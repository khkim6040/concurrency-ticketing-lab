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
