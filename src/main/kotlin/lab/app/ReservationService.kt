package lab.app

import lab.Outcome
import lab.OversellStrategy
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

@Service
@Profile("app")
class ReservationService(private val jdbc: JdbcClient) {

    // 트랜잭션 없음: autocommit 두 문장. sleep 중 커넥션을 반납해야 lost update가 관측된다.
    fun reserve(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): Outcome = when (strategy) {
        OversellStrategy.NONE -> {
            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
                .param("id", eventId).query(Int::class.javaObjectType).single()
            Thread.sleep(raceWindowMs) // 경합 창 확대
            if (remaining <= 0) Outcome.SOLD_OUT
            else {
                jdbc.sql("UPDATE event SET remaining = :r WHERE id = :id")
                    .param("r", remaining - 1).param("id", eventId).update()
                Outcome.OK
            }
        }
        OversellStrategy.CONDITIONAL_UPDATE -> {
            val updated = jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id AND remaining > 0")
                .param("id", eventId).update()
            if (updated == 1) Outcome.OK else Outcome.SOLD_OUT
        }
    }
}
