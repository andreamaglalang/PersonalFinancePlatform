package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import androidx.annotation.MainThread

sealed interface CreationState {
    data object Ready : CreationState
    data object Submitting : CreationState
    data class Settled(val result: CreationResult) : CreationState
    data object SessionEnded : CreationState
}
private class CreationScopeEnded : CancellationException()

/** One in-memory form/session. No automatic retries, persistence, credentials or UI wiring. */
@MainThread
class TransactionCreationCoordinator(
    private val repository: TransactionCreationRepository,
    private val isSessionCurrent: () -> Boolean,
    private val onSessionExpired: () -> Unit = {},
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    private class Attempt(val request: CreateTransactionRequest, var result: CreationResult? = null)
    private val attempts = LinkedHashMap<TransactionDraft, Attempt>()
    private val mutex = Mutex()
    private var closed = false
    private var work: Deferred<CreationResult>? = null
    private val mutable = MutableStateFlow<CreationState>(CreationState.Ready)
    val state = mutable.asStateFlow()
    /** IDs only; callers can warn about unresolved operations without logging payloads. */
    val unresolvedClientIds: List<UUID> get() = attempts.values.filter { it.result is CreationResult.UnknownOutcome }.map { it.request.clientId }

    suspend fun submit(draft: TransactionDraft, acknowledgeDifferentUnresolvedPayload: Boolean = false): CreationResult {
        if (!mutex.tryLock()) return CreationResult.Busy
        try {
            if (closed || !isSessionCurrent()) {
                invalidate()
                return CreationResult.Failure(FinanceFailure.SessionExpired)
            }
            var attempt = attempts[draft]
            if (attempt == null) {
                if (unresolvedClientIds.isNotEmpty() && !acknowledgeDifferentUnresolvedPayload) return CreationResult.UnresolvedPrevious
                if (attempts.size >= 8) return CreationResult.AttemptLimit
                val id = nextId()
                require(attempts.values.none { it.request.clientId == id }) { "Client ID collision" }
                attempt = Attempt(CreateTransactionRequest(id, draft))
                attempts[draft] = attempt
            }
            val selected = attempt
            (selected.result as? CreationResult.Success)?.let { return it } // Repeated completed taps do not send again.
            // C2: a 409 means the server holds different data under this ClientId. Definitive: never resend it.
            selected.result?.takeIf { it == CreationResult.Failure(FinanceFailure.Conflict) }?.let { return it }
            mutable.value = CreationState.Submitting
            val result = try {
                coroutineScope {
                    val task = async(start = CoroutineStart.LAZY) { repository.create(selected.request) }
                    work = task
                    if (closed) task.cancel(CreationScopeEnded())
                    task.start()
                    task.await()
                }
            } catch (_: CreationScopeEnded) {
                currentCoroutineContext().ensureActive()
                CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true)
            } catch (cancelled: CancellationException) {
                if (!closed && isSessionCurrent()) {
                    selected.result = CreationResult.UnknownOutcome(UnknownCreationReason.Interrupted)
                    mutable.value = CreationState.Settled(checkNotNull(selected.result))
                } else invalidate()
                throw cancelled
            } catch (_: Exception) {
                CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure)
            } finally { work = null }
            if (closed || !isSessionCurrent()) {
                invalidate()
                return CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true)
            }
            if (result == CreationResult.Failure(FinanceFailure.SessionExpired) ||
                result == CreationResult.Failure(FinanceFailure.MissingSession)) {
                invalidate()
                onSessionExpired() // Existing auth owner decides the signed-out transition.
                return result
            }
            if (result is CreationResult.UnknownOutcome && result.sessionInvalidated) {
                invalidate()
                return result
            }
            selected.result = result
            mutable.value = CreationState.Settled(result)
            return result
        } finally { mutex.unlock() }
    }
    /** Must be called when the owning account/form scope ends. No server revocation implied. */
    fun invalidate() {
        closed = true
        work?.cancel(CreationScopeEnded())
        attempts.clear()
        mutable.value = CreationState.SessionEnded
    }
}
