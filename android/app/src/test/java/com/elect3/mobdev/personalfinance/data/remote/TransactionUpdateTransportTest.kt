package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** PUT api/transactions/{id} over fake engines only: exact wire fields, response checks, bounded failures. */
class TransactionUpdateTransportTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private val clientId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")

    private inner class Fixture {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("edit-access", "edit-refresh", expiry, expiry.plusSeconds(1000),
                    AuthUser(42, "Editor", "editor@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, MutableAuthClock(expiry.minusSeconds(60)))
        val sent = mutableListOf<FinanceWriteRequest>()
        var rate = BigDecimal("58.12345678")
        var rateDate = "2026-11-14"
        var respond: (FinanceWriteRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, echo(it.body!!, 7)) }
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { error("no GET expected") },
            writeEngine = FinanceWriteEngine { sent += it; respond(it) })
        suspend fun login() = assertEquals(AuthResult.Success, auth.login("editor@example.test", "fixture-only"))

        /** What the API returns: the submitted values plus its own rate snapshot. */
        fun echo(body: String, id: Int, amountBase: BigDecimal? = null) = JsonParser.parseString(body).asJsonObject.apply {
            val amount = get("amount").asBigDecimal
            addProperty("transactionId", id); addProperty("clientId", clientId.toString()); addProperty("categoryName", "Travel")
            addProperty("fxRate", rate); addProperty("fxRateDate", rateDate)
            addProperty("amountBase", amountBase ?: amount.multiply(rate).setScale(2, RoundingMode.HALF_UP))
            addProperty("createdAtUtc", "2026-11-15T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }
    private fun draft(amount: String = "12.50", currency: String = "USD", date: String = "2026-11-15") =
        TransactionDraft(TransactionType.Expense, 2, BigDecimal(amount), currency, LocalDate.parse(date), "Taxi")

    @Test fun putSendsExactlyTheWriteRequestFieldsAndDecodesTheServerSnapshot() = runTest {
        val f = Fixture(); f.login()
        val updated = (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Success).value
        val request = f.sent.single()
        assertEquals(WriteMethod.PUT, request.method)
        assertEquals("/api/transactions/7", request.url.path)
        assertEquals("Bearer edit-access", request.authorization())
        assertFalse(request.toString().contains("edit-access"))
        val json = JsonParser.parseString(request.body).asJsonObject
        // TransactionWriteRequest rejects unknown members, so no clientId (or anything else) is ever sent on an edit.
        assertEquals(setOf("type", "categoryId", "amount", "currencyCode", "transactionDate", "description"), json.keySet())
        assertEquals("USD", json["currencyCode"].asString)
        assertEquals(BigDecimal("12.50"), json["amount"].asBigDecimal)
        assertEquals("2026-11-15", json["transactionDate"].asString)
        assertEquals(BigDecimal("58.12345678"), updated.fxRate)
        assertEquals(LocalDate.parse("2026-11-14"), updated.fxRateDate)
        assertEquals(BigDecimal("726.54"), updated.amountBase)
        assertEquals("USD", updated.currencyCode)
    }

    @Test fun sameCurrencyEditDecodesTheApisRateOneSnapshot() = runTest {
        val f = Fixture(); f.login(); f.rate = BigDecimal("1.00000000"); f.rateDate = "2026-11-15"
        val updated = (f.repos.transactionUpdates.update(7, draft(currency = "PHP")) as FinanceResult.Success).value
        assertEquals(0, updated.fxRate.compareTo(BigDecimal.ONE)); assertEquals(0, updated.amountBase.compareTo(BigDecimal("12.50")))
    }

    @Test fun aResponseThatDoesNotMatchTheEditOrIsInconsistentIsRejected() = runTest {
        val f = Fixture(); f.login()
        f.respond = { FinanceHttpResponse(200, f.echo(it.body!!, 8)) } // a different transaction
        assertEquals(FinanceFailure.InvalidResponse, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        f.respond = { FinanceHttpResponse(200, f.echo(it.body!!, 7, amountBase = BigDecimal("700.00"))) } // base ≠ round(amount × rate)
        assertEquals(FinanceFailure.InvalidResponse, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        f.rateDate = "2026-11-20" // a rate dated after the transaction is not a valid snapshot
        f.respond = { FinanceHttpResponse(200, f.echo(it.body!!, 7)) }
        assertEquals(FinanceFailure.InvalidResponse, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        f.rate = BigDecimal("58.123456789"); f.rateDate = "2026-11-14" // more than the API's 8 rate decimals
        assertEquals(FinanceFailure.InvalidResponse, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
    }

    @Test fun statusesMapToBoundedFailuresAndNothingIsSentWithoutASession() = runTest {
        val f = Fixture()
        assertEquals(FinanceFailure.MissingSession, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        assertTrue(f.sent.isEmpty())
        f.login()
        for ((status, failure) in listOf(400 to FinanceFailure.InvalidInput, 404 to FinanceFailure.NotFound, 409 to FinanceFailure.Conflict)) {
            f.respond = { FinanceHttpResponse(status, "") }
            assertEquals(failure, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        }
        f.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceFailure.Timeout, (f.repos.transactionUpdates.update(7, draft()) as FinanceResult.Failure).reason)
        assertEquals(FinanceFailure.InvalidInput, (f.repos.transactionUpdates.update(0, draft()) as FinanceResult.Failure).reason)
    }

    @Test fun sourceAmountsWithMoreThanTwoDecimalsCannotBeSubmitted() {
        assertThrows(IllegalArgumentException::class.java) { draft(amount = "12.345") }
    }
}
