# M3 캐시 계층 Implementation Plan

**Goal:** Redis 잔여석 캐시와 조회 경로(`GET /api/stock`), 캐시 전략 4종(`NONE` / `TTL_SHORT` / `INVALIDATE_ON_WRITE` / `REDIS_AS_SOT`), 전용 조회자가 측정하는 `phantomStockViews`·`staleWindowMs`를 추가하고, "어떤 전략도 stale을 0으로 만들지 못한다"를 UI가 설명하게 한다.

**Architecture:** M2 구조 그대로. 앱에 `StockCache`(키 `stock:{eventId}`)가 들어와 조회 경로를 담당한다. cache-aside 셋은 미스 때 DB 읽기 → `sleep(raceWindowMs)` → `SET` 순서라 삭제-재적재 경합이 재현된다. `REDIS_AS_SOT`는 카운터 단계를 Redis `DECR`로 바꾸고 `oversell` 전략을 무시한다. web은 조회자 2개를 10ms 간격으로 돌리고, 100번째 `OK` 응답 시각을 `soldOutAt`으로 잡아 그 뒤 `remaining > 0` 응답을 phantom으로 센다. 판정은 M2 그대로다.

**Tech Stack:** Kotlin 2.2, JDK 21, Spring Boot 3.5 (web, jdbc, data-redis), MySQL 8.4, Redis 7, Docker Compose.

**Spec:** `docs/milestones/specs/2026-09-20-m3-design.md` (결정 근거 `docs/decisions.md`)

## Global Constraints

- JDK 21 toolchain, `spring.threads.virtual.enabled=true`. 로컬 테스트는 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- JPA 금지. DB 접근은 `JdbcClient`만. Redis는 `StringRedisTemplate`만. 새 의존성은 `spring-boot-starter-data-redis` 하나.
- 좌석 단계 → 카운터 단계 순서와 좌석 되돌리기는 M2 그대로. 캐시 축은 카운터 단계 뒤(`INVALIDATE_ON_WRITE`의 `DEL`)나 카운터 단계 자체(`REDIS_AS_SOT`)만 건드린다.
- `DEL`은 `tx.execute`가 반환된 뒤, 즉 커밋 이후에.
- `REDIS_AS_SOT`의 `DECR`에는 sleep이 없다(원자 연산). cache-aside 미스 경로에는 DB 읽기와 `SET` 사이에 sleep이 있다.
- 조회자 상수: 2개, 10ms 간격, 꼬리 2초. 파라미터로 노출하지 않는다.
- `phantomStockViews`·`staleWindowMs`는 판정에 들어가지 않는다.
- 시각은 `System.nanoTime()` 기준, `gate.countDown()` 시점을 0으로 하는 상대 ms.
- 커밋은 `type: English description` 한 줄. 본문·Co-Authored-By 없음.

## File Structure

| 파일 | 변경 |
| --- | --- |
| `src/main/kotlin/lab/Models.kt` | `CacheStrategy`, `StrategySet.cacheConsistency`, `ReserveRequest.cache`, `StockResponse`, `View`, `Progress.lastView`, `ConsistencyMetrics.phantomStockViews/staleWindowMs`, `PerformanceMetrics.viewCount/viewDbReads`, `buildReport(views, soldOutAtMs)` |
| `src/test/kotlin/lab/ReportTest.kt` | phantom 집계, 판정 무관, soldOut 없음 |
| `build.gradle.kts`, `src/main/resources/application.yml`, `docker-compose.yml` | Redis 의존성·호스트·컨테이너 |
| `src/main/kotlin/lab/app/StockCache.kt` (신규) | `read`, `decrement`, `invalidate` |
| `src/main/kotlin/lab/app/ReservationService.kt` | `REDIS_AS_SOT` 분기, `INVALIDATE_ON_WRITE`의 `DEL` |
| `src/main/kotlin/lab/app/ReserveController.kt` | `GET /api/stock` |
| `src/main/kotlin/lab/web/LoadRunner.kt` | 키 시드, 조회자, `soldOutAt`, 기준선 키 |
| `src/main/resources/static/index.html` | `cacheConsistency` 라디오, oversell 비활성화, 조회자 값 줄, 고정 해설 |
| `scripts/dod.sh` | `cacheConsistency` 인자, M3 구간 |
| `docs/decisions.md`, `README.md` | 결정 기록, M3 결과 표, 로드맵 체크 |

---

### Task 1: 모델과 집계 함수 확장 (TDD)

**Files:**
- Modify: `src/main/kotlin/lab/Models.kt`
- Modify: `src/main/kotlin/lab/web/LoadRunner.kt` (`ReserveRequest` 생성 한 줄, 컴파일 유지용)
- Test: `src/test/kotlin/lab/ReportTest.kt`

**Interfaces:**
- Produces:
  - `enum class CacheStrategy { NONE, TTL_SHORT, INVALIDATE_ON_WRITE, REDIS_AS_SOT }`
  - `data class StrategySet(val oversell: OversellStrategy, val doubleBooking: DoubleBookingStrategy = DoubleBookingStrategy.NONE, val cacheConsistency: CacheStrategy = CacheStrategy.NONE)`
  - `data class ReserveRequest(val eventId: Long, val userId: Long, val seatNo: Int, val strategy: OversellStrategy, val doubleBooking: DoubleBookingStrategy, val cache: CacheStrategy, val raceWindowMs: Long)`
  - `data class StockResponse(val remaining: Int, val fromDb: Boolean)`
  - `data class View(val sentAtMs: Long, val remaining: Int, val fromDb: Boolean)`
  - `class Progress(seatCount: Int)`에 `lastView: AtomicInteger` (초기 -1)
  - `data class ConsistencyMetrics(oversoldCount: Int, ledgerMismatch: Int, doubleBookedSeats: Int, phantomStockViews: Int, staleWindowMs: Long)`
  - `data class PerformanceMetrics(throughput, p50Ms, p95Ms, p99Ms, errorCount, retryCount, dbConnectionPeak, duplicateKeyCount, viewCount: Int, viewDbReads: Int)`
  - `fun buildReport(runId: String, spec: RunSpec, samples: List<Sample>, remaining: Int, elapsedMs: Long, baselineThroughput: Double? = null, doubleBookedSeats: Int = 0, views: List<View> = emptyList(), soldOutAtMs: Long? = null): RunReport`

- [x] **Step 1: 실패하는 테스트 추가**

`src/test/kotlin/lab/ReportTest.kt` 클래스 안에 아래 두 테스트를 추가한다. 기존 9개는 그대로 둔다.

```kotlin
    @Test
    fun `counts phantom views after sold out and their window but leaves the verdict alone`() {
        val views = listOf(
            View(sentAtMs = 100, remaining = 3, fromDb = true),  // 매진 전, 정당
            View(sentAtMs = 300, remaining = 3, fromDb = false), // phantom
            View(sentAtMs = 450, remaining = 1, fromDb = false), // phantom, 마지막
            View(sentAtMs = 500, remaining = 0, fromDb = true),  // 정확
        )
        val r = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500, views = views, soldOutAtMs = 200)
        assertEquals(2, r.consistency.phantomStockViews)
        assertEquals(250, r.consistency.staleWindowMs)
        assertEquals(4, r.performance.viewCount)
        assertEquals(2, r.performance.viewDbReads)
        assertEquals(Verdict.PASS, r.verdict) // stale read는 설계 선택이지 판정 조건이 아니다
    }

    @Test
    fun `no phantom without a sold out moment`() {
        val views = listOf(View(100, 3, true), View(300, 3, false))
        val r = buildReport("r", spec, samples(ok = 2, soldOut = 0), remaining = 1, elapsedMs = 500, views = views)
        assertEquals(0, r.consistency.phantomStockViews)
        assertEquals(0, r.consistency.staleWindowMs)
        assertEquals(2, r.performance.viewCount)
    }
```

- [x] **Step 2: 실패 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test --tests 'lab.ReportTest' 2>&1 | tail -20`
Expected: 컴파일 실패. `Unresolved reference 'View'`, `phantomStockViews` 등.

- [x] **Step 3: Models.kt 수정**

`src/main/kotlin/lab/Models.kt`에서:

`DoubleBookingStrategy` 줄 아래에 추가:

```kotlin
enum class CacheStrategy { NONE, TTL_SHORT, INVALIDATE_ON_WRITE, REDIS_AS_SOT }
```

`StrategySet`을 교체:

```kotlin
data class StrategySet(
    val oversell: OversellStrategy,
    val doubleBooking: DoubleBookingStrategy = DoubleBookingStrategy.NONE,
    val cacheConsistency: CacheStrategy = CacheStrategy.NONE,
)
```

`ReserveRequest`·`ReserveResponse`를 교체하고 그 아래 두 타입을 추가:

```kotlin
data class ReserveRequest(
    val eventId: Long,
    val userId: Long,
    val seatNo: Int,
    val strategy: OversellStrategy,
    val doubleBooking: DoubleBookingStrategy,
    val cache: CacheStrategy,
    val raceWindowMs: Long,
)
data class ReserveResponse(val result: Outcome, val retries: Int = 0, val activeConnections: Int = 0)
data class StockResponse(val remaining: Int, val fromDb: Boolean)

// 조회자 한 번의 관측. sentAtMs는 예매 출발 기준 상대 ms.
data class View(val sentAtMs: Long, val remaining: Int, val fromDb: Boolean)
```

`Progress`에 한 줄 추가(주석도 갱신):

```kotlin
// 실행 중 그리드용 카운터. Jackson은 AtomicInteger를 숫자로 직렬화한다. seats[i]는 좌석 i+1의 확정 예약 수. lastView는 조회자가 마지막으로 본 잔여석(-1은 아직 없음).
class Progress(seatCount: Int) {
    val ok = AtomicInteger()
    val soldOut = AtomicInteger()
    val error = AtomicInteger()
    val seats: List<AtomicInteger> = List(seatCount) { AtomicInteger() }
    val lastView = AtomicInteger(-1)
}
```

`ConsistencyMetrics`·`PerformanceMetrics`를 교체:

```kotlin
// phantomStockViews·staleWindowMs는 판정에 들어가지 않는다. stale read는 설계 선택이다.
data class ConsistencyMetrics(
    val oversoldCount: Int,
    val ledgerMismatch: Int,
    val doubleBookedSeats: Int,
    val phantomStockViews: Int,
    val staleWindowMs: Long,
)
data class PerformanceMetrics(
    val throughput: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val errorCount: Int,
    val retryCount: Int,
    val dbConnectionPeak: Int,
    val duplicateKeyCount: Int,
    val viewCount: Int,
    val viewDbReads: Int,
)
```

`buildReport`를 교체:

```kotlin
fun buildReport(
    runId: String,
    spec: RunSpec,
    samples: List<Sample>,
    remaining: Int,
    elapsedMs: Long,
    baselineThroughput: Double? = null,
    doubleBookedSeats: Int = 0,
    views: List<View> = emptyList(),
    soldOutAtMs: Long? = null,
): RunReport {
    val ok = samples.count { it.outcome == Outcome.OK }
    val sorted = samples.map { it.latencyMs }.sorted()
    fun pct(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    // 매진(N번째 OK 응답) 이후에 보낸 조회가 잔여석 > 0을 받았으면 phantom.
    val phantom = if (soldOutAtMs == null) emptyList() else views.filter { it.sentAtMs > soldOutAtMs && it.remaining > 0 }
    val consistency = ConsistencyMetrics(
        oversoldCount = (ok - spec.seatCount).coerceAtLeast(0),
        ledgerMismatch = spec.seatCount - (ok + remaining),
        doubleBookedSeats = doubleBookedSeats,
        phantomStockViews = phantom.size,
        staleWindowMs = phantom.maxOfOrNull { it.sentAtMs - soldOutAtMs!! } ?: 0,
    )
    val performance = PerformanceMetrics(
        throughput = samples.size * 1000.0 / elapsedMs.coerceAtLeast(1),
        p50Ms = pct(0.50), p95Ms = pct(0.95), p99Ms = pct(0.99),
        errorCount = samples.count { it.outcome == Outcome.ERROR },
        retryCount = samples.sumOf { it.retries },
        dbConnectionPeak = samples.maxOfOrNull { it.activeConnections } ?: 0,
        duplicateKeyCount = samples.count { it.outcome == Outcome.DUPLICATE_KEY },
        viewCount = views.size,
        viewDbReads = views.count { it.fromDb },
    )
    val consistent = consistency.oversoldCount == 0 && consistency.ledgerMismatch == 0 && consistency.doubleBookedSeats == 0
    val verdict = when {
        !consistent -> Verdict.FAIL
        baselineThroughput != null && performance.throughput <= baselineThroughput * 0.5 -> Verdict.DEGRADED
        else -> Verdict.PASS
    }
    return RunReport(runId, spec, consistency, performance, verdict, baselineThroughput)
}
```

- [x] **Step 4: LoadRunner의 ReserveRequest 생성에 `cache` 추가 (컴파일 유지)**

`src/main/kotlin/lab/web/LoadRunner.kt`의 `.body(ReserveRequest(eventId, userId.toLong(), seatNo, spec.strategies.oversell, spec.strategies.doubleBooking, spec.raceWindowMs))`를 아래로 바꾼다.

```kotlin
                            .body(ReserveRequest(eventId, userId.toLong(), seatNo, spec.strategies.oversell, spec.strategies.doubleBooking, spec.strategies.cacheConsistency, spec.raceWindowMs))
```

- [x] **Step 5: 통과 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 11 tests pass.

- [x] **Step 6: Commit**

```bash
git add src/main/kotlin/lab/Models.kt src/main/kotlin/lab/web/LoadRunner.kt src/test/kotlin/lab/ReportTest.kt
git commit -m "feat: add cache strategy types and phantom view metrics to the report"
```

---

### Task 2: Redis 인프라, StockCache, 앱 경로

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Create: `src/main/kotlin/lab/app/StockCache.kt`
- Modify: `src/main/kotlin/lab/app/ReservationService.kt`
- Modify: `src/main/kotlin/lab/app/ReserveController.kt`

**Interfaces:**
- Consumes: Task 1의 `CacheStrategy`, `StockResponse`, `ReserveRequest.cache`
- Produces:
  - `class StockCache(redis: StringRedisTemplate, jdbc: JdbcClient)` (`@Profile("app")`)
    - `fun read(eventId: Long, cache: CacheStrategy, raceWindowMs: Long): StockResponse`
    - `fun decrement(eventId: Long): Boolean` — `DECR` 결과가 0 이상이면 true, 음수면 `INCR`로 되돌리고 false
    - `fun invalidate(eventId: Long)` — `DEL`
  - `GET /api/stock?eventId=&cache=&raceWindowMs=` → `StockResponse`
  - Redis 키 규칙 `stock:{eventId}` (web이 Task 3에서 같은 키를 시드한다)

- [x] **Step 1: 의존성과 설정**

`build.gradle.kts`의 `dependencies`에서 `spring-boot-starter-jdbc` 줄 아래에 추가:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

`src/main/resources/application.yml`의 `spring:` 아래(`datasource:`와 같은 들여쓰기)에 추가:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
```

`docker-compose.yml`을 아래로 교체:

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: lab
      MYSQL_USER: lab
      MYSQL_PASSWORD: lab
      MYSQL_ROOT_PASSWORD: root
    volumes:
      - ./db/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
      interval: 2s
      retries: 30

  redis:
    image: redis:7-alpine

  app-1: &app
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: app
      DB_URL: jdbc:mysql://mysql:3306/lab
      REDIS_HOST: redis
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_started

  app-2: *app

  web:
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: web
      DB_URL: jdbc:mysql://mysql:3306/lab
      REDIS_HOST: redis
      APP_URLS: http://app-1:8080,http://app-2:8080
    ports:
      - "8080:8080"
    depends_on:
      - app-1
      - app-2
      - redis
```

- [x] **Step 2: StockCache 작성**

`src/main/kotlin/lab/app/StockCache.kt`를 새로 만든다.

```kotlin
package lab.app

import lab.CacheStrategy
import lab.StockResponse
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Duration

// 잔여석 캐시. 키 stock:{eventId}. REDIS_AS_SOT에서는 캐시가 아니라 카운터 자체이며 web이 실행 시작 시 N을 심는다.
@Service
@Profile("app")
class StockCache(private val redis: StringRedisTemplate, private val jdbc: JdbcClient) {
    private fun key(eventId: Long) = "stock:$eventId"

    // 조회 경로. cache-aside 셋은 미스 때 DB 읽기 → sleep → SET 순서라 쓰기 쪽 DEL과 엇갈리면 옛값이 재적재된다.
    fun read(eventId: Long, cache: CacheStrategy, raceWindowMs: Long): StockResponse {
        redis.opsForValue().get(key(eventId))?.let { return StockResponse(it.toInt(), fromDb = false) }
        val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
            .param("id", eventId).query(Int::class.javaObjectType).single()
        if (cache != CacheStrategy.REDIS_AS_SOT) {
            Thread.sleep(raceWindowMs) // 경합 창 확대: DB 읽기와 캐시 적재 사이
            val ttl = if (cache == CacheStrategy.TTL_SHORT) Duration.ofSeconds(1) else Duration.ofSeconds(60)
            redis.opsForValue().set(key(eventId), remaining.toString(), ttl)
        }
        return StockResponse(remaining, fromDb = true)
    }

    // REDIS_AS_SOT 카운터 단계. DECR이 원자라 sleep이 없다. 음수면 되돌리고 매진.
    fun decrement(eventId: Long): Boolean {
        val left = redis.opsForValue().decrement(key(eventId))!!
        if (left >= 0) return true
        redis.opsForValue().increment(key(eventId))
        return false
    }

    fun invalidate(eventId: Long) {
        redis.delete(key(eventId))
    }
}
```

- [x] **Step 3: ReservationService 수정**

`src/main/kotlin/lab/app/ReservationService.kt`에서:

import에 추가:

```kotlin
import lab.CacheStrategy
```

생성자를 교체:

```kotlin
class ReservationService(private val jdbc: JdbcClient, private val tx: TransactionTemplate, private val stock: StockCache) {
```

`reserve` 안의 `counter(req.eventId, req.strategy, req.raceWindowMs)` 호출을 `counter(req)`로 바꾼다.

기존 `private fun counter(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse = when (strategy) {`를 `private fun dbCounter(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse = when (strategy) {`로 이름만 바꾸고, 그 위에 새 `counter`를 추가:

```kotlin
    // 카운터 단계. REDIS_AS_SOT는 Redis DECR이 결정하고 oversell 전략은 무시된다. DB는 뒤따라 동기로 쓴다.
    private fun counter(req: ReserveRequest): ReserveResponse {
        if (req.cache == CacheStrategy.REDIS_AS_SOT) {
            if (!stock.decrement(req.eventId)) return ReserveResponse(Outcome.SOLD_OUT)
            jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id").param("id", req.eventId).update()
            return ReserveResponse(Outcome.OK)
        }
        val res = dbCounter(req.eventId, req.strategy, req.raceWindowMs)
        // 커밋 이후에 지운다(PESSIMISTIC의 tx.execute가 반환된 뒤). 커밋 전이면 조회자가 커밋 전 값을 재적재한다.
        if (res.result == Outcome.OK && req.cache == CacheStrategy.INVALIDATE_ON_WRITE) stock.invalidate(req.eventId)
        return res
    }
```

- [x] **Step 4: ReserveController에 조회 매핑 추가**

`src/main/kotlin/lab/app/ReserveController.kt`를 아래로 교체:

```kotlin
package lab.app

import com.zaxxer.hikari.HikariDataSource
import lab.CacheStrategy
import lab.ReserveRequest
import lab.ReserveResponse
import lab.StockResponse
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@Profile("app")
class ReserveController(private val service: ReservationService, private val stockCache: StockCache, private val dataSource: DataSource) {
    // 풀은 첫 커넥션에서 늦게 만들어지므로 MXBean을 요청마다 읽는다.
    private fun active() = (dataSource as HikariDataSource).hikariPoolMXBean?.activeConnections ?: 0

    @PostMapping("/api/reserve")
    fun reserve(@RequestBody req: ReserveRequest): ReserveResponse {
        val before = active()
        val res = service.reserve(req)
        return res.copy(activeConnections = maxOf(before, active()))
    }

    @GetMapping("/api/stock")
    fun stock(@RequestParam eventId: Long, @RequestParam cache: CacheStrategy, @RequestParam raceWindowMs: Long): StockResponse =
        stockCache.read(eventId, cache, raceWindowMs)
}
```

- [x] **Step 5: 빌드와 테스트**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. `ReportTest`는 Spring 컨텍스트를 띄우지 않으므로 Redis 없이 통과한다.

- [x] **Step 6: 컨테이너 기동 확인**

Run: `docker compose up -d --build && sleep 25 && docker compose ps && docker compose logs app-1 2>&1 | grep -E 'Started|ERROR' | tail -3`
Expected: `redis`, `mysql`, `app-1`, `app-2`, `web` 모두 running. `Started LabApplicationKt` 로그, ERROR 없음. 스키마는 바뀌지 않았으므로 `down -v`는 필요 없다.

- [x] **Step 7: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yml docker-compose.yml
git commit -m "chore: add Redis container and spring-data-redis dependency"
git add src/main/kotlin/lab/app/StockCache.kt src/main/kotlin/lab/app/ReservationService.kt src/main/kotlin/lab/app/ReserveController.kt
git commit -m "feat: add stock cache read path and four cache strategies"
```

---

### Task 3: web 조회자, 키 시드, soldOutAt, 기준선 키

**Files:**
- Modify: `src/main/kotlin/lab/web/LoadRunner.kt`

**Interfaces:**
- Consumes: Task 1의 `View`, `StockResponse`, `Progress.lastView`, `buildReport(views, soldOutAtMs)`; Task 2의 `GET /api/stock`과 키 `stock:{eventId}`
- Produces: `RunReport`에 `phantomStockViews`, `staleWindowMs`, `viewCount`, `viewDbReads`가 채워진다. `Progress.lastView`가 실행 중 갱신된다.

- [x] **Step 1: LoadRunner 교체**

`src/main/kotlin/lab/web/LoadRunner.kt` 전체를 아래로 교체한다.

```kotlin
package lab.web

import lab.CacheStrategy
import lab.DoubleBookingStrategy
import lab.Outcome
import lab.OversellStrategy
import lab.Progress
import lab.ReserveRequest
import lab.ReserveResponse
import lab.RunReport
import lab.RunSpec
import lab.Sample
import lab.StockResponse
import lab.View
import lab.buildReport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// 조회자 상수. ponytail: 파라미터로 열 필요가 생기면 그때.
private const val VIEWERS = 2
private const val VIEW_INTERVAL_MS = 10L
private const val VIEW_TAIL_MS = 2_000L // TTL_SHORT의 1초 만료가 창 안에 잡히도록 예매가 끝난 뒤에도 조회를 이어 간다.

@Service
@Profile("web")
class LoadRunner(
    private val jdbc: JdbcClient,
    private val redis: StringRedisTemplate,
    @Value("\${lab.app-urls}") private val appUrls: List<String>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory()).build()
    // DEGRADED 기준선: 같은 (N, M, appInstances, raceWindowMs, doubleBooking, cacheConsistency)의 최근 NONE 처리량. ponytail: 메모리 보관.
    private val baselines = ConcurrentHashMap<List<Any>, Double>()

    fun run(runId: String, spec: RunSpec, progress: Progress): RunReport {
        prepareReservations(unique = spec.strategies.doubleBooking == DoubleBookingStrategy.UNIQUE_CONSTRAINT)
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO event (run_id, total, remaining) VALUES (:run, :n, :n)")
            .param("run", runId).param("n", spec.seatCount).update(keys)
        val eventId = keys.key!!.toLong()
        val cache = spec.strategies.cacheConsistency
        // REDIS_AS_SOT는 DECR이 빈 키에서 -1을 만들지 않도록 web이 N을 심는다. 다른 전략은 eventId가 새로 나와 키가 비어 있다.
        if (cache == CacheStrategy.REDIS_AS_SOT) redis.opsForValue().set("stock:$eventId", spec.seatCount.toString())
        val targets = appUrls.take(spec.appInstances)
        // 좌석 선택: seed가 같으면 같은 좌석. picks[userId - 1] ∈ 1..N 균등.
        val rnd = Random(spec.seed)
        val picks = IntArray(spec.userCount) { rnd.nextInt(spec.seatCount) + 1 }
        val gate = CountDownLatch(1)
        val start = AtomicLong() // gate.countDown() 직전에 찍는다. 모든 상대 시각의 0점.
        fun nowMs() = (System.nanoTime() - start.get()) / 1_000_000
        val soldOutAt = AtomicLong(-1) // N번째 OK 응답이 도착한 상대 ms. -1은 매진 없음.
        val stop = AtomicBoolean(false)
        val views = ConcurrentLinkedQueue<View>()

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val viewers = List(VIEWERS) { i ->
                pool.submit<Unit> {
                    gate.await()
                    val url = "${targets[i % targets.size]}/api/stock?eventId=$eventId&cache=$cache&raceWindowMs=${spec.raceWindowMs}"
                    while (!stop.get()) {
                        val sentAt = nowMs()
                        runCatching { client.get().uri(url).retrieve().body(StockResponse::class.java)!! }
                            .onSuccess { views.add(View(sentAt, it.remaining, it.fromDb)); progress.lastView.set(it.remaining) }
                            .onFailure { log.warn("stock view failed", it) }
                        Thread.sleep(VIEW_INTERVAL_MS)
                    }
                }
            }
            val futures = (1..spec.userCount).map { userId ->
                pool.submit<Sample> {
                    gate.await()
                    val seatNo = picks[userId - 1]
                    val t0 = System.nanoTime()
                    val res = runCatching {
                        client.post().uri("${targets[userId % targets.size]}/api/reserve")
                            .body(ReserveRequest(eventId, userId.toLong(), seatNo, spec.strategies.oversell, spec.strategies.doubleBooking, cache, spec.raceWindowMs))
                            .retrieve().body(ReserveResponse::class.java)!!
                    }.getOrElse { ex -> log.warn("reserve failed user={} seat={}", userId, seatNo, ex); ReserveResponse(Outcome.ERROR) }
                    when (res.result) {
                        Outcome.OK -> {
                            if (progress.ok.incrementAndGet() == spec.seatCount) soldOutAt.set(nowMs())
                            progress.seats[seatNo - 1].incrementAndGet()
                        }
                        Outcome.SOLD_OUT -> progress.soldOut.incrementAndGet()
                        Outcome.ERROR -> progress.error.incrementAndGet()
                        Outcome.SEAT_TAKEN, Outcome.DUPLICATE_KEY -> Unit
                    }
                    Sample(res.result, (System.nanoTime() - t0) / 1_000_000, res.retries, res.activeConnections)
                }
            }
            start.set(System.nanoTime())
            gate.countDown()
            val samples = futures.map { it.get() }
            val elapsedMs = nowMs() // throughput 분모는 예매 구간만. 조회 꼬리는 넣지 않는다.
            Thread.sleep(VIEW_TAIL_MS)
            stop.set(true)
            viewers.forEach { it.get() }

            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
                .param("id", eventId).query(Int::class.javaObjectType).single()
            val doubleBooked = jdbc.sql(
                "SELECT COUNT(*) FROM (SELECT seat_no FROM reservation WHERE event_id = :id GROUP BY seat_no HAVING COUNT(*) > 1) t",
            ).param("id", eventId).query(Long::class.javaObjectType).single().toInt()

            val key = listOf(spec.seatCount, spec.userCount, spec.appInstances, spec.raceWindowMs, spec.strategies.doubleBooking, cache)
            val isBaseline = spec.strategies.oversell == OversellStrategy.NONE
            val report = buildReport(
                runId, spec, samples, remaining, elapsedMs, if (isBaseline) null else baselines[key], doubleBooked,
                views.toList(), soldOutAt.get().takeIf { it >= 0 },
            )
            if (isBaseline) baselines[key] = report.performance.throughput
            return report
        }
    }

    // 이전 NONE 실행의 중복 행이 남아 있으면 유니크 인덱스 생성이 실패하므로 매 실행 전에 비운다. 동시 실행이 1건이라 DDL 경합은 없다.
    private fun prepareReservations(unique: Boolean) {
        jdbc.sql("TRUNCATE TABLE reservation").update()
        val has = jdbc.sql(
            "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'reservation' AND index_name = 'ux_reservation_seat'",
        ).query(Long::class.javaObjectType).single() > 0
        if (unique && !has) jdbc.sql("CREATE UNIQUE INDEX ux_reservation_seat ON reservation (event_id, seat_no)").update()
        if (!unique && has) jdbc.sql("DROP INDEX ux_reservation_seat ON reservation").update()
    }
}
```

- [x] **Step 2: 빌드**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: 컨테이너 재기동과 끝-끝 확인**

Run:

```bash
docker compose up -d --build && sleep 25
run() { # $1 oversell, $2 cacheConsistency
  local id
  id=$(curl -sf -X POST http://localhost:8080/api/runs -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\",\"doubleBooking\":\"UNIQUE_CONSTRAINT\",\"cacheConsistency\":\"$2\"}}" | jq -r .runId)
  while [ "$(curl -s http://localhost:8080/api/runs/$id | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s http://localhost:8080/api/runs/$id | jq -c '{status, v: .report.verdict, oversold: .report.consistency.oversoldCount, ledger: .report.consistency.ledgerMismatch, phantom: .report.consistency.phantomStockViews, stale: .report.consistency.staleWindowMs, views: .report.performance.viewCount, viewDb: .report.performance.viewDbReads, error}'
}
run CONDITIONAL_UPDATE NONE
run CONDITIONAL_UPDATE TTL_SHORT
run CONDITIONAL_UPDATE INVALIDATE_ON_WRITE
run NONE REDIS_AS_SOT
```

Expected:
- 네 실행 모두 `status=DONE`, `oversold=0`, `ledger=0`, `views`가 수백(예매 구간 + 꼬리 2초 동안 조회자 2개 × 10ms 간격).
- `NONE`: `phantom` 수백, `stale`이 실행 길이 + 2,000ms 근처, `viewDb=1`.
- `TTL_SHORT`: `phantom` 수십, `stale ≤ 1,100` 근처, `viewDb`가 실행 초 수 + 2 근처.
- `INVALIDATE_ON_WRITE`: 경합이 걸리면 `phantom` 수백·`stale`이 `NONE`과 비슷, 안 걸리면 0. `viewDb`는 수십.
- `NONE REDIS_AS_SOT`: `oversell=NONE`인데도 `oversold=0 ledger=0`(Redis가 결정), `phantom` 0\~1, `viewDb=0`.

`views=0`이면 web 로그(`docker compose logs web | grep 'stock view failed'`)에서 조회 URL·Redis 연결을 본다. `REDIS_AS_SOT`에서 `oversold>0`이면 키 시드가 실행되는지(`docker compose exec redis redis-cli keys 'stock:*'`)를 본다.

- [x] **Step 4: Commit**

```bash
git add src/main/kotlin/lab/web/LoadRunner.kt
git commit -m "feat: run stock viewers and measure phantom views after sold out"
```

---

### Task 4: cacheConsistency 라디오, 조회자 값 줄, 고정 해설 UI

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /api/runs`의 `strategies.cacheConsistency`, `GET /api/runs/{id}`의 `progress.lastView`, `progress.ok`

- [x] **Step 1: index.html 수정**

`<h1>concurrency-ticketing-lab — M2</h1>`을 `<h1>concurrency-ticketing-lab — M3</h1>`으로.

`<style>` 안 마지막 줄 뒤에 추가:

```css
#stock{margin:.5rem 0;font-weight:bold}
#note{font-size:13px;color:#444;border-top:1px solid #ddd;padding-top:.5rem;margin-top:1rem}
#note li{margin:.2rem 0}
```

`doubleBooking` 라디오 그룹의 닫는 `</label>` 뒤, `<button>실행</button>` 앞에 추가:

```html
  <label>cacheConsistency
    <label><input type="radio" name="cacheConsistency" value="NONE" checked> NONE</label>
    <label><input type="radio" name="cacheConsistency" value="TTL_SHORT"> TTL_SHORT</label>
    <label><input type="radio" name="cacheConsistency" value="INVALIDATE_ON_WRITE"> INVALIDATE_ON_WRITE</label>
    <label><input type="radio" name="cacheConsistency" value="REDIS_AS_SOT"> REDIS_AS_SOT</label>
  </label>
```

`<div id="grid"></div>` 앞에 추가:

```html
<div id="stock"></div>
```

`<pre id="out"></pre>` 뒤에 추가:

```html
<div id="note">
  <b>stale 노출은 어떤 전략에서도 0이 되지 않는다.</b> <code>phantomStockViews</code>는 판정에 들어가지 않는다. 캐시를 두는 순간 값이 두 벌이 되고, 둘을 맞추는 방식은 창의 크기를 정할 뿐 창을 닫지 못한다.
  <ul>
    <li><b>NONE</b>: TTL(60초)이 끝날 때까지 옛값. 실행이 그보다 짧으면 끝까지 stale.</li>
    <li><b>TTL_SHORT</b>: 창이 TTL(1초) 이하로 줄지만 닫히지 않는다. 0으로 만드는 TTL은 캐시를 없앤 것과 같다.</li>
    <li><b>INVALIDATE_ON_WRITE</b>: 마지막 쓰기의 삭제 뒤에 늦게 온 재적재는 다시 지워지지 않는다.</li>
    <li><b>REDIS_AS_SOT</b>: 두 번째 복사본이 없어 0에 가깝다. 그래도 읽은 값은 보는 순간 이미 과거다. 이때 oversell 전략은 무시된다.</li>
  </ul>
</div>
```

`<script>`에서:

`const f = ...` 줄의 `out = document.getElementById('out');`을 `out = document.getElementById('out'), stock = document.getElementById('stock');`로.

`f.onchange = ...` 한 줄을 아래로 교체:

```js
f.onchange = () => {
  sel.classList.toggle('hot', f.oversell.value === 'LOCAL_LOCK' && sel.value === '2');
  // REDIS_AS_SOT: Redis가 카운터라 DB 오버셀 전략은 무의미하다
  const sot = f.cacheConsistency.value === 'REDIS_AS_SOT';
  f.querySelectorAll('[name=oversell]').forEach(r => r.disabled = sot);
};
```

`function draw(...)` 앞에 추가:

```js
// 조회자가 마지막으로 본 잔여석과 실제 값(N − ok). 매진 뒤에도 왼쪽이 남아 있으면 그것이 stale read다.
function showStock(n, ok, lastView) {
  stock.textContent = `조회자가 보는 잔여석: ${lastView < 0 ? '-' : lastView} / 실제: ${n - ok}`;
}
```

`f.onsubmit` 안에서:
- `strategies: { oversell: d.oversell, doubleBooking: d.doubleBooking } };`를 `strategies: { oversell: d.oversell ?? 'NONE', doubleBooking: d.doubleBooking, cacheConsistency: d.cacheConsistency } };`로. 비활성화된 라디오는 `FormData`에 빠지므로 `REDIS_AS_SOT`일 때 `NONE`을 채운다.
- `draw(body.seatCount, 0, []);` 뒤에 `showStock(body.seatCount, 0, -1);` 추가.
- 타이머 안 `draw(body.seatCount, s.progress.ok, s.progress.seats);` 뒤에 `showStock(body.seatCount, s.progress.ok, s.progress.lastView);` 추가.

- [x] **Step 2: 브라우저 확인**

Run: `docker compose up -d --build && sleep 25`, 그리고 브라우저(Playwright MCP 가능)로 `http://localhost:8080`을 연다.
Expected:
- `cacheConsistency` 라디오 4개가 보이고 `NONE`이 기본.
- `REDIS_AS_SOT`를 고르면 `oversell` 라디오 5개가 회색(disabled)이 되고, 다른 값을 고르면 다시 활성화된다.
- `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT` + `TTL_SHORT`로 실행하면 실행 중 `조회자가 보는 잔여석: 100 / 실제: 37`처럼 두 숫자가 어긋나는 순간이 보이고, 끝난 뒤 리포트 JSON에 `phantomStockViews`·`staleWindowMs`·`viewCount`·`viewDbReads`가 있다. 판정은 `PASS`(또는 `DEGRADED`).
- `REDIS_AS_SOT` + `oversell=NONE`으로 실행하면 `oversoldCount=0`.
- 해설 블록이 리포트 아래에 항상 보인다.

- [x] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add cacheConsistency radio, live viewer stock line and stale read note"
```

---

### Task 5: M3 DoD 스크립트와 문서

**Files:**
- Modify: `scripts/dod.sh`
- Modify: `docs/decisions.md`
- Modify: `README.md`
- Modify: `docs/milestones/plans/2026-09-20-m3-cache-layer.md` (체크박스)

**Interfaces:**
- Consumes: 전체

- [x] **Step 1: DoD 스크립트 교체**

`scripts/dod.sh` 전체를 아래로 교체한다.

```bash
#!/usr/bin/env bash
# M1 DoD: LOCAL_LOCK은 앱 1대 PASS·2대 FAIL. CONDITIONAL_UPDATE/PESSIMISTIC/OPTIMISTIC은 양쪽 모두 정합. NONE은 양쪽 모두 FAIL.
# M2 DoD: CONDITIONAL_UPDATE + 앱 2대에서 좌석 NONE은 dup>0으로 FAIL, UNIQUE_CONSTRAINT는 dup=0·dupKey>0으로 PASS.
# M3 DoD: 정합 조합(CONDITIONAL_UPDATE + UNIQUE_CONSTRAINT)에서 캐시 전략 4종의 phantom·stale 창·DB 조회 수를 비교한다. 판정은 바뀌지 않는다.
# NONE을 먼저 돌려 같은 파라미터의 DEGRADED 기준선을 만든다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUNS=${RUNS:-3}
M2_RUNS=${M2_RUNS:-10}
M3_RUNS=${M3_RUNS:-5}

run() { # $1 oversell, $2 appInstances, $3 doubleBooking, $4 cacheConsistency (기본 NONE)
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":$2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\",\"doubleBooking\":\"$3\",\"cacheConsistency\":\"${4:-NONE}\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) dup=\(.report.consistency.doubleBookedSeats) dupKey=\(.report.performance.duplicateKeyCount) phantom=\(.report.consistency.phantomStockViews) stale=\(.report.consistency.staleWindowMs)ms viewDb=\(.report.performance.viewDbReads)/\(.report.performance.viewCount) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms retries=\(.report.performance.retryCount) connPeak=\(.report.performance.dbConnectionPeak)"'
}

for s in NONE LOCAL_LOCK CONDITIONAL_UPDATE PESSIMISTIC OPTIMISTIC; do
  for n in 1 2; do
    echo "== $s apps=$n doubleBooking=NONE"
    for _ in $(seq "$RUNS"); do run "$s" "$n" NONE; done
  done
done

for d in NONE UNIQUE_CONSTRAINT; do
  echo "== CONDITIONAL_UPDATE apps=2 doubleBooking=$d"
  for _ in $(seq "$M2_RUNS"); do run CONDITIONAL_UPDATE 2 "$d"; done
done

for c in NONE TTL_SHORT INVALIDATE_ON_WRITE REDIS_AS_SOT; do
  echo "== CONDITIONAL_UPDATE apps=2 doubleBooking=UNIQUE_CONSTRAINT cache=$c"
  for _ in $(seq "$M3_RUNS"); do run CONDITIONAL_UPDATE 2 UNIQUE_CONSTRAINT "$c"; done
done
```

- [x] **Step 2: 기동과 DoD 실행**

Run: `docker compose up -d --build && sleep 25 && ./scripts/dod.sh 2>&1 | tee /tmp/m3-dod.txt`
Expected:
- M1·M2 구간: M2 README 표와 같은 판정 패턴. 조회자 2개가 붙었지만 캐시 `NONE`이라 DB 조회는 실행당 1회이고 수치는 M2 범위 안이어야 한다. `oversold`·`ledger`·`dup` 패턴이 M2와 같은지로 회귀를 본다.
- M3 구간(`CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT`, 모두 `PASS` 또는 `DEGRADED`, `oversold=0 ledger=0 dup=0`):
  - `cache=NONE`: 5/5 `phantom` 수백, `stale`이 실행 길이 + 2,000ms 근처, `viewDb=1`.
  - `cache=TTL_SHORT`: 5/5 `phantom > 0`, `stale ≤ 1,100ms` 근처, `viewDb`가 한 자리\~열 초반.
  - `cache=INVALIDATE_ON_WRITE`: 경합이 걸린 실행은 `phantom` 수백·`stale`이 `NONE`과 비슷, 안 걸린 실행은 0. 재현율(5회 중 몇 회)을 README에 그대로 적는다. `viewDb`는 수십.
  - `cache=REDIS_AS_SOT`: `phantom` 0\~1, `stale` 0\~수 ms, `viewDb=0`.

`NONE`에서 `phantom=0`이면 `soldOutAt`이 찍히는지(`ok`가 100에 도달하는지)와 조회자가 실제로 도는지(`viewCount`)를 먼저 본다. `TTL_SHORT`의 `stale`이 1,100ms를 크게 넘으면 `SET ... EX`의 TTL 분기를 의심한다.

- [x] **Step 3: decisions.md에 결정 추가**

`docs/decisions.md` 표 끝에 추가한다.

```markdown
| 2026-09-20 | `REDIS_AS_SOT`는 카운터 단계를 Redis `DECR`로 바꾸고 `oversell` 전략을 무시한다. UI가 라디오를 비활성화 | "SoT가 Redis로 옮겨가면 DB 락 전략은 의미가 없다"가 교훈. write-through(DB가 결정하고 `DECR` 추가)는 축이 직교하지만 스펙의 "Redis가 SoT" 시나리오가 사라진다. |
| 2026-09-20 | `REDIS_AS_SOT`의 DB 쓰기는 동기(`remaining - 1`). 스펙의 "비동기"는 보류 | write-behind 큐는 관측 가치가 없고 원장 검증만 어렵게 한다. |
| 2026-09-20 | `phantomStockViews`·`staleWindowMs`는 판정에 넣지 않는다 | 어떤 전략도 0이 안 되는 지표를 FAIL 조건에 넣으면 M3부터 모든 실행이 FAIL. 학습 목표 4("stale read는 설계 선택")대로 별도 표시 + 고정 해설. |
| 2026-09-20 | 조회 부하는 전용 조회자 상수(2개, 10ms 간격, 예매 종료 후 꼬리 2초). 파라미터로 노출하지 않음 | 예매 직전 조회 모델은 조회가 출발선에 몰려 매진 이후 조회가 없다. 꼬리 2초는 `TTL_SHORT`의 1초 만료를 창 안에 잡기 위함. |
| 2026-09-20 | `soldOutAt` = N번째 `OK` 응답이 web에 도착한 시각 | DB가 0이 된 시점보다 약간 늦어 phantom을 적게 세는 쪽으로 보수적. DB 폴링 없이 기존 응답 처리에 한 줄. |
| 2026-09-20 | cache-aside 미스 경로는 DB 읽기 → `sleep(raceWindowMs)` → `SET`. `REDIS_AS_SOT`의 `DECR`에는 sleep 없음 | 다른 축과 같은 read-modify-write 원칙. 이 sleep이 `INVALIDATE_ON_WRITE`의 삭제-재적재 경합을 재현시킨다. `DECR`은 원자라 `CONDITIONAL_UPDATE`와 같은 이유로 sleep이 없다. |
| 2026-09-20 | 지표에 `staleWindowMs`(마지막 phantom − soldOutAt)와 `viewDbReads` 추가 | phantom 수는 폴링 주기에 좌우되지만 ms 창은 직관적. `viewDbReads`가 이 축의 비용 면(캐시가 DB를 얼마나 막는가). |
| 2026-09-20 | `INVALIDATE_ON_WRITE`의 `DEL`은 `tx.execute` 반환 뒤(커밋 이후) | 커밋 전에 지우면 조회자가 커밋 전 값을 재적재하는 별개의 버그. |
| 2026-09-20 | DEGRADED 기준선 키에 `cacheConsistency` 포함 | `INVALIDATE_ON_WRITE`는 쓰기마다 `DEL`, `REDIS_AS_SOT`는 경로가 다르므로 같은 캐시 전략끼리만 비교. |
```

- [x] **Step 4: README 갱신**

`README.md`에서:
- `## What works today (M0 + M1 + M2)` → `## What works today (M0 + M1 + M2 + M3)`. 목록 끝에 추가:
  - `- A Redis stock cache and a read path (\`GET /api/stock\`). Two viewer threads poll it every 10 ms during the run and for two seconds after, and every read that returns a positive remaining count after the N-th confirmed booking is a \`phantomStockView\`. Four strategies: \`NONE\` (cache-aside, 60 s TTL), \`TTL_SHORT\` (1 s TTL), \`INVALIDATE_ON_WRITE\` (delete the key after every decrement) and \`REDIS_AS_SOT\` (the counter lives in Redis, \`DECR\` decides, and the oversell strategy is ignored).`
  - `- \`phantomStockViews\`, \`staleWindowMs\` (how long after sell-out the last stale read was served) and \`viewDbReads\` (how many reads the cache did not absorb). None of them affect the verdict: a stale read is a design choice, and the UI says so under the report.`
- M2 표 아래에 Step 2 출력에서 뽑은 M3 표를 추가한다. 제목 문장: `Results from five runs each with the same parameters, \`CONDITIONAL_UPDATE\` + \`UNIQUE_CONSTRAINT\`, two app instances, all four \`cacheConsistency\` strategies:`. 열: `Cache | Verdict | Phantom views | Stale window | DB reads / views | Throughput (req/s) | p99`. 4행. 값은 실측치 범위. `INVALIDATE_ON_WRITE` 행의 phantom은 재현율을 `n/5 runs` 형태로 함께 적는다.
- 표 아래에 한 문단: `NONE` keeps the first value it saw until the TTL expires, which is after the run ends. `TTL_SHORT` bounds the window to the TTL and no lower; the TTL that closes it is zero, which is no cache. `INVALIDATE_ON_WRITE` is right until a reader that missed the cache, read the database and slept lands its stale value after the last write's delete, and then nothing deletes it again. `REDIS_AS_SOT` has no second copy, so the only staleness left is the time between reading a value and looking at it, which the measurement shows as zero or one view. The cost side is the DB reads column: the shorter the window, the less the cache absorbs.
- `## API` 예시 요청 본문의 `"strategies": { "oversell": "NONE", "doubleBooking": "NONE" }`을 `"strategies": { "oversell": "NONE", "doubleBooking": "NONE", "cacheConsistency": "NONE" }`으로 바꾼다.
- `## How it is put together`의 첫 문단 끝에 한 문장 추가: `Redis holds the stock cache under \`stock:{eventId}\`; under \`REDIS_AS_SOT\` it holds the counter itself.` 파일 목록에 `  app/StockCache.kt         stock:{eventId} read path, DECR counter, DEL on write` 줄을 `app/ReservationService.kt` 아래에 추가하고, `app/ReservationService.kt` 설명을 `seat step, then one SQL path per counter strategy, or DECR under REDIS_AS_SOT`로 바꾼다.
- 로드맵 M3 항목을 `- [x] M3 Cache layer. Redis, four stale-read strategies, \`phantomStockViews\``로 바꾼다.
- `## Documents`에 `- [M3 design](docs/milestones/specs/2026-09-20-m3-design.md) and [M3 implementation plan](docs/milestones/plans/2026-09-20-m3-cache-layer.md) (Korean)` 줄을 추가한다.
- `## Running it`의 `You need JDK 21 and Docker.`는 그대로. `docker compose up -d --build` 설명에 Redis가 함께 뜬다는 말은 넣지 않는다(compose가 말한다).

- [x] **Step 5: Commit**

```bash
git add scripts/dod.sh
git commit -m "chore: extend DoD script with M3 cache strategy runs"
git add docs/decisions.md
git commit -m "docs: log M3 decisions"
git add README.md
git commit -m "docs: record M3 results and mark roadmap"
```

- [x] **Step 6: 계획 체크박스 갱신 후 커밋**

이 문서의 `- [ ]`를 모두 `- [x]`로 바꾼다.

```bash
git add docs/milestones/plans/2026-09-20-m3-cache-layer.md
git commit -m "docs: mark M3 plan as done"
```
