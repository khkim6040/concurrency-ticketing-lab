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
    // 풀은 첫 커넥션에서 늦게 만들어지므로 MXBean을 요청마다 읽는다.
    private fun active() = (dataSource as HikariDataSource).hikariPoolMXBean?.activeConnections ?: 0

    @PostMapping("/api/reserve")
    fun reserve(@RequestBody req: ReserveRequest): ReserveResponse {
        val before = active()
        val res = service.reserve(req.eventId, req.strategy, req.raceWindowMs)
        return res.copy(activeConnections = maxOf(before, active()))
    }
}
