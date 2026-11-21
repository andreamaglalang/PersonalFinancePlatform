package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import com.elect3.mobdev.personalfinance.domain.repository.TransactionUpdateRepository
import kotlinx.coroutines.CancellationException

/**
 * Keeps this device's copy of a transaction in step with a server-confirmed edit (same account, same ClientId, so
 * the existing row is replaced, never duplicated). Edits are online only: nothing is queued here, and a local storage
 * failure never hides the server's result.
 */
class LedgerRecordingUpdateRepository(
    private val remote: TransactionUpdateRepository,
    private val ledger: LocalLedger,
    private val accountId: () -> Int?,
) : TransactionUpdateRepository {
    override suspend fun update(transactionId: Int, draft: TransactionDraft): FinanceResult<com.elect3.mobdev.personalfinance.domain.model.Transaction> {
        val result = remote.update(transactionId, draft)
        if (result is FinanceResult.Success) {
            val account = accountId() ?: return result
            try { ledger.recordSynced(account, listOf(result.value)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cache failure must not hide a confirmed server edit. */ }
        }
        return result
    }
}
