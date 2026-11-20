package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** Canonical immutable payload; equality ignores insignificant decimal scale. */
class TransactionDraft(
    val type: TransactionType, val categoryId: Int, amount: BigDecimal,
    val currencyCode: String, val transactionDate: LocalDate, val description: String? = null,
) {
    val amount: BigDecimal = amount.also {
        require(it.signum() > 0 && it <= BigDecimal("9999999999999999.99") && it.stripTrailingZeros().scale() <= 2) { "Invalid amount" }
    }.setScale(2, RoundingMode.UNNECESSARY)
    init {
        require(categoryId > 0) { "Invalid category" }
        require(this.amount.signum() > 0 && this.amount <= BigDecimal("9999999999999999.99")) { "Invalid amount" }
        require(currencyCode.matches(Regex("[A-Z]{3}"))) { "Invalid currency" }
        require(transactionDate.year in 1..9999) { "Invalid date" }
        require(description == null || description.length <= 200) { "Invalid description" }
    }
    override fun equals(other: Any?) = other is TransactionDraft && type == other.type && categoryId == other.categoryId &&
        amount == other.amount && currencyCode == other.currencyCode && transactionDate == other.transactionDate && description == other.description
    override fun hashCode() = listOf(type, categoryId, amount, currencyCode, transactionDate, description).hashCode()
    override fun toString() = "TransactionDraft([redacted])"
}
class CreateTransactionRequest(val clientId: UUID, val draft: TransactionDraft) {
    init { require(clientId != UUID(0, 0)) { "Nonempty client ID required" } }
    override fun toString() = "CreateTransactionRequest([redacted])"
}
enum class UnknownCreationReason { Timeout, Interrupted, TransportFailure, ServerFailure, InvalidResponse, SessionChanged }
sealed interface CreationResult {
    class Success(val transaction: Transaction, val replayed: Boolean) : CreationResult {
        override fun toString() = "CreationResult.Success([redacted])"
    }
    data class Failure(val reason: FinanceFailure) : CreationResult
    data class UnknownOutcome(val reason: UnknownCreationReason, val sessionInvalidated: Boolean = false) : CreationResult
    data object Busy : CreationResult
    data object UnresolvedPrevious : CreationResult
    data object AttemptLimit : CreationResult
    /** Kept on this device (server unreachable); will be sent later with the same ClientId. Not yet a server record. */
    data class Queued(val clientId: java.util.UUID) : CreationResult
}
fun interface TransactionCreationRepository {
    suspend fun create(request: CreateTransactionRequest): CreationResult
}
