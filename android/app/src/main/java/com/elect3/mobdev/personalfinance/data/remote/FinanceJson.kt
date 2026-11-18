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

/** Explicit wire mapping: strict JSON types and decimals never pass through Double. */
internal object FinanceJson {
    fun createdTransaction(body: String, request: CreateTransactionRequest): Transaction {
        val row = root(body)
        val envelope = JsonObject().apply {
            add("items", JsonArray().apply { add(row) })
            addProperty("page", 1); addProperty("pageSize", 20); addProperty("totalCount", 1)
        }
        return transactions(envelope.toString(), TransactionQuery()).items.single().also {
            val draft = request.draft
            require(it.clientId == request.clientId && it.type == draft.type && it.categoryId == draft.categoryId &&
                it.amount.compareTo(draft.amount) == 0 && it.currencyCode == draft.currencyCode &&
                it.transactionDate == draft.transactionDate && it.description == draft.description &&
                it.billId == null && it.billDueDate == null)
        }
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
    private fun JsonObject.createdAtUtc(): Instant {
        val raw = text("createdAtUtc")
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
                if (r.field("billDueDate").isJsonNull) null else r.date("billDueDate")).also {
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
}
