package io.github.vsaliy.minimetagpt.infrastructure.observability

import io.github.vsaliy.minimetagpt.agents.AgentFailure
import io.github.vsaliy.minimetagpt.domain.AgentRole
import io.github.vsaliy.minimetagpt.domain.WorkflowStage
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Wraps a workflow stage with an OpenTelemetry span (through the Micrometer tracing bridge, when configured),
 * MDC context (workflowId/role/stage) and one structured log line carrying duration, outcome and error category.
 */
class StageObserver(private val tracer: Tracer?) {
    private val log = LoggerFactory.getLogger(StageObserver::class.java)

    suspend fun <T> observe(workflowId: UUID, role: AgentRole?, stage: WorkflowStage, block: suspend () -> T): T {
        val mdc = buildMap {
            put("workflowId", workflowId.toString())
            put("stage", stage.name)
            role?.let { put("agentRole", it.name) }
        }
        val previous = MDC.getCopyOfContextMap().orEmpty()
        return withContext(MDCContext(previous + mdc)) {
            val span = tracer?.nextSpan()?.name("workflow.stage.${stage.name.lowercase()}")?.start()
            span?.tag("workflow.id", workflowId.toString())
            span?.tag("workflow.stage", stage.name)
            role?.let { span?.tag("agent.role", it.name) }
            val started = System.nanoTime()
            var outcome = "SUCCESS"
            var errorCategory = "NONE"
            try {
                block()
            } catch (e: CancellationException) {
                outcome = "CANCELLED"
                throw e
            } catch (e: AgentFailure) {
                outcome = "FAILED"
                errorCategory = e.error.category.name
                span?.error(e)
                throw e
            } catch (e: Exception) {
                outcome = "FAILED"
                errorCategory = "INTERNAL"
                span?.error(e)
                throw e
            } finally {
                val durationMs = (System.nanoTime() - started) / 1_000_000
                span?.tag("outcome", outcome)
                span?.tag("error.category", errorCategory)
                span?.end()
                log.info("stage finished workflowId={} role={} stage={} durationMs={} outcome={} errorCategory={}",
                    workflowId, role?.name ?: "-", stage, durationMs, outcome, errorCategory)
            }
        }
    }
}
