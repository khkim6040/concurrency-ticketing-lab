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
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)
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
                    }.getOrElse { ex -> log.warn("reserve failed user={} seat={}", userId, seatNo, ex); ReserveResponse(Outcome.ERROR) }
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
