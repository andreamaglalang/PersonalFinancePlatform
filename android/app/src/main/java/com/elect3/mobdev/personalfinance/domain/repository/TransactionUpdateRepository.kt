package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.Transaction

/**
 * Authenticated PUT api/transactions/{id}. The server applies the stored-rate rules: an amount-only edit keeps the
 * stored rate; a currency or date change needs a validated rate; bill-payment transactions cannot be edited (409).
 * Edits are online only: they are never queued on the device.
 */
fun interface TransactionUpdateRepository {
    suspend fun update(transactionId: Int, draft: TransactionDraft): FinanceResult<Transaction>
}
