package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardRoute
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardUiState
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListUiState
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.time.YearMonth
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText

/**
 * M1 end to end in the real app composition: sign in -> Transactions -> Add -> Save -> list refresh.
 * Real repositories, coordinator and JSON; fake GET/POST engines only, so no backend can be reached.
 */
class AuthenticatedSaveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var login: LoginViewModel
    private lateinit var demoListing: TransactionListViewModel
    private lateinit var demoDashboard: DashboardViewModel
    private val demoSession = DemoSession()
    private val demoListRepository = MockTransactionRepository(ledger = demoSession.ledger)
    private val demoDashboardLoader = LoadDashboardSummary(MockTransactionRepository(ledger = demoSession.ledger))
    private val authRequests = CopyOnWriteArrayList<String>()
    private val categoryWrites = CopyOnWriteArrayList<String>()
    private val gets = CopyOnWriteArrayList<String>()
    private val posts = CopyOnWriteArrayList<FinancePostRequest>()
    @Volatile private var saved: String? = null
    @Volatile private var postStatus = 201
    private val auth = InMemoryAuthRepository(object : AuthApi {
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> {
            authRequests += "login"
            return ApiResult.Success(
            AuthPayload("offline-access", "offline-refresh", Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200),
                AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
        }
        override suspend fun logout(refreshToken: String): ApiResult<Unit> {
            authRequests += "logout"
            return ApiResult.Success(Unit)
        }
        override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> {
            authRequests += "refresh"
            return ApiResult.Failure(AuthFailure.NotConfigured)
        }
    })
    private val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
        FinanceHttpEngine { request ->
            val path = request.url.path; gets += path
            if (path.endsWith("/api/categories")) {
                val query = request.url.query.orEmpty()
                val size = Regex("pageSize=(\\d+)").find(query)!!.groupValues[1].toInt()
                val rows = listOfNotNull(
                    categoryRow(2, "Salary", "Income").takeUnless { query.contains("type=Expense") },
                    categoryRow(7, "Food", "Expense").takeUnless { query.contains("type=Income") })
                FinanceHttpResponse(200, """{"items":[${rows.joinToString(",")}],"page":1,"pageSize":$size,"totalCount":${rows.size}}""")
            } else {
                val row = saved
                val size = Regex("pageSize=(\\d+)").find(request.url.query.orEmpty())?.groupValues?.get(1) ?: "20"
                FinanceHttpResponse(200, if (row == null) """{"items":[],"page":1,"pageSize":$size,"totalCount":0}"""
                    else """{"items":[$row],"page":1,"pageSize":$size,"totalCount":1}""")
            }
        },
        FinancePostEngine { request ->
            posts += request
            if (postStatus == 201) { val row = created(request.body); saved = row; FinanceHttpResponse(201, row) }
            else FinanceHttpResponse(postStatus, "private response must not be rendered")
        },
        FinanceWriteEngine { request ->
            categoryWrites += request.method.name
            FinanceHttpResponse(503, "")
        })
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show() {
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            login = ViewModelProvider(owner, LoginViewModel.Factory(auth, authenticatedTransactions = finance.transactions,
                authenticatedCategories = finance.categories, authenticatedCurrency = finance.baseCurrency,
                authenticatedCreation = finance.creation)).get(LoginViewModel::class.java)
            // Same retained demo ledger and refresh wiring as MainActivity; no production transport is reachable.
            demoListing = ViewModelProvider(owner, TransactionListViewModel.Factory(
                demoListRepository, initialFilter = TransactionFilter().forMonth(YearMonth.now())))
                .get("demo", TransactionListViewModel::class.java)
            demoDashboard = ViewModelProvider(owner, DashboardViewModel.Factory(
                demoDashboardLoader, YearMonth.now())).get("demo-dashboard", DashboardViewModel::class.java)
            val content: @androidx.compose.runtime.Composable (String?) -> Unit = { key ->
                OwliFinanceContent(key, login.authenticatedTransactions,
                    demoTransactions = { demoListing },
                    isSessionCurrent = { login.state.value.authenticatedContentKey == it },
                    onSessionExpired = { if (login.state.value.authenticatedContentKey == it) login.sessionExpired() },
                    sessionKeys = login.state.map { it.authenticatedContentKey },
                    authenticatedCategories = login.authenticatedCategories,
                    authenticatedCurrency = login.authenticatedCurrency,
                    authenticatedCreation = login.authenticatedCreation,
                    demoSession = demoSession,
                    onDemoDataChanged = {
                        demoListing.refreshAfterConfirmedCreation()
                        demoDashboard.refresh()
                    },
                    dashboardContent = { DashboardRoute(demoDashboard) })
            }
            PersonalFinanceTheme { OwliEntryGate(login, authenticatedContent = { content(it) }) { content(null) } }
        }
    }
    private fun authenticate() {
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
    }
    /** Category-first flow: Activity's Add opens the picker; choosing Food opens the form with it preselected. */
    private fun openAddForm() {
        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick() // the central (+)
        compose.onNodeWithText(compose.activity.getString(R.string.cat_choose_overline, text(R.string.transaction_expense))).assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Food").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Food") and hasClickAction() and hasAnyAncestor(isDialog())).performClick() // the picker tile, not a list row behind it
        compose.onNodeWithText(text(R.string.form_account_notice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cat_form_title_expense)).assertIsDisplayed()
    }
    private fun fillValidExpense() {
        compose.onNode(hasText(text(R.string.form_amount)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement("12.34")
    }

    @Test fun signedInExpenseIsSavedThroughTheApiAndAppearsAfterTheListRefreshes() {
        show(); authenticate(); openAddForm()
        compose.onNodeWithText(text(R.string.form_save_unavailable_short)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertIsNotEnabled()
        fillValidExpense()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            posts.size == 1 && gets.count { it.endsWith("/api/transactions") } >= 2 &&
                compose.onAllNodesWithText(text(R.string.tx_saved_title)).fetchSemanticsNodes().isNotEmpty() // confirmed
        }
        compose.onNodeWithText(text(R.string.tx_saved_lead)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tx_back_dashboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        // The refreshed server row (the month summary and day total show the same amount too).
        compose.onAllNodes(hasText("₱12.34", substring = true) and hasClickAction()).assertCountEquals(1)
        assertEquals("Bearer offline-access", posts.single().authorization())
        val json = JsonParser.parseString(posts.single().body).asJsonObject
        assertEquals("Expense", json["type"].asString); assertEquals("PHP", json["currencyCode"].asString)
        assertEquals(7, json["categoryId"].asInt)
    }

    @Test fun expiredSessionDuringSaveReturnsToLoginWithoutSuccessOrListData() {
        postStatus = 401
        show(); authenticate(); openAddForm(); fillValidExpense()
        compose.onNodeWithText(text(R.string.form_save_expense)).performClick()
        // The 401 is handled asynchronously; wait for the return to Login before asserting.
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.auth_session_expired)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.auth_session_expired)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_account_success)).assertDoesNotExist()
        assertEquals(1, posts.size)
    }

    @Test fun mockDemoFormOffersOnlySessionSavingAndNeverReachesTheApi() {
        show()
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick() // the central (+)
        compose.onNode(hasText("Food") and hasClickAction() and hasAnyAncestor(isDialog())).performClick() // the picker tile, not a list row behind it
        compose.onNodeWithText(text(R.string.form_demo_session_notice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_demo_session_save)).assertExists()
        compose.onNodeWithText(text(R.string.form_account_notice)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertDoesNotExist()
        assertTrue(posts.isEmpty()); assertTrue(gets.isEmpty())
    }

    @Test fun mockDemoExpenseRefreshesActivityAndDashboardWithoutHttp() {
        val amount = BigDecimal("12.34")
        val description = "Demo workflow regression expense"
        val income = TransactionFixtures.items.filter { it.type == TransactionType.Income }
            .fold(BigDecimal.ZERO) { total, item -> total.add(item.amountBase) }
        val expenses = TransactionFixtures.items.filter { it.type == TransactionType.Expense }
            .fold(BigDecimal.ZERO) { total, item -> total.add(item.amountBase) }

        show()
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            (demoDashboard.state.value as? DashboardUiState.Content)?.let {
                !it.isRefreshing && it.summary.totalExpenses.compareTo(expenses) == 0
            } == true
        }
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(demoSession.ledger.snapshot().isEmpty()) }

        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        val originalFilter = demoListing.activeFilter.value
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        compose.onNode(hasText("Food") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(text(R.string.form_demo_session_notice)).assertIsDisplayed()
        fillValidExpense()
        compose.onNode(hasText(text(R.string.form_description)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performScrollTo().performTextInput(description)
        compose.onNodeWithText(text(R.string.form_demo_session_save)).assertIsEnabled().performClick()

        compose.waitUntil(5_000) {
            demoSession.ledger.snapshot().size == 1 &&
                (demoListing.state.value as? TransactionListUiState.Content)?.items?.any { it.description == description } == true &&
                (demoDashboard.state.value as? DashboardUiState.Content)?.let {
                    !it.isRefreshing && it.summary.totalExpenses.compareTo(expenses.add(amount)) == 0
                } == true
        }
        compose.onNodeWithText(text(R.string.tx_demo_saved_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tx_demo_saved_lead)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tx_saved_lead)).assertDoesNotExist()
        compose.runOnIdle {
            val savedDemo = demoSession.ledger.snapshot().single()
            assertEquals(TransactionType.Expense, savedDemo.type)
            assertEquals(0, amount.compareTo(savedDemo.amount))
            assertEquals(description, savedDemo.description)
            val summary = (demoDashboard.state.value as DashboardUiState.Content).summary
            assertEquals(0, income.compareTo(summary.totalIncome))
            assertEquals(0, expenses.add(amount).compareTo(summary.totalExpenses))
            assertEquals(0, income.subtract(expenses.add(amount)).compareTo(summary.netTransactionFlow))
            assertEquals(TransactionFixtures.items.size.toLong() + 1, summary.recordCount)
        }

        compose.onNodeWithText(text(R.string.tx_back_dashboard)).performScrollTo().performClick()
        // Exercise the real Dashboard renderer, not a placeholder or only the ViewModel.
        compose.onAllNodesWithText(formatPhpAmount(expenses.add(amount)), substring = true)
            .onFirst().performScrollTo().assertIsDisplayed()
        repeat(2) {
            compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
            // Transactions shows the session strip and the list's own data-source label; both carry this text.
            compose.onAllNodesWithText(text(R.string.auth_demo_title)).assertCountEquals(2).onFirst().assertIsDisplayed()
            compose.onNodeWithText(description, substring = true).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(originalFilter, demoListing.activeFilter.value)
                assertEquals(1, (demoListing.state.value as TransactionListUiState.Content).items.count { it.description == description })
                assertEquals(1, demoSession.ledger.snapshot().size)
            }
            compose.onNodeWithText(text(R.string.navigation_dashboard)).performClick()
            compose.onNodeWithText(text(R.string.auth_demo_title)).assertIsDisplayed()
            compose.onAllNodesWithText(formatPhpAmount(expenses.add(amount)), substring = true)
                .onFirst().performScrollTo().assertIsDisplayed()
        }
        assertTrue("Mock Demo must not call authentication", authRequests.isEmpty())
        assertTrue("Mock Demo must not call finance GET", gets.isEmpty())
        assertTrue("Mock Demo must not call transaction POST", posts.isEmpty())
        assertTrue("Mock Demo must not call category writes", categoryWrites.isEmpty())
    }

    companion object {
        private fun categoryRow(id: Int, name: String, type: String) =
            """{"categoryId":$id,"name":"$name","type":"$type","colorHEX":null,"isActive":true,"isSystemDefault":true}"""
        private fun created(body: String) = JsonParser.parseString(body).asJsonObject.apply {
            addProperty("transactionId", 501); addProperty("categoryName", "Food")
            addProperty("fxRate", 1.0); add("fxRateDate", get("transactionDate")); add("amountBase", get("amount"))
            addProperty("createdAtUtc", "2026-11-16T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }
}
