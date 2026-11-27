package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Contract C1 transaction concurrency token: uppercase SHA-256 hex. */
internal val TRANSACTION_VERSION = Regex("[0-9A-F]{64}")

/** Explicit wire mapping: strict JSON types and decimals never pass through Double. */
internal object FinanceJson {
    fun createdTransaction(body: String, request: CreateTransactionRequest): Transaction = single(body).also {
        val draft = request.draft
        require(it.clientId == request.clientId && it.type == draft.type && it.categoryId == draft.categoryId &&
            it.amount.compareTo(draft.amount) == 0 && it.currencyCode == draft.currencyCode &&
            it.transactionDate == draft.transactionDate && it.description == draft.description &&
            it.billId == null && it.billDueDate == null)
    }
    /** One TransactionResponse, validated with the same rules as a list row. */
    private fun single(body: String): Transaction {
        val envelope = JsonObject().apply {
            add("items", JsonArray().apply { add(root(body)) })
            addProperty("page", 1); addProperty("pageSize", 20); addProperty("totalCount", 1)
        }
        return transactions(envelope.toString(), TransactionQuery()).items.single()
    }
    /** GET api/transactions/{id}: must be the requested row and carry the version an edit needs. */
    fun transaction(body: String, transactionId: Int): Transaction = single(body).also {
        require(it.transactionId == transactionId && it.version != null)
    }
    /** DELETE api/transactions/{id} answer: {transactionId, deleted: true, affectedBudgets}. */
    fun deletedTransaction(body: String, transactionId: Int) {
        val row = root(body)
        require(row.int("transactionId") == transactionId && row.bool("deleted"))
    }
    private fun root(body: String): JsonObject {
        val reader = JsonReader(StringReader(body)).apply { strictness = Strictness.STRICT }
        val value = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return value.asJsonObject
    }
    private fun JsonObject.field(key: String): JsonElement = get(key) ?: error("Invalid response")
    private fun JsonObject.text(key: String): String {
        val value = field(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun JsonObject.number(key: String): BigDecimal {
        val value = field(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal
    }
    private fun JsonObject.int(key: String) = number(key).intValueExact()
    private fun JsonObject.bool(key: String): Boolean {
        val value = field(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
    private fun JsonObject.nullableText(key: String): String? = if (field(key).isJsonNull) null else text(key)
    private fun JsonObject.date(key: String): LocalDate {
        val raw = text(key)
        require(raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        return LocalDate.parse(raw).also { require(it.year in 1..9999) }
    }
    private fun JsonObject.money(key: String): BigDecimal = number(key).also {
        require(it.signum() >= 0 && it <= BigDecimal("9999999999999999.99"))
        it.setScale(2, java.math.RoundingMode.UNNECESSARY)
    }
    private fun JsonObject.createdAtUtc(): Instant = utc("createdAtUtc")
    private fun JsonObject.utc(key: String): Instant {
        val raw = text(key)
        // SQL datetime2 does not retain DateTime.Kind. The backend writes UTC and names
        // this field CreatedAtUtc, but SQL-loaded DateTimes can serialize without a Z.
        // Interpret that explicit contract as UTC, never the Android device time zone.
        return if (raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?"))) {
            LocalDateTime.parse(raw).also { require(it.year in 1..9999) }.toInstant(ZoneOffset.UTC)
        } else Instant.parse(raw)
    }
    private class Envelope(val rows: JsonArray, val total: Long)
    private fun envelope(root: JsonObject, page: Int, size: Int): Envelope {
        require(root.int("page") == page && root.int("pageSize") == size)
        val total = root.number("totalCount").longValueExact()
        val items = root.field("items").asJsonArray
        require(total >= 0 && items.size() <= size && items.size().toLong() <= total)
        require(items.size().toLong() <= (total - (page - 1L) * size).coerceAtLeast(0))
        return Envelope(items, total)
    }
    /** Single CategoryResponse from a member POST/PUT; must echo the submitted draft. */
    fun category(body: String, draft: CategoryDraft, expectedId: Int? = null): Category {
        val row = root(body)
        return Category(row.int("categoryId"), row.text("name"), TransactionType.fromWireValue(row.text("type")),
            row.nullableText("colorHEX"), row.bool("isActive"), row.bool("isSystemDefault")).also {
            require(it.categoryId > 0 && (expectedId == null || it.categoryId == expectedId))
            require(it.name == draft.trimmedName && it.type == draft.type && !it.isSystemDefault && it.isActive == draft.isActive)
            require(it.colorHex.equals(draft.colorHex, ignoreCase = true))
        }
    }
    /** One page of api/budgets for [month]; every row must belong to that month. */
    fun budgetPage(body: String, page: Int, size: Int, month: java.time.YearMonth): Pair<List<Budget>, Long> {
        val e = envelope(root(body), page, size)
        val items = e.rows.map { it.asJsonObject.toBudget() }
        require(items.all { it.periodMonth == month })
        require(items.map { it.budgetId }.distinct().size == items.size)
        return items to e.total
    }
    /** Single BudgetResponse from POST/PUT. */
    fun budget(body: String): Budget = root(body).toBudget()
    private fun JsonObject.toBudget(): Budget {
        val month = date("periodMonth").also { require(it.dayOfMonth == 1) }
        val limit = money("limitAmount").also { require(it.signum() > 0) }
        val spent = money("spentAmount")
        val remaining = number("remainingAmount")
        // The server defines remaining as limit minus spent; anything else is not trusted.
        require(remaining.compareTo(limit.subtract(spent)) == 0)
        val alert = int("alertThresholdPct").also { require(it in 1..100) }
        val status = BudgetStatus.entries.firstOrNull { it.name == text("status") } ?: error("Invalid status")
        val currency = text("baseCurrency").also { require(it.matches(Regex("[A-Z]{3}"))) }
        return Budget(int("budgetId").also { require(it > 0) }, int("categoryId").also { require(it > 0) },
            text("categoryName").also { require(it.isNotBlank()) }, bool("isCategoryActive"), java.time.YearMonth.from(month),
            limit, alert, spent, remaining, status, currency)
    }
    fun categories(body: String, query: CategoryQuery): CategoryPage {
        val e = envelope(root(body), query.page, query.pageSize)
        val items = e.rows.map { item ->
            val row = item.asJsonObject
            Category(row.int("categoryId"), row.text("name"), TransactionType.fromWireValue(row.text("type")),
                row.nullableText("colorHEX"), row.bool("isActive"), row.bool("isSystemDefault")).also {
                require(it.categoryId > 0 && it.name.isNotBlank() && it.name.length <= 50)
                require(it.colorHex == null || it.colorHex.matches(Regex("#[0-9A-Fa-f]{6}")))
                require(query.includeInactive || it.isActive)
                require(query.type == null || query.type == it.type)
            }
        }
        require(items.map { it.categoryId }.distinct().size == items.size)
        return CategoryPage(items, query.page, query.pageSize, e.total)
    }
    /**
     * PUT api/transactions/{id} response: must be the edited row with exactly the submitted values, not bill-linked,
     * and its rate snapshot must be self-consistent under the API's rule (base = round(amount × rate, 2)).
     */
    fun updatedTransaction(body: String, transactionId: Int, draft: TransactionDraft): Transaction = single(body).also {
        require(it.transactionId == transactionId && it.type == draft.type && it.categoryId == draft.categoryId &&
            it.amount.compareTo(draft.amount) == 0 && it.currencyCode == draft.currencyCode &&
            it.transactionDate == draft.transactionDate && it.description == draft.description &&
            it.billId == null && it.billDueDate == null)
        require(it.amount.multiply(it.fxRate).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(it.amountBase) == 0)
        require(it.fxRateDate <= it.transactionDate)
        require(it.version != null) // the next edit must be able to send it
    }
    fun transactions(body: String, query: TransactionQuery): TransactionPage {
        val e = envelope(root(body), query.page, query.pageSize)
        val items = e.rows.map { item ->
            val r = item.asJsonObject
            val client = r.text("clientId")
            require(client.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            Transaction(r.int("transactionId"), UUID.fromString(client), TransactionType.fromWireValue(r.text("type")),
                r.int("categoryId"), r.text("categoryName"), r.money("amount"), r.text("currencyCode"),
                r.number("fxRate"), r.date("fxRateDate"), r.money("amountBase"), r.date("transactionDate"),
                r.nullableText("description"), r.createdAtUtc(),
                if (r.field("billId").isJsonNull) null else r.int("billId"),
                if (r.field("billDueDate").isJsonNull) null else r.date("billDueDate"),
                // Absent only on servers before contract C1; a present value must be well formed.
                version = if (r.has("version")) r.text("version").also { v -> require(v.matches(TRANSACTION_VERSION)) } else null).also {
                require(it.transactionId > 0 && it.categoryId > 0 && it.categoryName.isNotBlank())
                require(it.amount.signum() > 0 && it.currencyCode.matches(Regex("[A-Z]{3}")))
                require(it.fxRate.signum() > 0 && it.fxRate <= BigDecimal("9999999999.99999999"))
                it.fxRate.setScale(8, java.math.RoundingMode.UNNECESSARY)
                require(it.description == null || it.description.length <= 200)
                require((it.billId == null) == (it.billDueDate == null) && (it.billId == null || it.billId > 0))
                require(query.type == null || query.type == it.type)
                require(query.categoryId == null || query.categoryId == it.categoryId)
                require(query.fromDate == null || it.transactionDate >= query.fromDate)
                require(query.toDate == null || it.transactionDate <= query.toDate)
            }
        }
        require(items.map { it.transactionId }.distinct().size == items.size)
        return TransactionPage(items, query.page, query.pageSize, e.total)
    }
    /** C6 `GET api/reports/monthly`: must describe [month] and add up exactly (net, category totals, counts). */
    fun monthlyReport(body: String, month: java.time.YearMonth): com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport {
        val r = root(body)
        require(r.date("month") == month.atDay(1))
        val currency = r.text("baseCurrency").also { require(it.matches(Regex("[A-Z]{3}"))) }
        val income = r.money("totalIncome")
        val expenses = r.money("totalExpenses")
        require(r.number("netCashFlow").compareTo(income.subtract(expenses)) == 0)
        val count = r.int("transactionCount").also { require(it >= 0) }
        val expenseRows = r.categoryTotals("expenseByCategory")
        val incomeRows = r.categoryTotals("incomeByCategory")
        require(expenseRows.fold(BigDecimal.ZERO) { a, c -> a.add(c.total) }.compareTo(expenses) == 0)
        require(incomeRows.fold(BigDecimal.ZERO) { a, c -> a.add(c.total) }.compareTo(income) == 0)
        require((expenseRows + incomeRows).sumOf { it.transactionCount } == count)
        val budgets = r.field("budgets").asJsonArray.map { it.asJsonObject.toBudget() }
        require(budgets.all { it.periodMonth == month && it.baseCurrency == currency })
        require(budgets.map { it.budgetId }.distinct().size == budgets.size)
        return com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport(month, currency, income, expenses, count, expenseRows,
            savingsContributed = r.money("savingsContributed"), budgets = budgets)
    }
    private fun JsonObject.categoryTotals(key: String) = field(key).asJsonArray.map { item ->
        val c = item.asJsonObject
        com.elect3.mobdev.personalfinance.domain.reports.ReportCategoryTotal(c.int("categoryId").also { require(it > 0) },
            c.text("categoryName").also { require(it.isNotBlank()) }, c.money("total"), c.int("transactionCount").also { require(it > 0) })
    }.also { rows -> require(rows.map { it.categoryId }.distinct().size == rows.size) }

    /** C4 `GET api/reports/summaries`: stored snapshots, strictly newest month first. */
    fun summaries(body: String, page: Int, size: Int): List<com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot> {
        val items = envelope(root(body), page, size).rows.map { item ->
            val r = item.asJsonObject
            val month = r.date("summaryMonth").also { require(it.dayOfMonth == 1) }
            val income = r.money("totalIncome")
            val expense = r.money("totalExpense")
            val net = r.number("netCashFlow").also { require(it.compareTo(income.subtract(expense)) == 0) }
            com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot(java.time.YearMonth.from(month), income, expense, net,
                r.money("savingsAllocated"), r.utc("generatedAtUtc"), r.text("baseCurrency").also { require(it.matches(Regex("[A-Z]{3}"))) })
        }
        require(items.zipWithNext().all { (a, b) -> a.month > b.month })
        return items
    }

    /** `GET api/notifications`: owner-scoped, newest first; an unread-only page never contains a read item. */
    fun notifications(body: String, unreadOnly: Boolean, page: Int, size: Int): NotificationPage {
        val e = envelope(root(body), page, size)
        val items = e.rows.map { item ->
            val r = item.asJsonObject
            AppNotification(r.int("notificationId").also { require(it > 0) }, NotificationKind.fromWire(r.text("type")),
                r.text("message").also { require(it.isNotBlank()) }, r.bool("isRead"), r.utc("createdAtUtc"))
        }
        require(!unreadOnly || items.none { it.isRead })
        require(items.map { it.notificationId }.distinct().size == items.size)
        require(items.zipWithNext().all { (a, b) -> a.createdAtUtc >= b.createdAtUtc })
        return NotificationPage(items, page, size, e.total)
    }
}
