package lab.app

import lab.ReserveRequest
import lab.ReserveResponse
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("app")
class ReserveController(private val service: ReservationService) {
    @PostMapping("/api/reserve")
    fun reserve(@RequestBody req: ReserveRequest) =
        ReserveResponse(service.reserve(req.eventId, req.strategy, req.raceWindowMs))
}
