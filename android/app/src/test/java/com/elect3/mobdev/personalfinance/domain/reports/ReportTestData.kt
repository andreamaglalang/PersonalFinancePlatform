package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Transactions shaped exactly like validated API rows: amountBase = round(amount × fxRate, 2). */
internal object ReportTestData {
    fun row(
        id: Int,
        type: TransactionType,
        amount: String,
        date: LocalDate,
        currency: String = "PHP",
        rate: String = "1",
        categoryId: Int = 1,
        categoryName: String = "Food",
        rateDate: LocalDate = if (currency == "PHP") date else date.minusDays(1),
        amountBase: BigDecimal? = null,
    ): Transaction {
        val source = BigDecimal(amount)
        val fx = BigDecimal(rate)
        return Transaction(id, UUID.nameUUIDFromBytes("tx-$id".toByteArray()), type, categoryId, categoryName, source, currency, fx,
            if (currency == "PHP") date else rateDate, amountBase ?: source.multiply(fx).setScale(2, RoundingMode.HALF_UP), date,
            null, Instant.parse("2026-01-01T00:00:00Z"), null, null)
    }

    fun expense(id: Int, amount: String, date: LocalDate, categoryId: Int = 1, categoryName: String = "Food") =
        row(id, TransactionType.Expense, amount, date, categoryId = categoryId, categoryName = categoryName)

    fun income(id: Int, amount: String, date: LocalDate) =
        row(id, TransactionType.Income, amount, date, categoryId = 50, categoryName = "Salary")
}
