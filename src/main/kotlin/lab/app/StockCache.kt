package lab.app

import lab.CacheStrategy
import lab.StockResponse
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Duration

// 잔여석 캐시. 키 stock:{eventId}. REDIS_AS_SOT에서는 캐시가 아니라 카운터 자체이며 web이 실행 시작 시 N을 심는다.
@Service
@Profile("app")
class StockCache(private val redis: StringRedisTemplate, private val jdbc: JdbcClient) {
    private fun key(eventId: Long) = "stock:$eventId"

    // 조회 경로. cache-aside 셋은 미스 때 DB 읽기 → sleep → SET 순서라 쓰기 쪽 DEL과 엇갈리면 옛값이 재적재된다.
    fun read(eventId: Long, cache: CacheStrategy, raceWindowMs: Long): StockResponse {
        redis.opsForValue().get(key(eventId))?.let { return StockResponse(it.toInt(), fromDb = false) }
        val remaining = jdbc.sql("SELECT remaining FROM event WHERE id = :id")
            .param("id", eventId).query(Int::class.javaObjectType).single()
        if (cache != CacheStrategy.REDIS_AS_SOT) {
            Thread.sleep(raceWindowMs) // 경합 창 확대: DB 읽기와 캐시 적재 사이
            val ttl = if (cache == CacheStrategy.TTL_SHORT) Duration.ofSeconds(1) else Duration.ofSeconds(60)
            redis.opsForValue().set(key(eventId), remaining.toString(), ttl)
        }
        return StockResponse(remaining, fromDb = true)
    }

    // REDIS_AS_SOT 카운터 단계. DECR이 원자라 sleep이 없다. 음수면 되돌리고 매진.
    fun decrement(eventId: Long): Boolean {
        val left = redis.opsForValue().decrement(key(eventId))!!
        if (left >= 0) return true
        restore(eventId)
        return false
    }

    fun restore(eventId: Long) {
        redis.opsForValue().increment(key(eventId))
    }

    fun invalidate(eventId: Long) {
        redis.delete(key(eventId))
    }
}
