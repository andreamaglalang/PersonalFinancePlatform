package com.elect3.mobdev.personalfinance.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Transaction(
    val transactionId: Int,
    val clientId: UUID,
    val type: TransactionType,
    val categoryId: Int,
    val categoryName: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal,
    val fxRateDate: LocalDate,
    val amountBase: BigDecimal,
    val transactionDate: LocalDate,
    val description: String?,
    val createdAtUtc: Instant,
    val billId: Int?,
    val billDueDate: LocalDate?,
)

enum class TransactionType(val wireValue: String) {
    Income("Income"),
    Expense("Expense");

    companion object {
        fun fromWireValue(value: String): TransactionType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported transaction type: $value")
    }
}
