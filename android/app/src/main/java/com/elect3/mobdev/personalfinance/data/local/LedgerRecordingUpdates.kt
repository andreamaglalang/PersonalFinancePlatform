package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import com.elect3.mobdev.personalfinance.domain.repository.TransactionUpdateRepository
import kotlinx.coroutines.CancellationException

/**
 * Keeps this device's copy of a transaction in step with the server: a confirmed edit or reload replaces the row (same
 * account, same ClientId, never duplicated); a confirmed delete, or a 404 meaning it is already gone, removes it.
 * Nothing is queued here, and a local storage failure never hides the server's result.
 */
class LedgerRecordingUpdateRepository(
    private val remote: TransactionUpdateRepository,
    private val ledger: LocalLedger,
    private val accountId: () -> Int?,
) : TransactionUpdateRepository {
    override suspend fun update(transactionId: Int, draft: TransactionDraft, version: String): FinanceResult<Transaction> =
        remote.update(transactionId, draft, version).also { r -> if (r is FinanceResult.Success) local { account -> recordSynced(account, listOf(r.value)) } }

    override suspend fun get(transactionId: Int): FinanceResult<Transaction> =
        remote.get(transactionId).also { r -> if (r is FinanceResult.Success) local { account -> recordSynced(account, listOf(r.value)) } }

    override suspend fun delete(transaction: Transaction): FinanceResult<Unit> = remote.delete(transaction).also { r ->
        if (r is FinanceResult.Success || (r as? FinanceResult.Failure)?.reason == FinanceFailure.NotFound) local { account -> forget(account, transaction.clientId) }
    }

    private suspend fun local(block: suspend LocalLedger.(Int) -> Unit) {
        val account = accountId() ?: return
        try { ledger.block(account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Cache failure must not hide a confirmed server result. */ }
    }
}
