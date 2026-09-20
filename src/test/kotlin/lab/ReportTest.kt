package lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportTest {
    private val spec = RunSpec(seatCount = 3, userCount = 5, strategies = StrategySet(OversellStrategy.NONE))

    private fun samples(ok: Int, soldOut: Int, error: Int = 0) =
        List(ok) { Sample(Outcome.OK, 10L * (it + 1)) } +
            List(soldOut) { Sample(Outcome.SOLD_OUT, 5) } +
            List(error) { Sample(Outcome.ERROR, 1) }

    @Test
    fun `oversold when ok exceeds seats`() {
        val r = buildReport("r", spec, samples(ok = 5, soldOut = 0), remaining = 0, elapsedMs = 1000)
        assertEquals(2, r.consistency.oversoldCount)
        assertEquals(3 - (5 + 0), r.consistency.ledgerMismatch)
        assertEquals(Verdict.FAIL, r.verdict)
    }

    @Test
    fun `pass when ledger balances`() {
        val r = buildReport("r", spec, samples(ok = 3, soldOut = 2), remaining = 0, elapsedMs = 500)
        assertEquals(0, r.consistency.oversoldCount)
        assertEquals(0, r.consistency.ledgerMismatch)
        assertEquals(Verdict.PASS, r.verdict)
        assertEquals(10.0, r.performance.throughput) // 5 req / 0.5 s
    }

    @Test
    fun `percentiles use nearest rank`() {
        val r = buildReport("r", spec, samples(ok = 3, soldOut = 0, error = 1), remaining = 0, elapsedMs = 100)
        // latencies sorted: 1, 10, 20, 30
        assertEquals(10, r.performance.p50Ms)
        assertEquals(30, r.performance.p95Ms)
        assertEquals(30, r.performance.p99Ms)
        assertEquals(1, r.performance.errorCount)
    }

    @Test
    fun `spec rejects out of range`() {
        assertFailsWith<IllegalArgumentException> {
            RunSpec(seatCount = 0, userCount = 1, strategies = StrategySet(OversellStrategy.NONE))
        }
    }

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
}
