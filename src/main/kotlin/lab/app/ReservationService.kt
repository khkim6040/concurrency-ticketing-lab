package lab.app

import lab.DoubleBookingStrategy
import lab.Outcome
import lab.OversellStrategy
import lab.ReserveRequest
import lab.ReserveResponse
import org.springframework.context.annotation.Profile
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
@Profile("app")
class ReservationService(private val jdbc: JdbcClient, private val tx: TransactionTemplate) {
    private val localLock = Any() // ponytail: 전역 락. 실행당 이벤트가 하나라 이벤트별 락과 결과가 같다.

    // 좌석 먼저, 카운터 나중. 좌석을 잡은 요청만 카운터를 차감하고, 카운터가 OK가 아니면 자기 행을 되돌린다.
    fun reserve(req: ReserveRequest): ReserveResponse {
        takeSeat(req)?.let { return ReserveResponse(it) }
        val res = counter(req.eventId, req.strategy, req.raceWindowMs)
        if (res.result != Outcome.OK) {
            jdbc.sql("DELETE FROM reservation WHERE event_id = :e AND seat_no = :s AND user_id = :u")
                .param("e", req.eventId).param("s", req.seatNo).param("u", req.userId).update()
        }
        return res
    }

    // 좌석 단계. 잡았으면 null, 거절이면 그 Outcome. 락도 트랜잭션도 없다.
    private fun takeSeat(req: ReserveRequest): Outcome? {
        val insert = jdbc.sql("INSERT INTO reservation (event_id, seat_no, user_id) VALUES (:e, :s, :u)")
            .param("e", req.eventId).param("s", req.seatNo).param("u", req.userId)
        return when (req.doubleBooking) {
            DoubleBookingStrategy.NONE -> {
                val taken = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE event_id = :e AND seat_no = :s")
                    .param("e", req.eventId).param("s", req.seatNo).query(Long::class.javaObjectType).single() > 0
                if (taken) return Outcome.SEAT_TAKEN
                Thread.sleep(req.raceWindowMs) // 경합 창 확대: 조회와 INSERT 사이
                insert.update()
                null
            }
            DoubleBookingStrategy.UNIQUE_CONSTRAINT -> try {
                insert.update()
                null
            } catch (e: DuplicateKeyException) {
                Outcome.DUPLICATE_KEY
            }
        }
    }

    private fun counter(eventId: Long, strategy: OversellStrategy, raceWindowMs: Long): ReserveResponse = when (strategy) {
        OversellStrategy.NONE -> readSleepWrite(eventId, raceWindowMs)
        OversellStrategy.LOCAL_LOCK -> synchronized(localLock) { readSleepWrite(eventId, raceWindowMs) }
        OversellStrategy.CONDITIONAL_UPDATE -> {
            val updated = jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id AND remaining > 0")
                .param("id", eventId).update()
            ReserveResponse(if (updated == 1) Outcome.OK else Outcome.SOLD_OUT)
        }
        OversellStrategy.PESSIMISTIC -> tx.execute {
            // FOR UPDATE가 행 락을 잡고, 트랜잭션이 끝날 때까지 커넥션을 점유한다.
            val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id FOR UPDATE")
                .param("id", eventId).query(Int::class.javaObjectType).single()
            Thread.sleep(raceWindowMs)
            if (remaining <= 0) ReserveResponse(Outcome.SOLD_OUT)
            else {
                jdbc.sql("UPDATE event SET remaining = remaining - 1 WHERE id = :id").param("id", eventId).update()
                ReserveResponse(Outcome.OK)
            }
        }!!
        OversellStrategy.OPTIMISTIC -> optimistic(eventId, raceWindowMs)
    }

    // 트랜잭션 없음: autocommit 두 문장. sleep 중 커넥션을 반납해야 lost update가 관측된다.
    private fun readSleepWrite(eventId: Long, raceWindowMs: Long): ReserveResponse {
        val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
            .param("id", eventId).query(Int::class.javaObjectType).single()
        Thread.sleep(raceWindowMs) // 경합 창 확대
        if (remaining <= 0) return ReserveResponse(Outcome.SOLD_OUT)
        jdbc.sql("UPDATE event SET remaining = :r WHERE id = :id")
            .param("r", remaining - 1).param("id", eventId).update()
        return ReserveResponse(Outcome.OK)
    }

    // 상한 없음: 버전당 정확히 한 요청이 이기므로 진행이 보장된다.
    private fun optimistic(eventId: Long, raceWindowMs: Long): ReserveResponse {
        var retries = 0
        while (true) {
            val (remaining, version) = jdbc.sql("SELECT remaining, version FROM event WHERE id = :id")
                .param("id", eventId).query { rs, _ -> rs.getInt(1) to rs.getLong(2) }.single()
            if (remaining <= 0) return ReserveResponse(Outcome.SOLD_OUT, retries)
            Thread.sleep(raceWindowMs)
            val updated = jdbc.sql("UPDATE event SET remaining = remaining - 1, version = version + 1 WHERE id = :id AND version = :v")
                .param("id", eventId).param("v", version).update()
            if (updated == 1) return ReserveResponse(Outcome.OK, retries)
            retries++
        }
    }
}
