# M1 전략 확장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `LOCAL_LOCK`/`PESSIMISTIC`/`OPTIMISTIC` 전략, 재시도·커넥션 피크 지표, DEGRADED 판정, 실행 중 칠해지는 좌석 그리드를 추가해 "`LOCAL_LOCK` + 앱 1대 PASS, 2대 FAIL"이 화면에서 보이게 한다.

**Architecture:** M0 구조 그대로. 앱은 `ReservationService`의 `when` 분기를 늘리고 응답에 `retries`·`activeConnections`를 실어 보낸다. web은 `Progress` 카운터를 `RunState`에 넣어 200ms 폴링으로 그리드를 칠하고, 같은 파라미터의 최근 `NONE` 처리량을 메모리에 보관해 DEGRADED를 판정한다.

**Tech Stack:** Kotlin 2.2, JDK 21, Spring Boot 3.5 (web, jdbc, `TransactionTemplate`, HikariCP), MySQL 8.4, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-20-m1-design.md` (결정 근거 `docs/decisions.md`)

## Global Constraints

- JDK 21 toolchain, `spring.threads.virtual.enabled=true`. `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- JPA 금지. DB 접근은 `JdbcClient`만. 새 의존성 없음. 스키마 변경 없음.
- 지연 주입(`Thread.sleep(raceWindowMs)`)은 `NONE`·`LOCAL_LOCK`·`PESSIMISTIC`·`OPTIMISTIC` 모두 읽기와 쓰기 사이. `CONDITIONAL_UPDATE`는 지연 없음.
- `OPTIMISTIC` 재시도 상한 없음.
- 커밋은 `type: English description` 한 줄. 본문·Co-Authored-By 없음.
- 앱은 무상태. 실행 상태·기준선은 web 메모리에만.

## File Structure

| 파일 | 변경 |
| --- | --- |
| `src/main/kotlin/lab/Models.kt` | 전략 5종, `Verdict.DEGRADED`, `Sample`/`ReserveResponse`에 `retries`·`activeConnections`, `PerformanceMetrics`에 `retryCount`·`dbConnectionPeak`, `RunReport.baselineThroughput`, `Progress`, `buildReport` 확장 |
| `src/test/kotlin/lab/ReportTest.kt` | 재시도 합·커넥션 최대·DEGRADED 케이스 |
| `src/main/kotlin/lab/app/ReservationService.kt` | 전략 3종 추가, `ReserveResponse` 반환 |
| `src/main/kotlin/lab/app/ReserveController.kt` | Hikari active 수 샘플링 |
| `src/main/kotlin/lab/web/LoadRunner.kt` | `Progress` 증가, 기준선 맵 |
| `src/main/kotlin/lab/web/RunController.kt` | `RunState.progress` |
| `src/main/resources/static/index.html` | 전략 라디오 5종, 그리드, 판정 배지, 200ms 폴링, LOCAL_LOCK+2대 하이라이트 |
| `scripts/dod.sh` | M1 DoD: 5전략 × 1/2대 × 3회 |
| `README.md` | 로드맵 체크, M1 결과 표 |

---

### Task 1: 모델과 집계 함수 확장 (TDD)

**Files:**
- Modify: `src/main/kotlin/lab/Models.kt`
- Test: `src/test/kotlin/lab/ReportTest.kt`

**Interfaces:**
- Produces:
  - `enum class OversellStrategy { NONE, LOCAL_LOCK, CONDITIONAL_UPDATE, PESSIMISTIC, OPTIMISTIC }`
  - `data class ReserveResponse(val result: Outcome, val retries: Int = 0, val activeConnections: Int = 0)`
  - `data class Sample(val outcome: Outcome, val latencyMs: Long, val retries: Int = 0, val activeConnections: Int = 0)`
  - `data class PerformanceMetrics(throughput, p50Ms, p95Ms, p99Ms, errorCount, retryCount: Int, dbConnectionPeak: Int)`
  - `enum class Verdict { PASS, DEGRADED, FAIL }`
  - `data class RunReport(runId, spec, consistency, performance, verdict, baselineThroughput: Double?)`
  - `data class Progress(val ok: AtomicInteger = AtomicInteger(), val soldOut: AtomicInteger = AtomicInteger(), val error: AtomicInteger = AtomicInteger())`
  - `fun buildReport(runId: String, spec: RunSpec, samples: List<Sample>, remaining: Int, elapsedMs: Long, baselineThroughput: Double? = null): RunReport`

- [x] **Step 1: 실패하는 테스트 추가**

`src/test/kotlin/lab/ReportTest.kt` 클래스 안에 아래 세 테스트를 추가한다. 기존 4개는 그대로 둔다.

```kotlin
    @Test
    fun `sums retries and takes connection peak`() {
        val s = listOf(
            Sample(Outcome.OK, 1, retries = 2, activeConnections = 5),
            Sample(Outcome.SOLD_OUT, 1, retries = 3, activeConnections = 20),
            Sample(Outcome.ERROR, 1),
        )
        val r = buildReport("r", spec, s, remaining = 2, elapsedMs = 100)
        assertEquals(5, r.performance.retryCount)
        assertEquals(20, r.performance.dbConnectionPeak)
    }

    @Test
    fun `degraded when throughput at most half of baseline`() {
        // 5 req / 0.5 s = 10 rps
        val degraded = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500, baselineThroughput = 20.0)
        assertEquals(Verdict.DEGRADED, degraded.verdict)
        assertEquals(20.0, degraded.baselineThroughput)
        val pass = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500, baselineThroughput = 19.0)
        assertEquals(Verdict.PASS, pass.verdict)
        val noBaseline = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500)
        assertEquals(Verdict.PASS, noBaseline.verdict)
        assertEquals(null, noBaseline.baselineThroughput)
    }

    @Test
    fun `fail beats degraded`() {
        val r = buildReport("r", spec, samples(ok = 5, soldOut = 0), remaining = 0, elapsedMs = 1000, baselineThroughput = 100.0)
        assertEquals(Verdict.FAIL, r.verdict)
    }
```

- [x] **Step 2: 실패 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test --tests lab.ReportTest`
Expected: 컴파일 실패 (`retries`, `baselineThroughput` 없음).

- [x] **Step 3: 구현**

`src/main/kotlin/lab/Models.kt` 전체를 아래로 교체한다.

```kotlin
package lab

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.random.Random

enum class OversellStrategy { NONE, LOCAL_LOCK, CONDITIONAL_UPDATE, PESSIMISTIC, OPTIMISTIC }

data class StrategySet(val oversell: OversellStrategy)

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

enum class Outcome { OK, SOLD_OUT, ERROR }

data class ReserveRequest(val eventId: Long, val userId: Long, val strategy: OversellStrategy, val raceWindowMs: Long)
data class ReserveResponse(val result: Outcome, val retries: Int = 0, val activeConnections: Int = 0)

data class Sample(val outcome: Outcome, val latencyMs: Long, val retries: Int = 0, val activeConnections: Int = 0)

// 실행 중 그리드용 카운터. Jackson은 AtomicInteger를 숫자로 직렬화한다.
data class Progress(
    val ok: AtomicInteger = AtomicInteger(),
    val soldOut: AtomicInteger = AtomicInteger(),
    val error: AtomicInteger = AtomicInteger(),
)

data class ConsistencyMetrics(val oversoldCount: Int, val ledgerMismatch: Int)
data class PerformanceMetrics(
    val throughput: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val errorCount: Int,
    val retryCount: Int,
    val dbConnectionPeak: Int,
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
): RunReport {
    val ok = samples.count { it.outcome == Outcome.OK }
    val sorted = samples.map { it.latencyMs }.sorted()
    fun pct(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    val consistency = ConsistencyMetrics(
        oversoldCount = (ok - spec.seatCount).coerceAtLeast(0),
        ledgerMismatch = spec.seatCount - (ok + remaining),
    )
    val performance = PerformanceMetrics(
        throughput = samples.size * 1000.0 / elapsedMs.coerceAtLeast(1),
        p50Ms = pct(0.50), p95Ms = pct(0.95), p99Ms = pct(0.99),
        errorCount = samples.count { it.outcome == Outcome.ERROR },
        retryCount = samples.sumOf { it.retries },
        dbConnectionPeak = samples.maxOfOrNull { it.activeConnections } ?: 0,
    )
    val consistent = consistency.oversoldCount == 0 && consistency.ledgerMismatch == 0
    val verdict = when {
        !consistent -> Verdict.FAIL
        baselineThroughput != null && performance.throughput <= baselineThroughput * 0.5 -> Verdict.DEGRADED
        else -> Verdict.PASS
    }
    return RunReport(runId, spec, consistency, performance, verdict, baselineThroughput)
}
```

- [x] **Step 4: 통과 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test --tests lab.ReportTest`
Expected: 7 tests PASS. (다른 파일은 아직 `ReserveResponse(result)`만 쓰므로 컴파일된다.)

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/lab/Models.kt src/test/kotlin/lab/ReportTest.kt
git commit -m "feat: add M1 strategies, performance fields and DEGRADED verdict to models"
```

---

### Task 2: 전략 3종과 커넥션 샘플링 (app)

**Files:**
- Modify: `src/main/kotlin/lab/app/ReservationService.kt`
- Modify: `src/main/kotlin/lab/app/ReserveController.kt`

**Interfaces:**
- Consumes: Task 1의 `OversellStrategy`, `Outcome`, `ReserveResponse`
- Produces: `ReservationService.reserve(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse` (`activeConnections`는 0, 컨트롤러가 채움). `POST /api/reserve` 응답 `{ result, retries, activeConnections }`.

- [x] **Step 1: 서비스 교체**

`src/main/kotlin/lab/app/ReservationService.kt` 전체를 아래로 교체한다. `TransactionTemplate`은 spring-boot-starter-jdbc가 자동 구성한다.

```kotlin
package lab.app

import lab.Outcome
import lab.OversellStrategy
import lab.ReserveResponse
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
@Profile("app")
class ReservationService(private val jdbc: JdbcClient, private val tx: TransactionTemplate) {
    private val localLock = Any() // ponytail: 전역 락. 실행당 이벤트가 하나라 이벤트별 락과 결과가 같다.

    fun reserve(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse = when (strategy) {
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

- [x] **Step 2: 컨트롤러 교체**

`src/main/kotlin/lab/app/ReserveController.kt` 전체를 아래로 교체한다. `hikariPoolMXBean`은 첫 커넥션 전에는 null이므로 요청마다 읽는다.

```kotlin
package lab.app

import com.zaxxer.hikari.HikariDataSource
import lab.ReserveRequest
import lab.ReserveResponse
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@Profile("app")
class ReserveController(private val service: ReservationService, private val dataSource: DataSource) {
    private fun active() = (dataSource as HikariDataSource).hikariPoolMXBean?.activeConnections ?: 0

    @PostMapping("/api/reserve")
    fun reserve(@RequestBody req: ReserveRequest): ReserveResponse {
        val before = active()
        val res = service.reserve(req.eventId, req.strategy, req.raceWindowMs)
        return res.copy(activeConnections = maxOf(before, active()))
    }
}
```

- [x] **Step 3: 컴파일 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build -x test`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit**

```bash
git add src/main/kotlin/lab/app
git commit -m "feat: add LOCAL_LOCK, PESSIMISTIC, OPTIMISTIC strategies and connection sampling"
```

---

### Task 3: 진행 카운터와 DEGRADED 기준선 (web)

**Files:**
- Modify: `src/main/kotlin/lab/web/LoadRunner.kt`
- Modify: `src/main/kotlin/lab/web/RunController.kt`

**Interfaces:**
- Consumes: Task 1의 `Progress`, `Sample`, `ReserveResponse`, `buildReport(..., baselineThroughput)`; Task 2의 `POST /api/reserve` 응답
- Produces: `LoadRunner.run(runId: String, spec: RunSpec, progress: Progress): RunReport`. `GET /api/runs/{id}` → `{ status, progress: { ok, soldOut, error }, report?, error? }`.

- [x] **Step 1: LoadRunner 교체**

`src/main/kotlin/lab/web/LoadRunner.kt` 전체를 아래로 교체한다.

```kotlin
package lab.web

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

@Service
@Profile("web")
class LoadRunner(
    private val jdbc: JdbcClient,
    @Value("\${lab.app-urls}") private val appUrls: List<String>,
) {
    private val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory()).build()
    // DEGRADED 기준선: 같은 (N, M, appInstances, raceWindowMs)의 최근 NONE 처리량. ponytail: 메모리 보관.
    private val baselines = ConcurrentHashMap<List<Any>, Double>()

    fun run(runId: String, spec: RunSpec, progress: Progress): RunReport {
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO event (run_id, total, remaining) VALUES (:run, :n, :n)")
            .param("run", runId).param("n", spec.seatCount).update(keys)
        val eventId = keys.key!!.toLong()
        val targets = appUrls.take(spec.appInstances)
        val gate = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val futures = (1..spec.userCount).map { userId ->
                pool.submit<Sample> {
                    gate.await()
                    val t0 = System.nanoTime()
                    val res = runCatching {
                        client.post().uri("${targets[userId % targets.size]}/api/reserve")
                            .body(ReserveRequest(eventId, userId.toLong(), spec.strategies.oversell, spec.raceWindowMs))
                            .retrieve().body(ReserveResponse::class.java)!!
                    }.getOrElse { ReserveResponse(Outcome.ERROR) }
                    when (res.result) {
                        Outcome.OK -> progress.ok
                        Outcome.SOLD_OUT -> progress.soldOut
                        Outcome.ERROR -> progress.error
                    }.incrementAndGet()
                    Sample(res.result, (System.nanoTime() - t0) / 1_000_000, res.retries, res.activeConnections)
                }
            }
            val start = System.nanoTime()
            gate.countDown()
            val samples = futures.map { it.get() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
                .param("id", eventId).query(Int::class.javaObjectType).single()

            val key = listOf(spec.seatCount, spec.userCount, spec.appInstances, spec.raceWindowMs)
            val isBaseline = spec.strategies.oversell == OversellStrategy.NONE
            val report = buildReport(runId, spec, samples, remaining, elapsedMs, if (isBaseline) null else baselines[key])
            if (isBaseline) baselines[key] = report.performance.throughput
            return report
        }
    }
}
```

- [x] **Step 2: RunController 수정**

`src/main/kotlin/lab/web/RunController.kt`에서 `RunState`와 `start`를 아래처럼 바꾼다.

```kotlin
data class RunState(val status: String, val progress: Progress, val report: RunReport? = null, val error: String? = null)
```

`start` 본문:

```kotlin
        val runId = UUID.randomUUID().toString()
        val progress = Progress()
        runs[runId] = RunState("RUNNING", progress)
        Thread.startVirtualThread {
            try {
                runs[runId] = RunState("DONE", progress, report = runner.run(runId, spec, progress))
            } catch (e: Exception) {
                runs[runId] = RunState("ERROR", progress, error = e.toString())
            } finally {
                running.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("runId" to runId))
```

import에 `lab.Progress` 추가.

- [x] **Step 3: 컴파일·테스트 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build`
Expected: BUILD SUCCESSFUL, 7 tests PASS.

- [x] **Step 4: Commit**

```bash
git add src/main/kotlin/lab/web
git commit -m "feat: expose run progress counters and NONE baseline for DEGRADED verdict"
```

---

### Task 4: 좌석 그리드와 판정 배지 UI

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: Task 3의 `GET /api/runs/{id}` 응답 (`progress.ok`, `report.verdict`, `report.spec.seatCount`)

- [x] **Step 1: 페이지 교체**

`src/main/resources/static/index.html` 전체를 아래로 교체한다.

```html
<!doctype html>
<meta charset="utf-8">
<title>concurrency-ticketing-lab</title>
<style>
body{font:14px system-ui;max-width:720px;margin:2rem auto}
label{display:block;margin:.4rem 0}
pre{background:#f4f4f4;padding:1rem}
select.hot{outline:3px solid #f80}
#grid{display:grid;grid-template-columns:repeat(auto-fill,12px);gap:2px;margin:1rem 0}
#grid i{display:block;width:12px;height:12px;background:#ccc}
#grid i.ok{background:#3a3}
#grid i.over{background:#f80}
#verdict{font:bold 24px system-ui;padding:.5rem 1rem;display:inline-block;color:#fff;background:#888}
#verdict.PASS{background:#3a3}#verdict.DEGRADED{background:#ea0}#verdict.FAIL{background:#d33}
</style>
<h1>concurrency-ticketing-lab — M1</h1>
<form id="f">
  <label>seatCount <input name="seatCount" type="number" value="100" min="1" max="1000"></label>
  <label>userCount <input name="userCount" type="number" value="1000" min="1" max="10000"></label>
  <label>appInstances <select name="appInstances"><option>1</option><option selected>2</option></select></label>
  <label>raceWindowMs <input name="raceWindowMs" type="number" value="20" min="0" max="200"></label>
  <label>oversell
    <label><input type="radio" name="oversell" value="NONE" checked> NONE</label>
    <label><input type="radio" name="oversell" value="LOCAL_LOCK"> LOCAL_LOCK</label>
    <label><input type="radio" name="oversell" value="CONDITIONAL_UPDATE"> CONDITIONAL_UPDATE</label>
    <label><input type="radio" name="oversell" value="PESSIMISTIC"> PESSIMISTIC</label>
    <label><input type="radio" name="oversell" value="OPTIMISTIC"> OPTIMISTIC</label>
  </label>
  <button>실행</button>
</form>
<div id="grid"></div>
<div id="verdict">대기 중</div>
<pre id="out"></pre>
<script>
const f = document.getElementById('f'), grid = document.getElementById('grid'),
      verdict = document.getElementById('verdict'), out = document.getElementById('out');
const sel = f.querySelector('[name=appInstances]');

// LOCAL_LOCK + 앱 2대: 로컬 락이 무너지는 조합을 강조
f.onchange = () => sel.classList.toggle('hot', f.oversell.value === 'LOCAL_LOCK' && sel.value === '2');

// 앞 min(ok, N)칸 초록, N 이후 초과분은 주황
function draw(n, ok) {
  const over = Math.max(0, ok - n);
  grid.innerHTML = Array.from({ length: n + over }, (_, i) =>
    `<i class="${i >= n ? 'over' : i < ok ? 'ok' : ''}"></i>`).join('');
}

f.onsubmit = async e => {
  e.preventDefault();
  f.querySelector('button').disabled = true;
  const d = Object.fromEntries(new FormData(f));
  const body = { seatCount: +d.seatCount, userCount: +d.userCount, appInstances: +d.appInstances,
                 raceWindowMs: +d.raceWindowMs, strategies: { oversell: d.oversell } };
  draw(body.seatCount, 0);
  verdict.className = ''; verdict.textContent = 'RUNNING'; out.textContent = '';
  const res = await fetch('/api/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const { runId, error } = await res.json();
  if (error) { out.textContent = error; f.querySelector('button').disabled = false; return; }
  const timer = setInterval(async () => {
    const s = await (await fetch('/api/runs/' + runId)).json();
    draw(body.seatCount, s.progress.ok);
    if (s.status === 'RUNNING') return;
    clearInterval(timer);
    f.querySelector('button').disabled = false;
    verdict.className = s.report?.verdict ?? ''; verdict.textContent = s.report?.verdict ?? s.status;
    out.textContent = JSON.stringify(s.report ?? s, null, 2);
  }, 200);
};
</script>
```

- [x] **Step 2: 로컬 확인**

Run: `docker compose up -d --build && sleep 25 && open http://localhost:8080`
브라우저에서 `LOCAL_LOCK` 선택 후 appInstances=2이면 select에 주황 테두리, 실행하면 그리드가 초록으로 차오르고 초과분이 주황으로 붙으며 배지가 `FAIL`. appInstances=1이면 배지 `PASS`.

- [x] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add seat grid, verdict badge and M1 strategy radios"
```

---

### Task 5: M1 DoD 스크립트와 문서

**Files:**
- Modify: `scripts/dod.sh`
- Modify: `README.md`

**Interfaces:**
- Consumes: 전체

- [x] **Step 1: DoD 스크립트 교체**

`scripts/dod.sh` 전체를 아래로 교체한다.

```bash
#!/usr/bin/env bash
# M1 DoD: LOCAL_LOCK은 앱 1대 PASS·2대 FAIL. CONDITIONAL_UPDATE/PESSIMISTIC/OPTIMISTIC은 양쪽 모두 정합. NONE은 양쪽 모두 FAIL.
# NONE을 먼저 돌려 같은 파라미터의 DEGRADED 기준선을 만든다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
RUNS=${RUNS:-3}

run() { # $1 strategy, $2 appInstances
  local id
  id=$(curl -sf -X POST "$BASE/api/runs" -H 'Content-Type: application/json' \
    -d "{\"seatCount\":100,\"userCount\":1000,\"appInstances\":$2,\"raceWindowMs\":20,\"strategies\":{\"oversell\":\"$1\"}}" | jq -r .runId)
  while [ "$(curl -s "$BASE/api/runs/$id" | jq -r .status)" = RUNNING ]; do sleep 1; done
  curl -s "$BASE/api/runs/$id" | jq -r '"\(.status) \(.report.verdict) oversold=\(.report.consistency.oversoldCount) ledger=\(.report.consistency.ledgerMismatch) errors=\(.report.performance.errorCount) rps=\(.report.performance.throughput|floor) p99=\(.report.performance.p99Ms)ms retries=\(.report.performance.retryCount) connPeak=\(.report.performance.dbConnectionPeak)"'
}

for s in NONE LOCAL_LOCK CONDITIONAL_UPDATE PESSIMISTIC OPTIMISTIC; do
  for n in 1 2; do
    echo "== $s apps=$n"
    for _ in $(seq "$RUNS"); do run "$s" "$n"; done
  done
done
```

- [x] **Step 2: 기동과 DoD 실행**

Run: `docker compose up -d --build && sleep 25 && ./scripts/dod.sh 2>&1 | tee /tmp/m1-dod.txt`
Expected:
- `NONE apps=1`, `NONE apps=2`: 모두 `FAIL oversold=<양수>`
- `LOCAL_LOCK apps=1`: 모두 `PASS`(또는 `DEGRADED`) `oversold=0 ledger=0`
- `LOCAL_LOCK apps=2`: 모두 `FAIL oversold=<양수>`
- `CONDITIONAL_UPDATE`, `PESSIMISTIC`, `OPTIMISTIC` 양쪽: 모두 `oversold=0 ledger=0 errors=0`, 판정 `PASS` 또는 `DEGRADED`
- `PESSIMISTIC`의 `connPeak`가 20 근처, `OPTIMISTIC`의 `retries`가 수천 이상

재현이 안 되면 `raceWindowMs`, Hikari 풀 크기, `innodb_lock_wait_timeout`(기본 50초)을 먼저 의심한다.

- [x] **Step 3: README 갱신**

`README.md`에서:
- `## What works today (M0)` 제목을 `## What works today (M0 + M1)`으로 바꾸고, 목록에 다음을 추가한다.
  - `- Five oversell strategies: \`NONE\`, \`LOCAL_LOCK\` (JVM \`synchronized\`), \`CONDITIONAL_UPDATE\`, \`PESSIMISTIC\` (\`SELECT ... FOR UPDATE\` inside a transaction) and \`OPTIMISTIC\` (version check with unbounded retry). The delay is injected between the read and the write in every strategy that has one, so a lock held while sleeping shows up as throughput lost.`
  - `- \`retryCount\` and \`dbConnectionPeak\` (HikariCP active connections, sampled per request on the app side), plus a \`DEGRADED\` verdict when a consistent run does at most half the throughput of the most recent \`NONE\` run with the same parameters.`
  - `- A seat grid that fills in while the run is in flight, polled every 200 ms. Green is a sold seat, orange is a seat sold past capacity.`
- 기존 결과 표 아래에 Step 2 출력에서 뽑은 M1 표를 추가한다. 열: `Strategy | Apps | Verdict | Oversold | Throughput (req/s) | p99 | Retries | Conn peak`. 10행(5전략 × 1/2대). 값은 실측치 범위.
- 로드맵의 M1 항목을 `- [x]`로 바꾼다.
- `## Documents`에 `[M1 design](docs/superpowers/specs/2026-09-20-m1-design.md) and [M1 implementation plan](docs/superpowers/plans/2026-09-20-m1-strategies.md) (Korean)` 줄을 추가한다.

- [x] **Step 4: Commit**

```bash
git add scripts/dod.sh
git commit -m "chore: extend DoD script to five strategies and both instance counts"
git add README.md
git commit -m "docs: record M1 results and mark roadmap"
```
