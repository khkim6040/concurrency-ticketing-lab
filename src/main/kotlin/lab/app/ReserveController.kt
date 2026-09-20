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
