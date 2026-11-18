package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** ComponentActivity + real repositories + fake HTTP engine only. Cannot reach a backend. */
class AuthenticatedTransactionsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var login: LoginViewModel
    private val requests = CopyOnWriteArrayList<FinanceHttpRequest>()
    private var demoLoads = 0
    @Volatile private var status = 200
    @Volatile private var body = transaction
    private val auth = InMemoryAuthRepository(object : AuthApi {
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
            AuthPayload("offline-access", "offline-refresh", Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200),
                AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
        override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
    })
    private val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
        FinanceHttpEngine { requests += it; FinanceHttpResponse(status, body) })
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show() {
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            login = ViewModelProvider(owner, LoginViewModel.Factory(auth, authenticatedTransactions = finance.transactions))
                .get(LoginViewModel::class.java)
            val content: @androidx.compose.runtime.Composable (String?) -> Unit = { key ->
                OwliFinanceContent(key, login.authenticatedTransactions,
                    demoTransactions = {
                        demoLoads++
                        ViewModelProvider(owner, TransactionListViewModel.Factory(MockTransactionRepository()))
                            .get("demo", TransactionListViewModel::class.java)
                    },
                    isSessionCurrent = { login.state.value.authenticatedContentKey == it },
                    onSessionExpired = { if (login.state.value.authenticatedContentKey == it) login.sessionExpired() },
                    sessionKeys = login.state.map { it.authenticatedContentKey },
                    dashboardContent = { Text(stringResource(R.string.dashboard_demo_data)) })
            }
            PersonalFinanceTheme {
                OwliEntryGate(login, authenticatedContent = { content(it) }) { content(null) }
            }
        }
    }
    private fun authenticate() {
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
    }
    private fun transactions() = compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()

    @Test fun authenticatedTabsUseFinanceAndRetainPaginationUntilLogout() {
        show(); authenticate()
        assertTrue(requests.isEmpty())
        transactions()
        compose.onNodeWithText(text(R.string.transactions_account_data)).assertIsDisplayed()
        compose.onNodeWithText("USD 12.34", substring = true).assertExists()
        compose.onNodeWithText(text(R.string.transactions_demo_data)).assertDoesNotExist()
        assertEquals(0, demoLoads); assertEquals(1, requests.size)
        compose.onNodeWithText(text(R.string.navigation_dashboard)).performClick()
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        transactions(); compose.waitForIdle(); assertEquals(1, requests.size)
        compose.onNodeWithText(text(R.string.auth_logout)).performClick()
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText("USD 12.34", substring = true).assertDoesNotExist()
    }
    @Test fun mockDemoNeverUsesFinanceTransport() {
        show()
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        transactions()
        compose.onNodeWithText(text(R.string.transactions_demo_data)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_account_data)).assertDoesNotExist()
        assertTrue(demoLoads > 0); assertTrue(requests.isEmpty())
    }
    @Test fun protected401ReturnsToExistingLoginExpiredState() {
        status = 401; body = "private response must not be rendered"
        show(); authenticate(); transactions()
        compose.onNodeWithText(text(R.string.auth_session_expired)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_account_data)).assertDoesNotExist()
        assertEquals(1, requests.size)
    }
    @Test fun authenticatedErrorRetryAndEmptyCopyDoNotDescribeDemoData() {
        status = 503; show(); authenticate(); transactions()
        compose.onNodeWithText(text(R.string.transactions_account_unavailable_message)).assertIsDisplayed()
        status = 200; body = """{"items":[],"page":1,"pageSize":20,"totalCount":0}"""
        compose.onNodeWithText(text(R.string.transactions_retry)).performClick()
        compose.onNodeWithText(text(R.string.transactions_account_empty_message)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_empty_message)).assertDoesNotExist()
        assertEquals(2, requests.size)
    }
    companion object {
        private const val transaction = """{"items":[{"transactionId":1,"clientId":"5bdb163e-8c0a-4fb9-ade4-e45d94368bfc",
        "type":"Expense","categoryId":7,"categoryName":"Food","amount":12.34,"currencyCode":"USD",
        "fxRate":1.0,"fxRateDate":"2026-11-15","amountBase":12.34,"transactionDate":"2026-11-15",
        "description":null,"createdAtUtc":"2026-11-15T01:02:03Z","billId":null,"billDueDate":null}],
        "page":1,"pageSize":20,"totalCount":1}"""
    }
}
