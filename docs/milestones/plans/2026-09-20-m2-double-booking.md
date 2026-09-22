# M2 중복 배정 Implementation Plan

**Goal:** 좌석 단위 예약(`reservation` 테이블), 실행 시작 시 켜고 끄는 유니크 인덱스, `doubleBookedSeats`·`duplicateKeyCount` 지표, 그리드의 빨강 셀을 추가해 "유니크 OFF에서 중복 배정 재현, ON에서 0건 + 중복키 카운트 노출"이 화면에서 보이게 한다.

**Architecture:** M1 구조 그대로. 한 요청은 **좌석 단계**(`reservation` INSERT, `doubleBooking` 전략) → **카운터 단계**(`event.remaining` 차감, 기존 `oversell` 전략) 순서로 진행하고, 카운터가 OK가 아니면 자기 행을 DELETE로 되돌린다. web은 실행 시작 시 `TRUNCATE` 후 유니크 인덱스를 원하는 상태로 맞추고, `Random(seed)`로 좌석을 미리 뽑아 보내며, 실행 후 DB에서 중복 좌석 수를 직접 센다.

**Tech Stack:** Kotlin 2.2, JDK 21, Spring Boot 3.5 (web, jdbc), MySQL 8.4, Docker Compose.

**Spec:** `docs/milestones/specs/2026-09-20-m2-design.md` (결정 근거 `docs/decisions.md`)

## Global Constraints

- JDK 21 toolchain, `spring.threads.virtual.enabled=true`. 로컬 테스트는 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- JPA 금지. DB 접근은 `JdbcClient`만. 새 의존성 없음.
- 좌석 단계가 카운터 단계보다 먼저. `LOCAL_LOCK`의 `synchronized`와 `PESSIMISTIC`의 트랜잭션은 카운터 단계만 감싼다.
- 좌석 `NONE`은 조회 → `Thread.sleep(raceWindowMs)` → INSERT. `UNIQUE_CONSTRAINT`는 INSERT만, 조회도 sleep도 없음.
- `seat` 테이블, `hotspot`, 보조 인덱스, `created_at`은 만들지 않는다.
- 스키마는 MySQL initdb(`db/schema.sql`)로 생성되므로 스키마 변경 뒤에는 `docker compose down -v`로 볼륨을 지우고 다시 올린다.
- 커밋은 `type: English description` 한 줄. 본문·Co-Authored-By 없음.

## File Structure

| 파일 | 변경 |
| --- | --- |
| `db/schema.sql` | `reservation` 테이블 추가 |
| `src/main/kotlin/lab/Models.kt` | `DoubleBookingStrategy`, `StrategySet.doubleBooking`, `Outcome.SEAT_TAKEN/DUPLICATE_KEY`, `ReserveRequest.seatNo/doubleBooking`, `Progress(seatCount).seats`, `ConsistencyMetrics.doubleBookedSeats`, `PerformanceMetrics.duplicateKeyCount`, `buildReport(doubleBookedSeats)` |
| `src/test/kotlin/lab/ReportTest.kt` | 중복 좌석 FAIL, 중복키 카운트 |
| `src/main/kotlin/lab/app/ReservationService.kt` | 좌석 단계 + 되돌리기. `reserve(req: ReserveRequest)` |
| `src/main/kotlin/lab/app/ReserveController.kt` | 호출 한 줄 `service.reserve(req)` |
| `src/main/kotlin/lab/web/LoadRunner.kt` | TRUNCATE + 인덱스 토글, 좌석 선택, `seats` 증가, 중복 좌석 집계, 기준선 키 |
| `src/main/kotlin/lab/web/RunController.kt` | `Progress(spec.seatCount)` |
| `src/main/resources/static/index.html` | `doubleBooking` 라디오, 좌석별 색(회색/초록/빨강) |
| `scripts/dod.sh` | `doubleBooking` 인자, M2 구간 10회 × 2 |
| `docs/decisions.md`, `README.md` | 결정 기록, M2 결과 표, 로드맵 체크 |

---

### Task 1: 모델과 집계 함수 확장 (TDD)

**Files:**
- Modify: `src/main/kotlin/lab/Models.kt`
- Test: `src/test/kotlin/lab/ReportTest.kt`

**Interfaces:**
- Produces:
  - `enum class DoubleBookingStrategy { NONE, UNIQUE_CONSTRAINT }`
  - `data class StrategySet(val oversell: OversellStrategy, val doubleBooking: DoubleBookingStrategy = DoubleBookingStrategy.NONE)`
  - `enum class Outcome { OK, SOLD_OUT, SEAT_TAKEN, DUPLICATE_KEY, ERROR }`
  - `data class ReserveRequest(val eventId: Long, val userId: Long, val seatNo: Int, val strategy: OversellStrategy, val doubleBooking: DoubleBookingStrategy, val raceWindowMs: Long)`
  - `class Progress(seatCount: Int)` with `ok`, `soldOut`, `error: AtomicInteger`, `seats: List<AtomicInteger>` (크기 `seatCount`)
  - `data class ConsistencyMetrics(val oversoldCount: Int, val ledgerMismatch: Int, val doubleBookedSeats: Int)`
  - `data class PerformanceMetrics(throughput, p50Ms, p95Ms, p99Ms, errorCount, retryCount, dbConnectionPeak, duplicateKeyCount: Int)`
  - `fun buildReport(runId: String, spec: RunSpec, samples: List<Sample>, remaining: Int, elapsedMs: Long, baselineThroughput: Double? = null, doubleBookedSeats: Int = 0): RunReport`

- [x] **Step 1: 실패하는 테스트 추가**

`src/test/kotlin/lab/ReportTest.kt` 클래스 안에 아래 두 테스트를 추가한다. 기존 7개는 그대로 둔다.

```kotlin
    @Test
    fun `fail when a seat is double booked`() {
        val r = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500, doubleBookedSeats = 1)
        assertEquals(1, r.consistency.doubleBookedSeats)
        assertEquals(0, r.consistency.oversoldCount)
        assertEquals(Verdict.FAIL, r.verdict)
    }

    @Test
    fun `counts duplicate key rejections and ignores seat rejections in the ledger`() {
        val s = samples(ok = 3, soldOut = 0) +
            List(2) { Sample(Outcome.DUPLICATE_KEY, 1) } +
            listOf(Sample(Outcome.SEAT_TAKEN, 1))
        val r = buildReport("r", spec, s, remaining = 0, elapsedMs = 100)
        assertEquals(2, r.performance.duplicateKeyCount)
        assertEquals(0, r.consistency.oversoldCount)
        assertEquals(0, r.consistency.ledgerMismatch)
        assertEquals(Verdict.PASS, r.verdict)
    }
```

- [x] **Step 2: 실패 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test --tests lab.ReportTest`
Expected: 컴파일 실패. `doubleBookedSeats`, `Outcome.DUPLICATE_KEY`, `Outcome.SEAT_TAKEN`, `duplicateKeyCount` 미정의.

- [x] **Step 3: Models.kt 교체**

`src/main/kotlin/lab/Models.kt` 전체를 아래로 교체한다.

```kotlin
package lab

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.random.Random

enum class OversellStrategy { NONE, LOCAL_LOCK, CONDITIONAL_UPDATE, PESSIMISTIC, OPTIMISTIC }
enum class DoubleBookingStrategy { NONE, UNIQUE_CONSTRAINT }

data class StrategySet(
    val oversell: OversellStrategy,
    val doubleBooking: DoubleBookingStrategy = DoubleBookingStrategy.NONE,
)

data class RunSpec(
    val seatCount: Int,
    val userCount: Int,
    val appInstances: Int = 2,
    val seed: Long = Random.nextLong(),
    val raceWindowMs: Long = 20,
    val strategies: StrategySet,
) {
    init {
        require(seatCount in 1..1000) { "seatCount 1..1000" }
        require(userCount in 1..10_000) { "userCount 1..10000" }
        require(appInstances in 1..2) { "appInstances 1..2" }
        require(raceWindowMs in 0..200) { "raceWindowMs 0..200" }
    }
}

// SEAT_TAKEN은 앱 조회로 거절, DUPLICATE_KEY는 DB 유니크 인덱스가 거절. 둘 다 카운터를 건드리지 않는다.
enum class Outcome { OK, SOLD_OUT, SEAT_TAKEN, DUPLICATE_KEY, ERROR }

data class ReserveRequest(
    val eventId: Long,
    val userId: Long,
    val seatNo: Int,
    val strategy: OversellStrategy,
    val doubleBooking: DoubleBookingStrategy,
    val raceWindowMs: Long,
)
data class ReserveResponse(val result: Outcome, val retries: Int = 0, val activeConnections: Int = 0)

data class Sample(val outcome: Outcome, val latencyMs: Long, val retries: Int = 0, val activeConnections: Int = 0)

// 실행 중 그리드용 카운터. Jackson은 AtomicInteger를 숫자로 직렬화한다. seats[i]는 좌석 i+1의 확정 예약 수.
class Progress(seatCount: Int) {
    val ok = AtomicInteger()
    val soldOut = AtomicInteger()
    val error = AtomicInteger()
    val seats: List<AtomicInteger> = List(seatCount) { AtomicInteger() }
}

data class ConsistencyMetrics(val oversoldCount: Int, val ledgerMismatch: Int, val doubleBookedSeats: Int)
data class PerformanceMetrics(
    val throughput: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val errorCount: Int,
    val retryCount: Int,
    val dbConnectionPeak: Int,
    val duplicateKeyCount: Int,
)
enum class Verdict { PASS, DEGRADED, FAIL }

data class RunReport(
    val runId: String,
    val spec: RunSpec,
    val consistency: ConsistencyMetrics,
    val performance: PerformanceMetrics,
    val verdict: Verdict,
    val baselineThroughput: Double?,
)

fun buildReport(
    runId: String,
    spec: RunSpec,
    samples: List<Sample>,
    remaining: Int,
    elapsedMs: Long,
    baselineThroughput: Double? = null,
    doubleBookedSeats: Int = 0,
): RunReport {
    val ok = samples.count { it.outcome == Outcome.OK }
    val sorted = samples.map { it.latencyMs }.sorted()
    fun pct(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    val consistency = ConsistencyMetrics(
        oversoldCount = (ok - spec.seatCount).coerceAtLeast(0),
        ledgerMismatch = spec.seatCount - (ok + remaining),
        doubleBookedSeats = doubleBookedSeats,
    )
    val performance = PerformanceMetrics(
        throughput = samples.size * 1000.0 / elapsedMs.coerceAtLeast(1),
        p50Ms = pct(0.50), p95Ms = pct(0.95), p99Ms = pct(0.99),
        errorCount = samples.count { it.outcome == Outcome.ERROR },
        retryCount = samples.sumOf { it.retries },
        dbConnectionPeak = samples.maxOfOrNull { it.activeConnections } ?: 0,
        duplicateKeyCount = samples.count { it.outcome == Outcome.DUPLICATE_KEY },
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

- [x] **Step 4: main 컴파일 오류가 예상 범위인지 확인**

테스트 컴파일은 `main` 컴파일에 의존하는데, `ReservationService`·`LoadRunner`·`RunController`가 아직 옛 시그니처를 쓰므로 이 단계에서는 테스트를 돌릴 수 없다. 오류가 예상 범위인지만 본다.

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew compileKotlin`
Expected: 실패. 오류는 세 가지뿐이어야 한다. `RunController`의 `Progress()` 인자 없음, `LoadRunner`의 `ReserveRequest` 인자 부족, `ReserveController`가 부르는 `reserve(eventId, strategy, raceWindowMs)`는 아직 존재하므로 오류 없음. 그 밖의 오류가 나면 `Models.kt`를 다시 본다.

테스트 통과 확인은 Task 3 Step 3에서 한다.

- [x] **Step 5: Commit 보류**

빌드가 깨진 상태로 커밋하지 않는다. Task 3 Step 5에서 Task 1·2·3을 커밋 하나로 묶는다.

---

### Task 2: 스키마와 앱 좌석 단계

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/kotlin/lab/app/ReservationService.kt`
- Modify: `src/main/kotlin/lab/app/ReserveController.kt`

**Interfaces:**
- Consumes: Task 1의 `ReserveRequest`, `DoubleBookingStrategy`, `Outcome`
- Produces: `ReservationService.reserve(req: ReserveRequest): ReserveResponse`. 테이블 `reservation(id, event_id, seat_no, user_id)`. 유니크 인덱스 이름 `ux_reservation_seat`는 Task 3의 web이 만들고 지운다.

- [x] **Step 1: 스키마에 테이블 추가**

`db/schema.sql` 끝에 추가한다. 유니크 인덱스는 여기 넣지 않는다.

```sql

-- 유니크 인덱스 ux_reservation_seat (event_id, seat_no)는 web이 실행 시작 시 CREATE/DROP으로 토글한다.
CREATE TABLE IF NOT EXISTS reservation (
    id       BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    seat_no  INT    NOT NULL,
    user_id  BIGINT NOT NULL
);
```

- [x] **Step 2: 서비스 교체**

`src/main/kotlin/lab/app/ReservationService.kt` 전체를 아래로 교체한다. 카운터 단계(`counter`)는 M1의 `when` 그대로다.

```kotlin
package lab.app

import lab.DoubleBookingStrategy
import lab.Outcome
import lab.OversellStrategy
import lab.ReserveRequest
import lab.ReserveResponse
import org.springframework.context.annotation.Profile
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
@Profile("app")
class ReservationService(private val jdbc: JdbcClient, private val tx: TransactionTemplate) {
    private val localLock = Any() // ponytail: 전역 락. 실행당 이벤트가 하나라 이벤트별 락과 결과가 같다.

    // 좌석 먼저, 카운터 나중. 좌석을 잡은 요청만 카운터를 차감하고, 카운터가 OK가 아니면 자기 행을 되돌린다.
    fun reserve(req: ReserveRequest): ReserveResponse {
        takeSeat(req)?.let { return ReserveResponse(it) }
        val res = counter(req.eventId, req.strategy, req.raceWindowMs)
        if (res.result != Outcome.OK) {
            jdbc.sql("DELETE FROM reservation WHERE event_id = :e AND seat_no = :s AND user_id = :u")
                .param("e", req.eventId).param("s", req.seatNo).param("u", req.userId).update()
        }
        return res
    }

    // 좌석 단계. 잡았으면 null, 거절이면 그 Outcome. 락도 트랜잭션도 없다.
    private fun takeSeat(req: ReserveRequest): Outcome? {
        val insert = jdbc.sql("INSERT INTO reservation (event_id, seat_no, user_id) VALUES (:e, :s, :u)")
            .param("e", req.eventId).param("s", req.seatNo).param("u", req.userId)
        return when (req.doubleBooking) {
            DoubleBookingStrategy.NONE -> {
                val taken = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE event_id = :e AND seat_no = :s")
                    .param("e", req.eventId).param("s", req.seatNo).query(Long::class.javaObjectType).single() > 0
                if (taken) return Outcome.SEAT_TAKEN
                Thread.sleep(req.raceWindowMs) // 경합 창 확대: 조회와 INSERT 사이
                insert.update()
                null
            }
            DoubleBookingStrategy.UNIQUE_CONSTRAINT -> try {
                insert.update()
                null
            } catch (e: DuplicateKeyException) {
                Outcome.DUPLICATE_KEY
            }
        }
    }

    private fun counter(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse = when (strategy) {
        OversellStrategy.NONE -> readSleepWrite(eventId, raceWindowMs)
        OversellStrategy.LOCAL_LOCK -> synchronized(localLock) { readSleepWrite(eventId, raceWindowMs) }
        OversellStrategy.CONDITIONAL_UPDATE -> {
            val updated = jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id AND remaining > 0")
                .param("id", eventId).update()
            ReserveResponse(if (updated == 1) Outcome.OK else Outcome.SOLD_OUT)
        }
        OversellStrategy.PESSIMISTIC -> tx.execute {
            // FOR UPDATE가 행 락을 잡고, 트랜잭션이 끝날 때까지 커넥션을 점유한다.
            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id FOR UPDATE")
                .param("id", eventId).query(Int::class.javaObjectType).single()
            Thread.sleep(raceWindowMs)
            if (remaining <= 0) ReserveResponse(Outcome.SOLD_OUT)
            else {
                jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id").param("id", eventId).update()
                ReserveResponse(Outcome.OK)
            }
        }!!
        OversellStrategy.OPTIMISTIC -> optimistic(eventId, raceWindowMs)
    }

    // 트랜잭션 없음: autocommit 두 문장. sleep 중 커넥션을 반납해야 lost update가 관측된다.
    private fun readSleepWrite(eventId: Long, raceWindowMs: Long): ReserveResponse {
        val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
            .param("id", eventId).query(Int::class.javaObjectType).single()
        Thread.sleep(raceWindowMs) // 경합 창 확대
        if (remaining <= 0) return ReserveResponse(Outcome.SOLD_OUT)
        jdbc.sql("UPDATE event SET remaining = :r WHERE id = :id")
            .param("r", remaining - 1).param("id", eventId).update()
        return ReserveResponse(Outcome.OK)
    }

    // 상한 없음: 버전당 정확히 한 요청이 이기므로 진행이 보장된다.
    private fun optimistic(eventId: Long, raceWindowMs: Long): ReserveResponse {
        var retries = 0
        while (true) {
            val (remaining, version) = jdbc.sql("SELECT remaining, version FROM event WHERE id = :id")
                .param("id", eventId).query { rs, _ -> rs.getInt(1) to rs.getLong(2) }.single()
            if (remaining <= 0) return ReserveResponse(Outcome.SOLD_OUT, retries)
            Thread.sleep(raceWindowMs)
            val updated = jdbc.sql("UPDATE event SET remaining = remaining - 1, version = version + 1 WHERE id = :id AND version = :v")
                .param("id", eventId).param("v", version).update()
            if (updated == 1) return ReserveResponse(Outcome.OK, retries)
            retries++
        }
    }
}
```

- [x] **Step 3: 컨트롤러 호출 한 줄 수정**

`src/main/kotlin/lab/app/ReserveController.kt`에서

```kotlin
        val res = service.reserve(req.eventId, req.strategy, req.raceWindowMs)
```
를
```kotlin
        val res = service.reserve(req)
```
로 바꾼다.

- [x] **Step 4: 컴파일 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew compileKotlin`
Expected: `LoadRunner.kt`(`ReserveRequest` 인자 부족)와 `RunController.kt`(`Progress()` 인자 없음)만 실패. `app` 패키지 오류는 없어야 한다.

---

### Task 3: web 인덱스 토글, 좌석 선택, 중복 집계

**Files:**
- Modify: `src/main/kotlin/lab/web/LoadRunner.kt`
- Modify: `src/main/kotlin/lab/web/RunController.kt`

**Interfaces:**
- Consumes: Task 1의 `Progress(seatCount)`, `ReserveRequest`, `buildReport(doubleBookedSeats)`. Task 2의 `reservation` 테이블.
- Produces: `GET /api/runs/{id}`의 `progress.seats: number[]`(길이 N), `report.consistency.doubleBookedSeats`, `report.performance.duplicateKeyCount`. 요청 본문 `strategies.doubleBooking`(생략 시 `NONE`).

- [x] **Step 1: LoadRunner 교체**

`src/main/kotlin/lab/web/LoadRunner.kt` 전체를 아래로 교체한다.

```kotlin
package lab.web

import lab.DoubleBookingStrategy
import lab.Outcome
import lab.OversellStrategy
import lab.Progress
import lab.ReserveRequest
import lab.ReserveResponse
import lab.RunReport
import lab.RunSpec
import lab.Sample
import lab.buildReport
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random

@Service
@Profile("web")
class LoadRunner(
    private val jdbc: JdbcClient,
    @Value("\${lab.app-urls}") private val appUrls: List<String>,
) {
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory()).build()
    // DEGRADED 기준선: 같은 (N, M, appInstances, raceWindowMs, doubleBooking)의 최근 NONE 처리량. ponytail: 메모리 보관.
    private val baselines = ConcurrentHashMap<List<Any>, Double>()

    fun run(runId: String, spec: RunSpec, progress: Progress): RunReport {
        prepareReservations(unique = spec.strategies.doubleBooking == DoubleBookingStrategy.UNIQUE_CONSTRAINT)
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO event (run_id, total, remaining) VALUES (:run, :n, :n)")
            .param("run", runId).param("n", spec.seatCount).update(keys)
        val eventId = keys.key!!.toLong()
        val targets = appUrls.take(spec.appInstances)
        // 좌석 선택: seed가 같으면 같은 좌석. picks[userId - 1] ∈ 1..N 균등.
        val rnd = Random(spec.seed)
        val picks = IntArray(spec.userCount) { rnd.nextInt(spec.seatCount) + 1 }
        val gate = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val futures = (1..spec.userCount).map { userId ->
                pool.submit<Sample> {
                    gate.await()
                    val seatNo = picks[userId - 1]
                    val t0 = System.nanoTime()
                    val res = runCatching {
                        client.post().uri("${targets[userId % targets.size]}/api/reserve")
                            .body(ReserveRequest(eventId, userId.toLong(), seatNo, spec.strategies.oversell, spec.strategies.doubleBooking, spec.raceWindowMs))
                            .retrieve().body(ReserveResponse::class.java)!!
                    }.getOrElse { ReserveResponse(Outcome.ERROR) }
                    when (res.result) {
                        Outcome.OK -> { progress.ok.incrementAndGet(); progress.seats[seatNo - 1].incrementAndGet() }
                        Outcome.SOLD_OUT -> progress.soldOut.incrementAndGet()
                        Outcome.ERROR -> progress.error.incrementAndGet()
                        Outcome.SEAT_TAKEN, Outcome.DUPLICATE_KEY -> Unit
                    }
                    Sample(res.result, (System.nanoTime() - t0) / 1_000_000, res.retries, res.activeConnections)
                }
            }
            val start = System.nanoTime()
            gate.countDown()
            val samples = futures.map { it.get() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
                .param("id", eventId).query(Int::class.javaObjectType).single()
            val doubleBooked = jdbc.sql(
                "SELECT COUNT(*) FROM (SELECT seat_no FROM reservation WHERE event_id = :id GROUP BY seat_no HAVING COUNT(*) > 1) t",
            ).param("id", eventId).query(Long::class.javaObjectType).single().toInt()

            val key = listOf(spec.seatCount, spec.userCount, spec.appInstances, spec.raceWindowMs, spec.strategies.doubleBooking)
            val isBaseline = spec.strategies.oversell == OversellStrategy.NONE
            val report = buildReport(runId, spec, samples, remaining, elapsedMs, if (isBaseline) null else baselines[key], doubleBooked)
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

- [x] **Step 2: RunController에서 Progress 크기 지정**

`src/main/kotlin/lab/web/RunController.kt`에서

```kotlin
        val progress = Progress()
```
를
```kotlin
        val progress = Progress(spec.seatCount)
```
로 바꾼다.

- [x] **Step 3: 빌드와 전체 테스트**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test`
Expected: BUILD SUCCESSFUL, `ReportTest` 9개 PASS.

- [x] **Step 4: 컨테이너 재생성 후 수동 확인**

스키마가 바뀌었으므로 볼륨을 지운다.

Run:
```bash
docker compose down -v && docker compose up -d --build && sleep 25
curl -s -X POST localhost:8080/api/runs -H 'Content-Type: application/json' \
  -d '{"seatCount":100,"userCount":1000,"appInstances":2,"raceWindowMs":20,"strategies":{"oversell":"CONDITIONAL_UPDATE","doubleBooking":"NONE"}}'
```
`runId`로 `curl -s localhost:8080/api/runs/<id> | jq '.report.consistency, .report.performance.duplicateKeyCount, (.progress.seats | map(select(. >= 2)) | length)'`를 DONE 뒤에 실행.
Expected: `oversoldCount 0`, `ledgerMismatch 0`, `doubleBookedSeats > 0`, `duplicateKeyCount 0`, `seats`에서 2 이상인 칸 수가 `doubleBookedSeats`와 같음.

같은 요청을 `"doubleBooking":"UNIQUE_CONSTRAINT"`로 반복.
Expected: `doubleBookedSeats 0`, `duplicateKeyCount` 약 900, 판정 `PASS` 또는 `DEGRADED`.

`docker compose exec mysql mysql -ulab -plab lab -e "SHOW INDEX FROM reservation"`로 두 번째 실행 뒤 `ux_reservation_seat`가 있고, `NONE`으로 한 번 더 돌린 뒤에는 없어졌는지 확인한다.

- [x] **Step 5: Commit (Task 1·2·3을 커밋 하나로)**

모델 변경은 app·web과 같이 가야 컴파일되므로 세 Task를 나누면 중간 커밋이 빌드를 깨뜨린다. 하나의 논리적 변경("좌석 예약 추가")으로 보고 커밋 하나로 묶는다.

```bash
git add src/main/kotlin/lab/Models.kt src/test/kotlin/lab/ReportTest.kt db/schema.sql \
        src/main/kotlin/lab/app/ReservationService.kt src/main/kotlin/lab/app/ReserveController.kt \
        src/main/kotlin/lab/web/LoadRunner.kt src/main/kotlin/lab/web/RunController.kt
git commit -m "feat: add seat reservations with a runtime-toggled unique index"
```

---

### Task 4: doubleBooking 라디오와 빨강 셀 UI

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: Task 3의 `progress.seats`, 요청 본문 `strategies.doubleBooking`

- [x] **Step 1: index.html 수정**

1. `<title>` 아래 `<style>`의 `#grid i.over{background:#f80}` 다음 줄에 추가:
```css
#grid i.dup{background:#d33}
```
2. `<h1>`을 `concurrency-ticketing-lab — M2`로 바꾼다.
3. `oversell` 라디오 `</label>` 바로 뒤, `<button>실행</button>` 앞에 추가:
```html
  <label>doubleBooking
    <label><input type="radio" name="doubleBooking" value="NONE" checked> NONE</label>
    <label><input type="radio" name="doubleBooking" value="UNIQUE_CONSTRAINT"> UNIQUE_CONSTRAINT</label>
  </label>
```
4. `draw` 함수를 아래로 교체:
```js
// 칸 i = 좌석 i+1. seats[i]가 0이면 회색, 1이면 초록, 2 이상이면 빨강(중복 배정). N을 넘는 판매분은 주황 칸으로 뒤에 붙인다.
function draw(n, ok, seats) {
  const over = Math.max(0, ok - n);
  grid.innerHTML = Array.from({ length: n + over }, (_, i) =>
    `<i class="${i >= n ? 'over' : (seats[i] ?? 0) >= 2 ? 'dup' : seats[i] === 1 ? 'ok' : ''}"></i>`).join('');
}
```
5. `onsubmit` 안에서
   - `strategies: { oversell: d.oversell }` → `strategies: { oversell: d.oversell, doubleBooking: d.doubleBooking }`
   - `draw(body.seatCount, 0);` → `draw(body.seatCount, 0, []);`
   - 폴링 안 `draw(body.seatCount, s.progress.ok);` → `draw(body.seatCount, s.progress.ok, s.progress.seats);`

- [x] **Step 2: 브라우저 확인**

Run: `docker compose up -d --build web && sleep 15 && open http://localhost:8080`
- `CONDITIONAL_UPDATE` + `NONE` 실행: 그리드에 빨강 칸이 여러 개, 회색 칸도 남고, 배지 FAIL. 리포트 JSON에 `doubleBookedSeats > 0`.
- `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT` 실행: 빨강 없음, 거의 전부 초록, 배지 PASS(또는 DEGRADED), `duplicateKeyCount` 약 900.
- `NONE` + `NONE` 실행: 빨강 다수 + 주황 칸 뒤에 붙음.

- [x] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add doubleBooking radio and red cells for double-booked seats"
```

---

### Task 5: M2 DoD 스크립트와 문서

**Files:**
- Modify: `scripts/dod.sh`
- Modify: `docs/decisions.md`
- Modify: `README.md`
- Modify: `docs/milestones/plans/2026-09-20-m2-double-booking.md` (체크박스)

**Interfaces:**
- Consumes: 전체

- [x] **Step 1: DoD 스크립트 교체**

`scripts/dod.sh` 전체를 아래로 교체한다.

```bash
#!/usr/bin/env bash
# M1 DoD: LOCAL_LOCK은 앱 1대 PASS·2대 FAIL. CONDITIONAL_UPDATE/PESSIMISTIC/OPTIMISTIC은 양쪽 모두 정합. NONE은 양쪽 모두 FAIL.
# M2 DoD: CONDITIONAL_UPDATE + 앱 2대에서 좌석 NONE은 dup>0으로 FAIL, UNIQUE_CONSTRAINT는 dup=0·dupKey>0으로 PASS.
# NONE을 먼저 돌려 같은 파라미터의 DEGRADED 기준선을 만든다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUNS=${RUNS:-3}
M2_RUNS=${M2_RUNS:-10}

run() { # $1 oversell, $2 appInstances, $3 doubleBooking
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":$2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\",\"doubleBooking\":\"$3\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) dup=\(.report.consistency.doubleBookedSeats) dupKey=\(.report.performance.duplicateKeyCount) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms retries=\(.report.performance.retryCount) connPeak=\(.report.performance.dbConnectionPeak)"'
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
```

- [x] **Step 2: 기동과 DoD 실행**

Run: `docker compose down -v && docker compose up -d --build && sleep 25 && ./scripts/dod.sh 2>&1 | tee /tmp/m2-dod.txt`
Expected:
- M1 구간(좌석 NONE): M1 결과와 같은 판정 패턴. `NONE` 양쪽 FAIL, `LOCAL_LOCK` 1대 PASS/DEGRADED·2대 FAIL, 나머지 셋 `oversold=0 ledger=0`. 단 이제 좌석 `NONE`이라 `dup>0`이 섞여 `LOCAL_LOCK` 1대·`CONDITIONAL_UPDATE`·`PESSIMISTIC`·`OPTIMISTIC`이 **FAIL**로 나온다. 이것이 M2의 교훈이므로 정상이다. `oversold`·`ledger`가 0인지로 M1 회귀를 본다.
- `CONDITIONAL_UPDATE apps=2 doubleBooking=NONE`: 10/10 `FAIL oversold=0 ledger=0 dup=<양수> dupKey=0`
- `CONDITIONAL_UPDATE apps=2 doubleBooking=UNIQUE_CONSTRAINT`: 10/10 `PASS`(또는 `DEGRADED`) `dup=0 dupKey=<약 900>`

`dup`이 0으로 나오면 `raceWindowMs`가 실제로 좌석 단계에서 적용되는지(`takeSeat`의 sleep)와 `picks` 범위를 먼저 의심한다. `UNIQUE_CONSTRAINT`에서 `dupKey=0`이면 `SHOW INDEX FROM reservation`으로 인덱스가 실제로 생겼는지 본다.

- [x] **Step 3: decisions.md에 결정 추가**

`docs/decisions.md` 표 끝에 추가한다.

```markdown
| 2026-09-20 | M2 요청 순서는 좌석 단계(reservation INSERT) → 카운터 단계(remaining 차감). 카운터가 OK가 아니면 자기 행 DELETE | 유니크 위반이 카운터 전에 끝나 환불이 없고, oversell·doubleBooking 두 축이 직교한다. 카운터 먼저면 환불이 LOCAL_LOCK의 절대값 쓰기와 경합. |
| 2026-09-20 | `seat` 테이블·`hotspot` 좌석 선택은 M2에서 보류. `reservation(event_id, seat_no, user_id)` 하나 + `Random(seed)` 균등 선택 | 중복 지표는 GROUP BY seat_no로 충분. `seat.status`는 lost update가 생기는 두 번째 장소가 되어 오버셀 실험과 섞임. N=100·M=1,000이면 균등만으로 좌석당 10명. |
| 2026-09-20 | 유니크 인덱스 토글은 web이 실행 시작 시 `TRUNCATE reservation` 후 `information_schema`로 확인해 CREATE/DROP | 이전 NONE 실행의 중복 행이 남으면 인덱스 생성이 실패. 동시 실행 1건이라 DDL 경합 없음. 리포트는 메모리에 있어 행을 지워도 잃는 것이 없다. |
| 2026-09-20 | `reservation`에 보조 인덱스 없음, `created_at` 없음 | 실행당 1,000행 이하라 풀 스캔으로 충분. 쓰는 곳 없는 컬럼은 두지 않는다. |
| 2026-09-20 | 좌석 거절은 `Outcome.SEAT_TAKEN`(앱 조회)과 `DUPLICATE_KEY`(DB 거절) 두 값으로 분리 | 플래그 없이 `duplicateKeyCount = count(DUPLICATE_KEY)` 한 줄. DoD가 요구하는 중복키 카운트가 그대로 나온다. |
| 2026-09-20 | DEGRADED 기준선 키에 `doubleBooking` 포함 | 좌석 NONE 경로가 sleep을 하나 더 하므로 같은 좌석 전략끼리만 비교해야 한다. |
```

- [x] **Step 4: README 갱신**

`README.md`에서:
- `## What works today (M0 + M1)` → `## What works today (M0 + M1 + M2)`. 목록 끝에 추가:
  - `- A \`reservation\` row per confirmed seat. Each user picks a seat from \`Random(seed)\`, holds it first and only then takes a ticket from the counter, so the oversell and double-booking axes never interfere. Two strategies: \`NONE\` (application check, then insert) and \`UNIQUE_CONSTRAINT\` (a unique index on \`(event_id, seat_no)\` that the web tier creates or drops at the start of every run).`
  - `- \`doubleBookedSeats\` counted directly in the DB after the run, \`duplicateKeyCount\` from the app's \`DuplicateKeyException\`s, and red cells in the seat grid.`
- 기존 M1 표 아래에 Step 2 출력에서 뽑은 M2 표를 추가한다. 열: `Oversell | Double booking | Verdict | Oversold | Ledger | Double booked | Dup keys | Throughput (req/s) | p99`. 2행. 값은 실측치 범위. 표 아래에 두 문장: `CONDITIONAL_UPDATE` alone keeps the count right and still fails because seats overlap, and the unique index is the only layer that rejects the second reservation regardless of what the code above it did.
- M1 표는 Step 2의 M1 구간 실측치로 갱신한다(좌석 NONE 경로가 추가되어 수치가 바뀜). 표 위 문장에 `doubleBooking=NONE`을 명시하고, 판정 열은 `dup`으로 인한 FAIL을 그대로 적되 `Oversold`·`Ledger`가 0임을 보이도록 `Ledger` 열을 추가한다.
- 로드맵 M2 항목을 `- [x] M2 Double booking. \`reservation\` table, unique index toggled at runtime`으로 바꾼다.
- `## Documents`에 `- [M2 design](docs/milestones/specs/2026-09-20-m2-design.md) and [M2 implementation plan](docs/milestones/plans/2026-09-20-m2-double-booking.md) (Korean)` 줄을 추가한다.
- `## How it is put together`의 파일 목록 설명 `app/ReservationService.kt one SQL path per strategy`를 `app/ReservationService.kt seat step, then one SQL path per counter strategy`로 바꾼다.
- `## API` 예시 요청 본문의 `"strategies": { "oversell": "NONE" }`을 `"strategies": { "oversell": "NONE", "doubleBooking": "NONE" }`으로 바꾼다.

- [x] **Step 5: Commit**

```bash
git add scripts/dod.sh
git commit -m "chore: extend DoD script with M2 double booking runs"
git add docs/decisions.md
git commit -m "docs: log M2 decisions"
git add README.md
git commit -m "docs: record M2 results and mark roadmap"
```

- [x] **Step 6: 계획 체크박스 갱신 후 커밋**

이 문서의 `- [ ]`를 모두 `- [x]`로 바꾼다.

```bash
git add docs/milestones/plans/2026-09-20-m2-double-booking.md
git commit -m "docs: mark M2 plan as done"
```
