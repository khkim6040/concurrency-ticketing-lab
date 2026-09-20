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
