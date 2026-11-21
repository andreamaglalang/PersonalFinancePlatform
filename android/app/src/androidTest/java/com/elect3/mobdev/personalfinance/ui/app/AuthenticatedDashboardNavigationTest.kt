package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardRoute
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardSource
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import java.time.Instant
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * M2 in the real app composition: signed-in Dashboard shows this account's figures, labelled as account
 * data; logout removes them; Mock Demo keeps its sample Dashboard. Fake GET engine only; no backend.
 */
class AuthenticatedDashboardNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var login: LoginViewModel
    private val gets = CopyOnWriteArrayList<String>()
    private val auth = InMemoryAuthRepository(object : AuthApi {
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
            AuthPayload("offline-access", "offline-refresh", Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200),
                AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
        override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
    })
    private val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
        FinanceHttpEngine { request ->
            val query = request.url.query.orEmpty(); gets += query
            val page = Regex("(?:^|&)page=(\\d+)").find(query)!!.groupValues[1].toInt()
            val size = Regex("(?:^|&)pageSize=(\\d+)").find(query)!!.groupValues[1].toInt()
            FinanceHttpResponse(200, """{"items":[${row(2, "Income", "2500.00")},${row(1, "Expense", "400.00")}],"page":$page,"pageSize":$size,"totalCount":2}""")
        })
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show() {
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            login = ViewModelProvider(owner, LoginViewModel.Factory(auth, authenticatedTransactions = finance.transactions,
                authenticatedCategories = finance.categories, authenticatedCurrency = finance.baseCurrency))
                .get(LoginViewModel::class.java)
            val demoDashboard = ViewModelProvider(owner, DashboardViewModel.Factory(
                LoadDashboardSummary(MockTransactionRepository()), YearMonth.of(2026, 11))).get("demo-dashboard", DashboardViewModel::class.java)
            val content: @androidx.compose.runtime.Composable (String?) -> Unit = { key ->
                OwliFinanceContent(key, login.authenticatedTransactions,
                    demoTransactions = {
                        ViewModelProvider(owner, TransactionListViewModel.Factory(MockTransactionRepository()))
                            .get("demo", TransactionListViewModel::class.java)
                    },
                    isSessionCurrent = { login.state.value.authenticatedContentKey == it },
                    onSessionExpired = { if (login.state.value.authenticatedContentKey == it) login.sessionExpired() },
                    sessionKeys = login.state.map { it.authenticatedContentKey },
                    authenticatedCategories = login.authenticatedCategories,
                    authenticatedCurrency = login.authenticatedCurrency,
                    dashboardContent = { DashboardRoute(demoDashboard) },
                    accountDashboard = { DashboardRoute(it, source = DashboardSource.Account) })
            }
            PersonalFinanceTheme { OwliEntryGate(login, authenticatedContent = { content(it) }) { content(null) } }
        }
    }

    @Test fun signedInDashboardShowsThisAccountsFiguresLabelledAsAccountData() {
        show()
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.dashboard_account_data)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.dashboard_account_data)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("+₱2,100.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.dashboard_account_net_explanation)).performScrollTo().assertIsDisplayed()
        assertTrue(gets.isNotEmpty() && gets.all { it.contains("pageSize=100") })
    }

    @Test fun logoutRemovesTheAccountDashboard() {
        show()
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("+₱2,100.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.auth_logout)).performClick()
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText("+₱2,100.00").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.dashboard_account_data)).assertDoesNotExist()
    }

    @Test fun mockDemoKeepsItsSampleDashboardAndNeverReadsTheAccountApi() {
        show()
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.dashboard_demo_data)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.dashboard_account_data)).assertDoesNotExist()
        assertTrue(gets.isEmpty())
    }

    companion object {
        private fun row(id: Int, type: String, amount: String) =
            """{"transactionId":$id,"clientId":"00000000-0000-4000-8000-${id.toString().padStart(12, '0')}","type":"$type",""" +
                """"categoryId":7,"categoryName":"Food","amount":$amount,"currencyCode":"PHP","fxRate":1.00000000,""" +
                """"fxRateDate":"2026-11-05","amountBase":$amount,"transactionDate":"2026-11-05","description":null,""" +
                """"createdAtUtc":"2026-11-05T01:02:03Z","billId":null,"billDueDate":null}"""
    }
}
