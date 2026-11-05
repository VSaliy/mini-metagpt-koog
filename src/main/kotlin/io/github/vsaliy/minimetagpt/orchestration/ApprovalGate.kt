package io.github.vsaliy.minimetagpt.orchestration

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Human-in-the-loop gate: a workflow suspends here until a reviewer approves or rejects. */
class ApprovalGate {
    private val pending = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()

    /** Registers the request; call [await] afterwards. Registering first avoids losing an early decision. */
    fun open(workflowId: UUID): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { pending[workflowId] = it }

    suspend fun await(workflowId: UUID, deferred: CompletableDeferred<Boolean>): Boolean =
        try {
            deferred.await()
        } finally {
            pending.remove(workflowId, deferred)
        }

    /** Returns false when the workflow is not waiting for a decision. */
    fun decide(workflowId: UUID, approved: Boolean): Boolean = pending[workflowId]?.complete(approved) ?: false
}
