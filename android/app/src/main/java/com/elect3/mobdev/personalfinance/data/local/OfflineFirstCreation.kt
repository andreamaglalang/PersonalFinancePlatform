package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.repository.CreateTransactionRequest
import com.elect3.mobdev.personalfinance.domain.repository.CreationResult
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionCreationRepository
import com.elect3.mobdev.personalfinance.domain.repository.UnknownCreationReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** True when the server could not be reached (or did not answer): safe to queue with the same ClientId. */
internal fun CreationResult.isConnectivityProblem(): Boolean = when (this) {
    is CreationResult.Failure -> reason == FinanceFailure.Unavailable || reason == FinanceFailure.Timeout || reason == FinanceFailure.SecureConnection
    is CreationResult.UnknownOutcome -> !sessionInvalidated && reason in setOf(UnknownCreationReason.Timeout, UnknownCreationReason.TransportFailure, UnknownCreationReason.Interrupted)
    else -> false
}

/**
 * Offline-first decorator for authenticated transaction creation. Confirmed server rows are written to the local
 * ledger. When the server cannot be reached, the request is kept on this device with its ClientId and reported as
 * [CreationResult.Queued]; [PendingTransactionSync] sends it later with the same ClientId, and the API's per-user
 * ClientId idempotency guarantees one server record even if the first attempt actually arrived.
 */
class OfflineFirstCreationRepository(
    private val remote: TransactionCreationRepository,
    private val ledger: LocalLedger,
    private val accountId: () -> Int?,
    private val categoryName: suspend (Int) -> String? = { null },
) : TransactionCreationRepository {
    override suspend fun create(request: CreateTransactionRequest): CreationResult {
        val result = remote.create(request)
        val account = accountId() ?: return result
        return try {
            when {
                result is CreationResult.Success -> { ledger.recordSynced(account, listOf(result.transaction)); result }
                result.isConnectivityProblem() -> { ledger.enqueue(account, request, categoryName(request.draft.categoryId)); CreationResult.Queued(request.clientId) }
                else -> result
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { result } // a local storage problem never hides the server result
    }
}

sealed interface SyncOutcome {
    data class Done(val sent: Int, val rejected: Int, val remaining: Int) : SyncOutcome
    data object SessionEnded : SyncOutcome
    data object Offline : SyncOutcome
}

/**
 * Sends this account's pending transactions, oldest first, one at a time (single-flight). Each send reuses the
 * stored ClientId, so a retry or a request that already arrived produces no duplicate. Stops at the first
 * connectivity problem; a server rejection (invalid data, inactive category, conflict) is kept as Rejected for the
 * user to review, never silently dropped.
 */
/**
 * Delay before automatic retry [attempt] (0-based) while entries are still pending: 10 s doubling to a 5-minute cap.
 * An API-only outage raises no network event, so this bounded backoff is what recovers it without "Sync now".
 */
fun autoSyncDelayMillis(attempt: Int): Long = (10_000L shl attempt.coerceIn(0, 5)).coerceAtMost(300_000L)

class PendingTransactionSync(private val remote: TransactionCreationRepository, private val ledger: LocalLedger) {
    private val mutex = Mutex()

    suspend fun sync(accountId: Int, isSessionCurrent: () -> Boolean): SyncOutcome = mutex.withLock {
        var sent = 0; var rejected = 0
        val queue = ledger.pending(accountId)
        for ((index, item) in queue.withIndex()) {
            if (!isSessionCurrent()) return SyncOutcome.SessionEnded
            val result = remote.create(CreateTransactionRequest(item.clientId, item.draft))
            when {
                result is CreationResult.Success -> { ledger.recordSynced(accountId, listOf(result.transaction)); sent++ }
                result is CreationResult.Failure && (result.reason == FinanceFailure.SessionExpired || result.reason == FinanceFailure.MissingSession) ->
                    return SyncOutcome.SessionEnded
                result is CreationResult.UnknownOutcome && result.sessionInvalidated -> return SyncOutcome.SessionEnded
                result.isConnectivityProblem() || result is CreationResult.UnknownOutcome ->
                    return if (sent == 0 && rejected == 0) SyncOutcome.Offline else SyncOutcome.Done(sent, rejected, queue.size - index)
                result is CreationResult.Failure -> { ledger.markRejected(accountId, item.clientId); rejected++ }
                else -> return SyncOutcome.Done(sent, rejected, queue.size - index)
            }
        }
        SyncOutcome.Done(sent, rejected, 0)
    }
}
