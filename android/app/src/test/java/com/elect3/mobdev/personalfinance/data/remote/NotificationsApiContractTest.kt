package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.NotificationKind
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET api/notifications` parsing and C3 mark-read requests over the real transport with in-memory engines. */
class NotificationsApiContractTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private inner class Fixture {
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("inbox-access", "inbox-refresh", expiry, expiry.plusSeconds(1000),
                    AuthUser(5, "Inbox", "inbox@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }, MutableAuthClock(expiry.minusSeconds(60)))
        val gets = mutableListOf<FinanceHttpRequest>()
        val writes = mutableListOf<FinanceWriteRequest>()
        var get: (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, page()) }
        var write: (FinanceWriteRequest) -> FinanceHttpResponse = { FinanceHttpResponse(204, "") }
        val repos = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { gets += it; get(it) }, writeEngine = FinanceWriteEngine { writes += it; write(it) })
        suspend fun login() = assertEquals(AuthResult.Success, auth.login("inbox@example.test", "fixture-only"))
    }

    @Test fun listSendsTheFilterAndParsesNewestFirst() = runTest {
        val f = Fixture(); f.login()
        f.get = { FinanceHttpResponse(200, page(row(9, "BudgetAlert", false, "2026-11-26T10:00:00"), row(8, "Surprise", true, "2026-11-25T10:00:00Z"))) }
        val result = (f.repos.notifications.list(false, 1, 20) as FinanceResult.Success).value
        assertEquals("/api/notifications", f.gets.single().url.path)
        assertEquals("unreadOnly=false&page=1&pageSize=20", f.gets.single().url.query)
        assertEquals(listOf(NotificationKind.BudgetAlert, NotificationKind.Other), result.items.map { it.kind })
        assertEquals(Instant.parse("2026-11-26T10:00:00Z"), result.items.first().createdAtUtc) // no zone means UTC
        assertEquals(2L, result.totalCount)
        assertEquals(NotificationKind.BillReminder, NotificationKind.fromWire("BillReminder"))
        assertEquals(NotificationKind.MonthlySummary, NotificationKind.fromWire("MonthlySummary"))
        assertEquals(NotificationKind.Other, NotificationKind.fromWire("Other"))
        for (wire in listOf("GoalMilestone", "GoalCompleted", "BillOverdue", "BillPaymentRecorded", "PasswordChanged"))
            assertEquals(wire, NotificationKind.fromWire(wire).name)
    }

    @Test fun inconsistentPagesAreRejected() = runTest {
        val f = Fixture(); f.login()
        for ((unreadOnly, body) in listOf(
            true to page(row(1, "BillReminder", true, "2026-11-26T10:00:00Z")),                          // read item under unread-only
            false to page(row(1, "BillReminder", false, "2026-11-25T10:00:00Z"), row(2, "BillReminder", false, "2026-11-26T10:00:00Z")), // oldest first
            false to page(row(1, "BillReminder", false, "2026-11-26T10:00:00Z"), row(1, "BillReminder", false, "2026-11-26T10:00:00Z")), // duplicate
            false to page(row(1, "BillReminder", false, "2026-11-26T10:00:00Z").replace("\"Message 1\"", "\" \"")),
            false to page(row(1, "BillReminder", false, "2026-11-26T10:00:00Z").replace("\"isRead\":false", "\"isRead\":\"no\"")),
            false to """{"items":[],"page":2,"pageSize":20,"totalCount":0}""",
        )) {
            f.get = { FinanceHttpResponse(200, body) }
            assertEquals(body, FinanceResult.Failure(FinanceFailure.InvalidResponse), f.repos.notifications.list(unreadOnly, 1, 20))
        }
    }

    @Test fun markReadPostsWithoutABodyAndAcceptsOnlyNoContent() = runTest {
        val f = Fixture(); f.login()
        assertTrue(f.repos.notifications.markRead(42) is FinanceResult.Success)
        val one = f.writes.single()
        assertEquals(WriteMethod.POST, one.method)
        assertEquals("/api/notifications/42/read", one.url.path)
        assertNull(one.body); assertNull(one.ifMatch)
        assertEquals("Bearer inbox-access", one.authorization())
        assertFalse(one.toString().contains("inbox-access"))
        assertTrue(f.repos.notifications.markAllRead() is FinanceResult.Success)
        assertEquals("/api/notifications/read-all", f.writes.last().url.path)
        assertEquals(WriteMethod.POST, f.writes.last().method); assertNull(f.writes.last().body)
        f.write = { FinanceHttpResponse(200, "{}") } // C3 promises 204; anything else is not a confirmation
        assertEquals(FinanceResult.Failure(FinanceFailure.InvalidResponse), f.repos.notifications.markRead(42))
        assertEquals(FinanceResult.Failure(FinanceFailure.InvalidInput), f.repos.notifications.markRead(0))
    }

    @Test fun markReadFailuresMapToBoundedReasons() = runTest {
        mapOf(404 to FinanceFailure.NotFound, 401 to FinanceFailure.SessionExpired, 503 to FinanceFailure.Unavailable).forEach { (status, failure) ->
            val f = Fixture(); f.login()
            f.write = { FinanceHttpResponse(status, "private details") }
            assertEquals(FinanceResult.Failure(failure), f.repos.notifications.markRead(7))
        }
        val f = Fixture(); f.login()
        f.write = { throw IOException("offline") }
        assertEquals(FinanceResult.Failure(FinanceFailure.Unavailable), f.repos.notifications.markAllRead())
    }

    @Test fun noSessionSendsNothing() = runTest {
        val f = Fixture()
        assertTrue(f.repos.notifications.markRead(3) is FinanceResult.Failure)
        assertTrue(f.repos.notifications.list(false, 1, 20) is FinanceResult.Failure)
        assertTrue(f.writes.isEmpty() && f.gets.isEmpty())
    }

    private fun row(id: Int, type: String, read: Boolean, created: String) = """{"notificationId":$id,"type":"$type",
        "referenceType":null,"referenceId":null,"message":"Message $id","isRead":$read,"createdAtUtc":"$created"}"""
    private fun page(vararg rows: String) = """{"items":[${rows.joinToString(",")}],"page":1,"pageSize":20,"totalCount":${rows.size}}"""
}
