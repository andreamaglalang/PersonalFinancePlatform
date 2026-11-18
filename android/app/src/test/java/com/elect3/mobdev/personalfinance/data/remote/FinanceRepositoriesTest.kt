package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Every request is handled in memory. No URLConnection or production AuthApiClient is created. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceRepositoriesTest {
    private class FakeAuth : AuthApi {
        var user = 1
        var logoutCalls = 0
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
            AuthPayload("offline-access-$user", "offline-refresh-$user", expiry, expiry.plusSeconds(1000),
                AuthUser(user, "Offline", "offline@example.test", "PHP", listOf("User"))),
        )
        override suspend fun logout(refreshToken: String): ApiResult<Unit> {
            logoutCalls++
            return ApiResult.Success(Unit)
        }
    }
    private class FakeHttp : FinanceHttpEngine {
        val requests = mutableListOf<FinanceHttpRequest>()
        var response: suspend () -> FinanceHttpResponse = { FinanceHttpResponse(200, categories()) }
        override suspend fun get(request: FinanceHttpRequest): FinanceHttpResponse {
            requests += request
            return response()
        }
    }
    private class Fixture(config: ApiConfiguration = ApiConfiguration.https("https://localhost:7027/")) {
        val api = FakeAuth()
        val clock = MutableAuthClock(expiry.minusSeconds(60))
        val auth = InMemoryAuthRepository(api, clock)
        val http = FakeHttp()
        val repos = FinanceRepositories(config, auth, http)
        suspend fun login() { assertEquals(AuthResult.Success, auth.login("offline@example.test", "fixture-only")) }
    }
    private fun failure(expected: FinanceFailure, result: FinanceResult<*>) {
        assertEquals(FinanceResult.Failure(expected), result)
    }
    private fun <T> value(result: FinanceResult<T>): T = (result as FinanceResult.Success).value

    @Test fun currencyDisplayIsNonSecretSessionBoundAndRequiresNoHttp() = runTest {
        val f = Fixture()
        assertNull(f.repos.baseCurrency())
        f.login()
        assertEquals("PHP", f.repos.baseCurrency())
        f.clock.now = expiry
        assertNull(f.repos.baseCurrency())
        assertTrue(f.http.requests.isEmpty())
    }

    @Test fun defaultCategoryRequestAndBearerAreExact() = runTest {
        val f = Fixture(); f.login()
        val page = value(f.repos.categories.list())
        val request = f.http.requests.single()
        assertEquals("https://localhost:7027/api/categories?includeInactive=false&page=1&pageSize=50", request.url.toString())
        assertEquals("Bearer offline-access-1", request.authorization())
        assertFalse(request.toString().contains("offline-access"))
        assertEquals(7, page.items.single().categoryId)
        assertEquals("#A0b1C2", page.items.single().colorHex)
        assertTrue(page.items.single().isSystemDefault)
    }
    @Test fun categoryTypeAndPaginationAreSerializedAndMapped() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { FinanceHttpResponse(200, categories(page = 2, size = 1, total = 3)) }
        val page = value(f.repos.categories.list(CategoryQuery(TransactionType.Income, true, 2, 1)))
        assertEquals("type=Income&includeInactive=true&page=2&pageSize=1", f.http.requests.single().url.query)
        assertEquals(2, page.page); assertEquals(1, page.pageSize); assertEquals(3L, page.totalCount)
    }
    @Test fun transactionFiltersAndSortAreSerializedExactly() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { FinanceHttpResponse(200, transactions(page = 2, size = 5)) }
        val query = TransactionQuery(2, 5, TransactionType.Expense, 7, LocalDate.parse("2026-11-01"),
            LocalDate.parse("2026-11-30"), TransactionSort.Amount, SortDirection.Ascending)
        val page = value(f.repos.transactions.list(query))
        assertEquals("/api/transactions", f.http.requests.single().url.path)
        assertEquals("page=2&pageSize=5&type=Expense&categoryId=7&fromDate=2026-11-01&toDate=2026-11-30&sortBy=amount&sortDirection=asc", f.http.requests.single().url.query)
        val row = page.items.single()
        assertEquals(BigDecimal("90071992547409.91"), row.amount)
        assertEquals(BigDecimal("1.12345678"), row.fxRate)
        assertEquals(BigDecimal("12.34"), row.amountBase)
        assertEquals("USD", row.currencyCode)
        assertEquals(LocalDate.parse("2026-11-15"), row.transactionDate)
        assertEquals(Instant.parse("2026-11-15T01:02:03.1234567Z"), row.createdAtUtc)
        assertNull(row.billId); assertNull(row.billDueDate); assertNull(row.description)
    }
    @Test fun existingReadPageAdapterKeepsPaginationAndTypedSessionFailure() = runTest {
        val f = Fixture()
        assertEquals(TransactionPageResult.Failure(TransactionFailure.SessionExpired), f.repos.transactions.readPage(1, 20))
        f.login(); f.http.response = { FinanceHttpResponse(200, transactions()) }
        val result = f.repos.transactions.readPage(1, 20) as TransactionPageResult.Success
        assertEquals(1, result.page.items.size)
        assertEquals("page=1&pageSize=20&sortBy=date&sortDirection=desc", f.http.requests.single().url.query)
    }
    @Test fun sqlLoadedUtcTimestampWithoutZoneUsesUtcRatherThanDeviceZone() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { FinanceHttpResponse(200, transactions().replace("01:02:03.1234567Z", "01:02:03.1234567")) }
        assertEquals(Instant.parse("2026-11-15T01:02:03.1234567Z"),
            value(f.repos.transactions.list()).items.single().createdAtUtc)
    }
    @Test fun missingSessionNeverCallsTransport() = runTest {
        val f = Fixture()
        failure(FinanceFailure.MissingSession, f.repos.categories.list())
        assertTrue(f.http.requests.isEmpty())
    }
    @Test fun expiredSessionNeverCallsTransport() = runTest {
        for (now in listOf(expiry, expiry.plusSeconds(1))) {
            val f = Fixture(); f.login(); f.clock.now = now
            failure(FinanceFailure.SessionExpired, f.repos.categories.list())
            assertTrue(f.http.requests.isEmpty()); assertFalse(f.auth.hasActiveSession())
        }
    }
    @Test fun sessionJustBeforeExpiryCanRead() = runTest {
        val f = Fixture(); f.login(); f.clock.now = expiry.minusNanos(1)
        assertTrue(f.repos.categories.list() is FinanceResult.Success)
    }
    @Test fun unconfiguredEndpointNeverCallsTransport() = runTest {
        val f = Fixture(ApiConfiguration.unconfigured()); f.login()
        failure(FinanceFailure.NotConfigured, f.repos.categories.list())
        assertTrue(f.http.requests.isEmpty())
    }
    @Test fun httpErrorsAreBoundedAndNotRetriedOrLogged() = runTest {
        for ((status, reason) in listOf(400 to FinanceFailure.InvalidInput, 401 to FinanceFailure.SessionExpired,
            403 to FinanceFailure.AccessDenied, 404 to FinanceFailure.NotFound, 409 to FinanceFailure.Conflict,
            429 to FinanceFailure.RateLimited, 500 to FinanceFailure.Unavailable, 503 to FinanceFailure.Unavailable,
            302 to FinanceFailure.InvalidResponse, 204 to FinanceFailure.InvalidResponse)) {
            val f = Fixture(); f.login()
            f.http.response = { FinanceHttpResponse(status, "private-password-token-error-body") }
            val result = f.repos.categories.list()
            failure(reason, result)
            assertFalse(result.toString().contains("private"))
            assertEquals(1, f.http.requests.size)
            // Returning a typed 401 does not silently change authentication ownership/policies.
            assertTrue(f.auth.hasActiveSession())
        }
    }
    @Test fun exceptionsAreSanitizedAndCancellationIsNotConvertedToError() = runTest {
        for ((exception, expected) in listOf(SocketTimeoutException("private") to FinanceFailure.Timeout,
            SSLException("private") to FinanceFailure.SecureConnection,
            IOException("private") to FinanceFailure.Unavailable,
            IllegalStateException("private") to FinanceFailure.InvalidResponse)) {
            val f = Fixture(); f.login(); f.http.response = { throw exception }
            val result = f.repos.categories.list()
            failure(expected, result); assertFalse(result.toString().contains("private"))
        }
        val f = Fixture(); f.login()
        f.http.response = { throw CancellationException("fixture cancellation") }
        try { f.repos.categories.list(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
    @Test fun malformedJsonAndIncorrectWireTypesAreRejected() = runTest {
        for (body in listOf("{", "null", "[]", categories() + "{}", categories().replace("\"page\":1", "\"page\":\"1\""),
            categories().replace("\"isActive\":true", "\"isActive\":1"),
            categories().replace("\"Income\"", "\"Other\""), categories().replace("\"items\":[", "\"items\":[null,"),
            categories().replace("\"name\":\"Salary\"", "\"name\":null"))) {
            val f = Fixture(); f.login(); f.http.response = { FinanceHttpResponse(200, body) }
            failure(FinanceFailure.InvalidResponse, f.repos.categories.list())
        }
    }
    @Test fun invalidMetadataAndDuplicateRowsAreRejected() = runTest {
        for (body in listOf(categories(page = 2), categories(total = -1), categories(size = 101),
            categories().replace("\"totalCount\":1", "\"totalCount\":1.2"),
            categories(total = 2).replace("[$category]", "[$category,$category]"))) {
            val f = Fixture(); f.login(); f.http.response = { FinanceHttpResponse(200, body) }
            failure(FinanceFailure.InvalidResponse, f.repos.categories.list())
        }
    }
    @Test fun categoryFilterMismatchIsRejectedAndInactiveIsExplicit() = runTest {
        val f = Fixture(); f.login()
        failure(FinanceFailure.InvalidResponse, f.repos.categories.list(CategoryQuery(type = TransactionType.Expense)))
        f.http.response = { FinanceHttpResponse(200, categories().replace("\"isActive\":true", "\"isActive\":false")) }
        failure(FinanceFailure.InvalidResponse, f.repos.categories.list())
        assertFalse(value(f.repos.categories.list(CategoryQuery(includeInactive = true))).items.single().isActive)
    }
    @Test fun malformedTransactionDataIsRejectedWithoutLeakingPayload() = runTest {
        for (body in listOf(transactions().replace("90071992547409.91", "1.001"),
            transactions().replace("90071992547409.91", "\"12.34\""), transactions().replace("2026-11-15\"", "2026-02-30\""),
            transactions().replace("\"USD\"", "\"usd\""), transactions().replace("1.12345678", "0"),
            transactions().replace("\"billId\":null", "\"billId\":2"),
            transactions().replace("5bdb163e-8c0a-4fb9-ade4-e45d94368bfc", "1-1-1-1-1"))) {
            val f = Fixture(); f.login(); f.http.response = { FinanceHttpResponse(200, body) }
            val result = f.repos.transactions.list()
            failure(FinanceFailure.InvalidResponse, result)
            assertFalse(result.toString().contains("900719"))
        }
    }
    @Test fun emptyPagesAndNullableFieldsAreSupported() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { FinanceHttpResponse(200, "{\"items\":[],\"page\":1,\"pageSize\":50,\"totalCount\":0}") }
        assertTrue(value(f.repos.categories.list()).items.isEmpty())
        f.http.response = { FinanceHttpResponse(200, transactions().replace("\"description\":null", "\"description\":\"Offline sample\"")
            .replace("\"billId\":null", "\"billId\":2").replace("\"billDueDate\":null", "\"billDueDate\":\"2026-11-14\"")) }
        val row = value(f.repos.transactions.list()).items.single()
        assertEquals(2, row.billId); assertEquals("Offline sample", row.description)
    }
    @Test fun invalidQueriesFailBeforeTransport() {
        for (action in listOf<() -> Unit>({ CategoryQuery(page = 0) }, { CategoryQuery(pageSize = 101) },
            { TransactionQuery(page = Int.MAX_VALUE) }, { TransactionQuery(categoryId = 0) },
            { TransactionQuery(fromDate = LocalDate.of(0, 1, 1)) },
            { TransactionQuery(fromDate = LocalDate.parse("2026-11-16"), toDate = LocalDate.parse("2026-11-15")) })) {
            try { action(); fail("Invalid query accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun clearSessionCancelsPendingReadImmediately() = runTest {
        val f = Fixture(); f.login()
        var cancelled = false
        f.http.response = { try { awaitCancellation() } finally { cancelled = true } }
        val read = async { f.repos.categories.list() }; runCurrent()
        f.auth.clearSession(); runCurrent()
        failure(FinanceFailure.SessionExpired, read.await()); assertTrue(cancelled)
    }
    @Test fun callerCancellationCancelsTransportAndPreservesSession() = runTest {
        val f = Fixture(); f.login()
        var cancelled = false
        f.http.response = { try { awaitCancellation() } finally { cancelled = true } }
        val read = async { f.repos.categories.list() }; runCurrent()
        read.cancelAndJoin()
        assertTrue(cancelled); assertTrue(f.auth.hasActiveSession())
    }
    @Test fun logoutRejectsEvenNonCooperativeLateSuccess() = runTest {
        val f = Fixture(); f.login()
        val reply = CompletableDeferred<FinanceHttpResponse>()
        f.http.response = { withContext(NonCancellable) { reply.await() } }
        val read = async { f.repos.categories.list() }; runCurrent()
        assertEquals(AuthResult.Success, f.auth.logout())
        reply.complete(FinanceHttpResponse(200, categories()))
        failure(FinanceFailure.SessionExpired, read.await())
        assertFalse(f.auth.hasActiveSession()); assertEquals(1, f.api.logoutCalls)
    }
    @Test fun accountSwitchRejectsOldResponseWithoutClearingNewSession() = runTest {
        val f = Fixture(); f.login()
        val reply = CompletableDeferred<FinanceHttpResponse>()
        f.http.response = { withContext(NonCancellable) { reply.await() } }
        val read = async { f.repos.transactions.list() }; runCurrent()
        f.api.user = 2; f.login()
        reply.complete(FinanceHttpResponse(401, "private"))
        failure(FinanceFailure.SessionExpired, read.await())
        assertTrue(f.auth.hasActiveSession())
        f.http.response = { FinanceHttpResponse(200, categories()) }
        assertTrue(f.repos.categories.list() is FinanceResult.Success)
        assertEquals("Bearer offline-access-2", f.http.requests.last().authorization())
    }
    @Test fun expiryDuringReadRejectsResponseAndDoesNotRevokeServerSession() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { f.clock.now = expiry; FinanceHttpResponse(200, categories()) }
        failure(FinanceFailure.SessionExpired, f.repos.categories.list())
        assertFalse(f.auth.hasActiveSession()); assertEquals(0, f.api.logoutCalls)
    }
    @Test fun responseBodyLimitAndRedactedRepresentationsAreEnforced() = runTest {
        val f = Fixture(); f.login()
        f.http.response = { FinanceHttpResponse(200, "x".repeat(MAX_BODY_BYTES + 1)) }
        failure(FinanceFailure.InvalidResponse, f.repos.categories.list())
        assertFalse(FinanceHttpResponse(500, "private-token").toString().contains("private-token"))
        val lease = (f.auth.financeSession() as FinanceResult.Success).value
        assertFalse(lease.toString().contains("offline-access"))
    }
    companion object {
        private val expiry = Instant.parse("2030-01-01T00:15:00Z")
        private const val category = """{"categoryId":7,"name":"Salary","type":"Income","colorHEX":"#A0b1C2","isActive":true,"isSystemDefault":true}"""
        private fun categories(page: Int = 1, size: Int = 50, total: Long = 1) =
            """{"items":[$category],"page":$page,"pageSize":$size,"totalCount":$total}"""
        private fun transactions(page: Int = 1, size: Int = 20) = """{"items":[{
            "transactionId":42,"clientId":"5bdb163e-8c0a-4fb9-ade4-e45d94368bfc","type":"Expense",
            "categoryId":7,"categoryName":"Food","amount":90071992547409.91,"currencyCode":"USD",
            "fxRate":1.12345678,"fxRateDate":"2026-11-14","amountBase":12.34,
            "transactionDate":"2026-11-15","description":null,"createdAtUtc":"2026-11-15T01:02:03.1234567Z",
            "billId":null,"billDueDate":null}],"page":$page,"pageSize":$size,"totalCount":10}"""
    }
}
