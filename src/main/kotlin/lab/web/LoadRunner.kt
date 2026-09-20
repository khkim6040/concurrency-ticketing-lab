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
import java.time.Duration
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
        // 10분 TTL은 실행이 끝난 뒤 키가 쌓이지 않게 한다.
        if (cache == CacheStrategy.REDIS_AS_SOT) redis.opsForValue().set("stock:$eventId", spec.seatCount.toString(), Duration.ofMinutes(10))
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
