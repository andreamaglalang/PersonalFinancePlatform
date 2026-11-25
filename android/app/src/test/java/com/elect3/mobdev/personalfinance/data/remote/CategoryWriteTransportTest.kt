package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.google.gson.JsonParser
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Category writes use only the existing member endpoints and fields; everything runs in memory. */
class CategoryWriteTransportTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private inner class Fixture(config: ApiConfiguration = ApiConfiguration.https("https://localhost:7027/")) {
        val clock = MutableAuthClock(expiry.minusSeconds(60))
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", expiry, expiry.plusSeconds(1000),
                    AuthUser(42, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, clock)
        val sent = mutableListOf<FinanceWriteRequest>()
        var respond: (FinanceWriteRequest) -> FinanceHttpResponse = { FinanceHttpResponse(201, echo(it.body!!, 77)) }
        val repos = FinanceRepositories(config, auth, FinanceHttpEngine { error("no GET expected") },
            writeEngine = FinanceWriteEngine { sent += it; respond(it) })
        suspend fun login() = assertEquals(AuthResult.Success, auth.login("offline@example.test", "fixture-only"))
    }
    private fun echo(body: String, id: Int) = JsonParser.parseString(body).asJsonObject.apply {
        addProperty("categoryId", id); addProperty("isSystemDefault", false)
    }.toString()
    private val draft = CategoryDraft("  Groceries ", TransactionType.Expense, "#2f8a5b")

    @Test fun createPostsExactlyTheApiFieldsAndNoIconOrOrder() = runTest {
        val f = Fixture(); f.login()
        val created = (f.repos.categoryWrites.create(draft) as FinanceResult.Success).value
        val request = f.sent.single()
        assertEquals(WriteMethod.POST, request.method)
        assertEquals("/api/categories", request.url.path)
        assertEquals("Bearer offline-access", request.authorization())
        val json = JsonParser.parseString(request.body).asJsonObject
        assertEquals(setOf("name", "type", "colorHEX", "isActive"), json.keySet())
        assertEquals("Groceries", json["name"].asString); assertEquals("Expense", json["type"].asString)
        assertEquals("#2F8A5B", json["colorHEX"].asString); assertTrue(json["isActive"].asBoolean)
        assertEquals(Category(77, "Groceries", TransactionType.Expense, "#2F8A5B", true, false), created)
        assertFalse(request.toString().contains("offline-access"))
        assertEquals(42, f.repos.accountId())
    }

    @Test fun updateUsesPutOnTheMemberRouteAndDeleteAcceptsNoContent() = runTest {
        val f = Fixture(); f.login()
        f.respond = { FinanceHttpResponse(200, echo(it.body!!, 9)) }
        val inactive = draft.copy(isActive = false)
        assertTrue(f.repos.categoryWrites.update(9, inactive) is FinanceResult.Success)
        assertEquals(WriteMethod.PUT, f.sent.last().method); assertEquals("/api/categories/9", f.sent.last().url.path)
        assertFalse(JsonParser.parseString(f.sent.last().body).asJsonObject["isActive"].asBoolean)
        f.respond = { FinanceHttpResponse(204, "") }
        assertTrue(f.repos.categoryWrites.delete(9) is FinanceResult.Success)
        assertEquals(WriteMethod.DELETE, f.sent.last().method); assertNull(f.sent.last().body)
        assertFalse(f.sent.any { it.url.path.contains("defaults") })
    }

    @Test fun statusesMapToBoundedFailures() = runTest {
        mapOf(409 to FinanceFailure.Conflict, 403 to FinanceFailure.AccessDenied, 404 to FinanceFailure.NotFound,
            400 to FinanceFailure.InvalidInput, 401 to FinanceFailure.SessionExpired, 503 to FinanceFailure.Unavailable).forEach { (status, failure) ->
            val f = Fixture(); f.login() // Each status is an independent session.
            f.respond = { FinanceHttpResponse(status, "private details") }
            assertEquals(FinanceResult.Failure(failure), f.repos.categoryWrites.delete(5))
        }
        val f = Fixture(); f.login()
        f.respond = { throw SocketTimeoutException() }
        assertEquals(FinanceResult.Failure(FinanceFailure.Timeout), f.repos.categoryWrites.create(draft))
        // A success body that does not echo the request is not trusted.
        f.respond = { FinanceHttpResponse(201, echo(it.body!!, 3).replace("Groceries", "Other")) }
        assertEquals(FinanceResult.Failure(FinanceFailure.InvalidResponse), f.repos.categoryWrites.create(draft))
    }

    @Test fun noSessionOrNoEndpointSendsNothing() = runTest {
        val f = Fixture()
        assertEquals(FinanceResult.Failure(FinanceFailure.MissingSession), f.repos.categoryWrites.create(draft))
        assertNull(f.repos.accountId())
        val g = Fixture(ApiConfiguration.unconfigured()); g.login()
        assertEquals(FinanceResult.Failure(FinanceFailure.NotConfigured), g.repos.categoryWrites.delete(1))
        assertTrue(f.sent.isEmpty() && g.sent.isEmpty())
    }

    @Test fun draftRejectsInvalidNamesAndColours() {
        assertThrows(IllegalArgumentException::class.java) { CategoryDraft("   ", TransactionType.Expense, null) }
        assertThrows(IllegalArgumentException::class.java) { CategoryDraft("x".repeat(51), TransactionType.Expense, null) }
        assertThrows(IllegalArgumentException::class.java) { CategoryDraft("Food", TransactionType.Expense, "red") }
        assertEquals(50, CategoryDraft(" " + "x".repeat(50) + " ", TransactionType.Income, null).trimmedName.length)
    }
}
