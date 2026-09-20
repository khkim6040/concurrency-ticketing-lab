package lab

import kotlin.math.ceil
import kotlin.random.Random

enum class OversellStrategy { NONE, CONDITIONAL_UPDATE }

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
data class ReserveResponse(val result: Outcome)

data class Sample(val outcome: Outcome, val latencyMs: Long)

data class ConsistencyMetrics(val oversoldCount: Int, val ledgerMismatch: Int)
data class PerformanceMetrics(val throughput: Double, val p50Ms: Long, val p95Ms: Long, val p99Ms: Long, val errorCount: Int)
enum class Verdict { PASS, FAIL }

data class RunReport(
    val runId: String,
    val spec: RunSpec,
    val consistency: ConsistencyMetrics,
    val performance: PerformanceMetrics,
    val verdict: Verdict,
)

fun buildReport(runId: String, spec: RunSpec, samples: List<Sample>, remaining: Int, elapsedMs: Long): RunReport {
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
    )
    val verdict = if (consistency.oversoldCount == 0 && consistency.ledgerMismatch == 0) Verdict.PASS else Verdict.FAIL
    return RunReport(runId, spec, consistency, performance, verdict)
}
