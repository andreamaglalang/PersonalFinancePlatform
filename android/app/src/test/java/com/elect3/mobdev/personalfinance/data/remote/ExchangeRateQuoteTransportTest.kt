package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** GET api/exchange-rates/quote over a fake engine: exact request, strict decoding, bounded failures. */
class ExchangeRateQuoteTransportTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private inner class Fixture {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("quote-access", "quote-refresh", expiry, expiry.plusSeconds(1000), AuthUser(7, "Q", "q@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, MutableAuthClock(expiry.minusSeconds(60)))
        val requests = mutableListOf<FinanceHttpRequest>()
        var respond: () -> FinanceHttpResponse = { FinanceHttpResponse(200, body()) }
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { requests += it; respond() })
        suspend fun login() = assertEquals(AuthResult.Success, auth.login("q@example.test", "fixture-only"))
    }
    private fun body(from: String = "USD", to: String = "PHP", amount: String = "10.25", rate: String = "56.12345679",
                     converted: String = "575.27", rateDate: String = "2026-11-19", requested: String = "2026-11-20", source: String = "Frankfurter") =
        """{"from":"$from","to":"$to","amount":$amount,"rate":$rate,"rateDate":"$rateDate","requestedDate":"$requested",""" +
            """"convertedAmount":$converted,"source":"$source","retrievedAtUtc":"2026-11-20T08:52:10.123Z"}"""

    @Test fun sendsAnAuthenticatedReadOnlyGetAndDecodesTheServerQuote() = runTest {
        val f = Fixture(); f.login()
        val q = (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal("10.25")) as FinanceResult.Success).value
        val request = f.requests.single()
        assertEquals("https://localhost:7027/api/exchange-rates/quote?from=USD&to=PHP&amount=10.25", request.url.toString())
        assertEquals("Bearer quote-access", request.authorization())
        assertFalse(request.toString().contains("quote-access"))
        assertEquals(BigDecimal("56.12345679"), q.rate); assertEquals(8, q.rate.scale())
        assertEquals(BigDecimal("575.27"), q.convertedAmount); assertEquals(2, q.convertedAmount.scale())
        assertEquals(LocalDate.parse("2026-11-19"), q.rateDate); assertEquals(LocalDate.parse("2026-11-20"), q.requestedDate)
        assertEquals("Frankfurter", q.source); assertEquals(Instant.parse("2026-11-20T08:52:10.123Z"), q.retrievedAt)
    }

    @Test fun reverseAndSameCurrencyQuotesDecode() = runTest {
        val f = Fixture(); f.login()
        f.respond = { FinanceHttpResponse(200, body("PHP", "USD", "1000.00", "0.01781780", "17.82")) }
        assertEquals(BigDecimal("17.82"), (f.repos.exchangeRateQuotes.quote("PHP", "USD", BigDecimal("1000")) as FinanceResult.Success).value.convertedAmount)
        f.respond = { FinanceHttpResponse(200, body("PHP", "PHP", "99.99", "1", "99.99", "2026-11-20", "2026-11-20", "Same currency")) }
        assertTrue(f.repos.exchangeRateQuotes.quote("PHP", "PHP", BigDecimal("99.99")) is FinanceResult.Success)
    }

    @Test fun aQuoteThatIsNotTheApisOwnConsistentAnswerIsRejected() = runTest {
        val f = Fixture(); f.login()
        for (bad in listOf(body(converted = "575.26"), body(from = "EUR"), body(amount = "10.24"), body(rate = "56.123456789", converted = "575.27"),
            body(rateDate = "2026-11-21"), body(source = ""), body("PHP", "PHP", "10.25", "56.12345679", "575.27"), "{}", "not json")) {
            f.respond = { FinanceHttpResponse(200, bad) }
            val r = f.repos.exchangeRateQuotes.quote(if (bad.contains("\"from\":\"PHP\"")) "PHP" else "USD",
                if (bad.contains("\"to\":\"PHP\"")) "PHP" else "PHP", BigDecimal("10.25"))
            assertEquals(bad, FinanceFailure.InvalidResponse, (r as FinanceResult.Failure).reason)
        }
    }

    @Test fun failuresAreBoundedAndConnectivityIsDistinguishedFromServerErrors() = runTest {
        val f = Fixture()
        assertEquals(FinanceFailure.MissingSession, (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE) as FinanceResult.Failure).reason)
        assertTrue(f.requests.isEmpty())
        f.login()
        assertEquals(FinanceFailure.InvalidInput, (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal("1.234")) as FinanceResult.Failure).reason)
        assertEquals(FinanceFailure.InvalidInput, (f.repos.exchangeRateQuotes.quote("usd", "PHP", BigDecimal.ONE) as FinanceResult.Failure).reason)
        assertTrue("invalid input never reaches the network", f.requests.isEmpty())
        f.respond = { FinanceHttpResponse(503, "") }
        assertEquals(FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = false), f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE))
        f.respond = { FinanceHttpResponse(400, "") }
        assertEquals(FinanceFailure.InvalidInput, (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE) as FinanceResult.Failure).reason)
        f.respond = { FinanceHttpResponse(404, "") }
        assertEquals(FinanceFailure.NotFound, (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE) as FinanceResult.Failure).reason)
        f.respond = { throw IOException("offline") }
        assertEquals(FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true), f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE))
        f.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceFailure.Timeout, (f.repos.exchangeRateQuotes.quote("USD", "PHP", BigDecimal.ONE) as FinanceResult.Failure).reason)
    }
}
