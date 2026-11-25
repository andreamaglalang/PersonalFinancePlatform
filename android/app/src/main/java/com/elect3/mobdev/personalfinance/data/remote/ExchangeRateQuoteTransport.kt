package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuote
import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

internal object ExchangeRateQuoteJson {
    private val code = Regex("[A-Z]{3}")
    private val maximumMoney = BigDecimal("9999999999999999.99")
    private val maximumRate = BigDecimal("9999999999.99999999")

    /**
     * Strict decoding: the quote must echo exactly what was asked, and its numbers must be the API's own
     * (rate ≤ 8 decimals, converted = round(amount × rate, 2, away from zero)). Anything else is rejected.
     */
    fun decode(body: String, from: String, to: String, amount: BigDecimal, requestedDate: LocalDate? = null): ExchangeRateQuote {
        val root = JsonParser.parseString(body).asJsonObject
        fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: error("Invalid response")
        fun JsonObject.number(key: String): BigDecimal = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal ?: error("Invalid response")
        val quote = ExchangeRateQuote(
            from = root.text("from"), to = root.text("to"), amount = root.number("amount"), rate = root.number("rate"),
            rateDate = LocalDate.parse(root.text("rateDate")), requestedDate = LocalDate.parse(root.text("requestedDate")),
            convertedAmount = root.number("convertedAmount"), source = root.text("source"),
            retrievedAt = root.text("retrievedAtUtc").let { raw ->
                runCatching { Instant.parse(raw) }.getOrElse { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }
            },
        )
        require(quote.from == from && quote.to == to && quote.from.matches(code) && quote.to.matches(code))
        require(quote.amount.compareTo(amount) == 0)
        require(requestedDate == null || quote.requestedDate == requestedDate)
        require(quote.rate.signum() > 0 && quote.rate <= maximumRate && quote.rate.stripTrailingZeros().scale() <= 8)
        require(quote.convertedAmount.signum() >= 0 && quote.convertedAmount <= maximumMoney && quote.convertedAmount.stripTrailingZeros().scale() <= 2)
        require(amount.multiply(quote.rate).setScale(2, RoundingMode.HALF_UP).compareTo(quote.convertedAmount) == 0)
        require(quote.rateDate <= quote.requestedDate && quote.source.isNotBlank() && quote.source.length <= 40)
        require(from != to || (quote.rate.compareTo(BigDecimal.ONE) == 0 && quote.convertedAmount.compareTo(amount) == 0))
        return quote.copy(rate = quote.rate.setScale(8, RoundingMode.UNNECESSARY), convertedAmount = quote.convertedAmount.setScale(2, RoundingMode.UNNECESSARY))
    }
}

/** Authenticated read-only quotes over the existing GET transport (session, TLS and status rules unchanged). */
internal class RemoteExchangeRateQuoteRepository(private val transport: AuthenticatedFinanceTransport) : ExchangeRateQuoteRepository, com.elect3.mobdev.personalfinance.domain.currency.HistoricalExchangeRateQuoteRepository {
    override suspend fun quote(from: String,to: String,amount: BigDecimal): FinanceResult<ExchangeRateQuote> = read(from,to,amount,null)
    override suspend fun quote(from: String,to: String,amount: BigDecimal,date: LocalDate): FinanceResult<ExchangeRateQuote> = read(from,to,amount,date)
    private suspend fun read(from: String,to: String,amount: BigDecimal,date: LocalDate?): FinanceResult<ExchangeRateQuote> {
        if(date != null && date.year !in 1..9999) return FinanceResult.Failure(FinanceFailure.InvalidInput)
        if (!from.matches(Regex("[A-Z]{3}")) || !to.matches(Regex("[A-Z]{3}")) || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
            return FinanceResult.Failure(FinanceFailure.InvalidInput)
        val plain = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        return transport.get(FinanceResource.ExchangeRateQuote, queryString("from" to from, "to" to to, "amount" to plain,"date" to date)) {
            ExchangeRateQuoteJson.decode(it, from, to, amount, date)
        }
    }
}
