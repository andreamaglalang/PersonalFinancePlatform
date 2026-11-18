package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.*
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Real auth + finance repositories with in-memory transports only; no sockets. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatedTransactionsTest {
    @get:Rule val main = MainDispatcherRule()
    private class Fixture {
        val expiry = Instant.parse("2030-01-01T00:15:00Z")
        val clock = MutableAuthClock(expiry.minusSeconds(60))
        val api = object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", expiry, expiry.plusSeconds(1000),
                    AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
        }
        val auth = InMemoryAuthRepository(api, clock)
        val requests = mutableListOf<FinanceHttpRequest>()
        var reply: suspend (FinanceHttpRequest) -> FinanceHttpResponse = { FinanceHttpResponse(200, emptyPage()) }
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
            FinanceHttpEngine { requests += it; reply(it) })
        val login = LoginViewModel(auth, isValidEmail = { true }, authenticatedTransactions = finance.transactions)
        val owner = ViewModelStore().apply { put("login", login) }
        var account: AccountTransactionStore? = null
        fun open(): AccountTransactionStore {
            val key = checkNotNull(login.state.value.authenticatedContentKey)
            return AccountTransactionStore(checkNotNull(login.authenticatedTransactions),
                { login.state.value.authenticatedContentKey == key },
                { if (login.state.value.authenticatedContentKey == key) login.sessionExpired() }).also { account = it }
        }
        fun close() { account?.close(); owner.clear() }
    }
    private suspend fun TestScope.signedIn(f: Fixture) {
        f.login.login("offline@example.test", "fixture-only")
        runCurrent()
        assertTrue(f.login.state.value.isSignedIn)
    }
    @Test fun authenticatedScopeUsesFinanceRepositoryLazilyAndRetainsModelAcrossTabVisits() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            signedIn(f)
            val scope = f.open()
            assertTrue(f.requests.isEmpty())
            val model = scope.model(); runCurrent()
            assertSame(model, scope.model())
            assertEquals(1, f.requests.size)
            assertEquals("/api/transactions", f.requests.single().url.path)
            assertEquals(TransactionListUiState.Empty, model.state.value)
            scope.close()
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
        } finally { f.close() }
    }
    @Test fun protected401UsesExistingLoginSessionExpiredTransition() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            signedIn(f)
            f.reply = { FinanceHttpResponse(401, "never displayed") }
            val model = f.open().model(); runCurrent()
            assertFalse(f.login.state.value.isSignedIn)
            assertEquals(AuthFailure.SessionExpired, f.login.state.value.error)
            assertFalse(f.auth.hasActiveSession())
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
            assertEquals(1, f.requests.size)
        } finally { f.close() }
    }
    @Test fun serverErrorIsRetryableAndRefreshCanRecoverWithoutSigningOut() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            signedIn(f); f.reply = { FinanceHttpResponse(503, "private") }
            val model = f.open().model(); runCurrent()
            assertEquals(TransactionListUiState.InitialError(TransactionFailure.Unavailable), model.state.value)
            assertTrue(f.login.state.value.isSignedIn)
            f.reply = { FinanceHttpResponse(200, emptyPage()) }
            model.retryInitialLoad(); runCurrent()
            assertEquals(TransactionListUiState.Empty, model.state.value)
            model.refresh(); runCurrent()
            assertEquals(3, f.requests.size)
            assertEquals(TransactionListUiState.Empty, model.state.value)
        } finally { f.close() }
    }
    @Test fun logoutCancelsPendingReadAndFinancialDisposalErasesState() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            signedIn(f)
            var cancelled = false
            f.reply = { try { awaitCancellation() } finally { cancelled = true } }
            val scope = f.open(); val model = scope.model(); runCurrent()
            assertEquals(TransactionListUiState.InitialLoading, model.state.value)
            f.login.logout(); runCurrent(); scope.close()
            assertTrue(cancelled)
            assertFalse(f.login.state.value.isSignedIn)
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
            model.refresh(); model.loadMore(); model.retryInitialLoad(); model.restartDemoSession(); runCurrent()
            assertEquals(1, f.requests.size)
        } finally { f.close() }
    }
    @Test fun expiryWatcherSignsOutWithoutChangingTokenPolicy() = runTest(main.dispatcher) {
        val f = Fixture()
        try {
            signedIn(f); val scope = f.open(); val model = scope.model(); runCurrent()
            f.clock.now = f.expiry
            advanceTimeBy(1000); runCurrent()
            assertEquals(AuthFailure.SessionExpired, f.login.state.value.error)
            scope.close()
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
            assertEquals(1, f.requests.size)
        } finally { f.close() }
    }
    @Test fun replacedAccountRejectsOldSuccessBeforeCompositionDisposal() = runTest(main.dispatcher) {
        val repository = ControlledTransactionRepository(completeDespiteCancellation = true)
        var current = "first"
        var expiredCalls = 0
        val old = AccountTransactionStore(repository, { current == "first" }, { expiredCalls++ })
        val model = old.model(); runCurrent()
        current = "second"
        repository.requests.single().succeed(TransactionPage(emptyList(), 1, 20, 0))
        runCurrent()
        assertTrue(model.state.value is TransactionListUiState.SessionExpired)
        assertEquals(0, expiredCalls)
        old.close()
    }
    @Test fun old401CannotSignOutReplacementAccountAndStoresDoNotShareRows() = runTest(main.dispatcher) {
        val oldRepository = ControlledTransactionRepository(true)
        val newRepository = ControlledTransactionRepository()
        var current = "first"
        var expiredCalls = 0
        val first = AccountTransactionStore(oldRepository, { current == "first" }, { expiredCalls++ })
        val firstModel = first.model(); runCurrent()
        current = "second"; first.close()
        val second = AccountTransactionStore(newRepository, { current == "second" }, { expiredCalls++ })
        try {
            val secondModel = second.model(); runCurrent()
            oldRepository.requests.single().fail(TransactionFailure.SessionExpired)
            newRepository.requests.single().succeed(TransactionPage(emptyList(), 1, 20, 0)); runCurrent()
            assertNotSame(firstModel, secondModel)
            assertTrue(firstModel.state.value is TransactionListUiState.SessionExpired)
            assertEquals(TransactionListUiState.Empty, secondModel.state.value)
            assertEquals(0, expiredCalls)
        } finally { second.close() }
    }
    @Test fun closingLoadedScopeDropsRowsAndAnUnusedScopeMakesNoRequests() = runTest(main.dispatcher) {
        val repository = ControlledTransactionRepository()
        val unused = AccountTransactionStore(repository, { true }, {})
        unused.close(); assertTrue(repository.requests.isEmpty())
        val scope = AccountTransactionStore(repository, { true }, {})
        val model = scope.model(); runCurrent()
        repository.requests.single().succeed(TransactionPage(
            com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures.items.take(2), 1, 20, 2))
        runCurrent(); assertTrue(model.state.value is TransactionListUiState.Content)
        scope.close()
        assertTrue(model.state.value is TransactionListUiState.SessionExpired)
    }
    @Test fun sessionFlowDropsLoadedRowsEvenBeforeGateDisposal() = runTest(main.dispatcher) {
        val keys = MutableStateFlow<String?>("first")
        val repository = ControlledTransactionRepository()
        val scope = AccountTransactionStore(repository, { keys.value == "first" }, {})
        val observer = launch { scope.invalidateOnSessionChange(keys, "first") }
        try {
            val model = scope.model(); runCurrent()
            repository.requests.single().succeed(TransactionPage(
                com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures.items.take(2), 1, 20, 2))
            runCurrent(); assertTrue(model.state.value is TransactionListUiState.Content)
            keys.value = null; runCurrent()
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
            model.refresh(); runCurrent(); assertEquals(1, repository.requests.size)
        } finally { observer.cancelAndJoin(); scope.close() }
    }
    @Test fun authenticatedPaginationRefreshAndRefresh401UseRealMapping() = runTest(main.dispatcher) {
        val f = Fixture()
        fun page(ids: IntRange, number: Int): String {
            val rows = ids.joinToString(",") { id -> """{"transactionId":$id,
                "clientId":"5bdb163e-8c0a-4fb9-ade4-e45d94368bfc","type":"Expense",
                "categoryId":7,"categoryName":"Offline","amount":12.34,"currencyCode":"USD",
                "fxRate":1,"fxRateDate":"2026-11-15","amountBase":12.34,"transactionDate":"2026-11-15",
                "description":null,"createdAtUtc":"2026-11-15T01:02:03Z","billId":null,"billDueDate":null}""" }
            return """{"items":[$rows],"page":$number,"pageSize":20,"totalCount":21}"""
        }
        try {
            signedIn(f)
            f.reply = { request -> FinanceHttpResponse(200,
                if (request.url.query.contains("page=2&")) page(1..1, 2) else page(2..21, 1)) }
            val model = f.open().model(); runCurrent()
            assertEquals(20, (model.state.value as TransactionListUiState.Content).items.size)
            model.loadMore(); runCurrent()
            assertEquals(21, (model.state.value as TransactionListUiState.Content).items.size)
            assertEquals(TransactionAppendState.End, (model.state.value as TransactionListUiState.Content).append)
            model.refresh(); runCurrent()
            assertEquals(20, (model.state.value as TransactionListUiState.Content).items.size)
            f.reply = { FinanceHttpResponse(401, "private") }
            model.refresh(); runCurrent()
            assertTrue(model.state.value is TransactionListUiState.SessionExpired)
            assertEquals(AuthFailure.SessionExpired, f.login.state.value.error)
            assertEquals(4, f.requests.size)
        } finally { f.close() }
    }
    companion object {
        private fun emptyPage() = """{"items":[],"page":1,"pageSize":20,"totalCount":0}"""
    }
}
