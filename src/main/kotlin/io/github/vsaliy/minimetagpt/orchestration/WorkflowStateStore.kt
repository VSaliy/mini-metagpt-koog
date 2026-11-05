package io.github.vsaliy.minimetagpt.orchestration

import io.github.vsaliy.minimetagpt.domain.WorkflowExecution
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes read-modify-write updates per workflow. Terminal workflows are immutable, so a late engine
 * update can never overwrite a CANCELLED (or otherwise finished) state.
 */
class WorkflowStateStore(private val repository: WorkflowRepository) {
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun create(execution: WorkflowExecution) = repository.save(execution)

    fun get(id: UUID): WorkflowExecution? = repository.find(id)

    fun update(id: UUID, change: (WorkflowExecution) -> WorkflowExecution): WorkflowExecution? =
        synchronized(locks.computeIfAbsent(id) { Any() }) {
            val current = repository.find(id) ?: return null
            if (current.status.terminal) return current
            change(current).also(repository::save)
        }
}
