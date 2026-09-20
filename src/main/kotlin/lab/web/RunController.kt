package lab.web

import lab.RunReport
import lab.RunSpec
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RunState(val status: String, val report: RunReport? = null, val error: String? = null)

@RestController
@Profile("web")
class RunController(private val runner: LoadRunner) {
    private val runs = ConcurrentHashMap<String, RunState>() // ponytail: 메모리 보관, 영속화는 공유 링크(M4)에서
    private val running = AtomicBoolean(false)

    @PostMapping("/api/runs")
    fun start(@RequestBody spec: RunSpec): ResponseEntity<Map<String, String>> {
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "run in progress"))
        }
        val runId = UUID.randomUUID().toString()
        runs[runId] = RunState("RUNNING")
        Thread.startVirtualThread {
            try {
                runs[runId] = RunState("DONE", report = runner.run(runId, spec))
            } catch (e: Exception) {
                runs[runId] = RunState("ERROR", error = e.toString())
            } finally {
                running.set(false)
            }
        }
        return ResponseEntity.accepted().body(mapOf("runId" to runId))
    }

    @GetMapping("/api/runs/{runId}")
    fun get(@PathVariable runId: String): ResponseEntity<RunState> =
        runs[runId]?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
