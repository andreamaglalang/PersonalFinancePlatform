package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.Transaction

/**
 * Authenticated api/transactions/{id} member operations. The server applies the stored-rate rules: an amount-only edit
 * keeps the stored rate; a currency or date change needs a validated rate; bill-payment transactions cannot be edited
 * or deleted (409). Edits and deletes are online only: they are never queued on the device.
 */
interface TransactionUpdateRepository {
    /** PUT with the [version] last read; a stale version is a [FinanceFailure.Conflict] and nothing is changed. */
    suspend fun update(transactionId: Int, draft: TransactionDraft, version: String): FinanceResult<Transaction>
    /** GET the current row (with its current version), e.g. to reload after a conflict. */
    suspend fun get(transactionId: Int): FinanceResult<Transaction>
    /** DELETE a server-confirmed transaction. Already gone (or not owned) is [FinanceFailure.NotFound]. */
    suspend fun delete(transaction: Transaction): FinanceResult<Unit>
}
