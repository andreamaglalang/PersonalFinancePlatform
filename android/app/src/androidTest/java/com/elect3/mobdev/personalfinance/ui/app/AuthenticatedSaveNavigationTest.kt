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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * M1 end to end in the real app composition: sign in -> Transactions -> Add -> Save -> list refresh.
 * Real repositories, coordinator and JSON; fake GET/POST engines only, so no backend can be reached.
 */
class AuthenticatedSaveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var login: LoginViewModel
    private val gets = CopyOnWriteArrayList<String>()
    private val posts = CopyOnWriteArrayList<FinancePostRequest>()
    @Volatile private var saved: String? = null
    @Volatile private var postStatus = 201
    private val auth = InMemoryAuthRepository(object : AuthApi {
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
            AuthPayload("offline-access", "offline-refresh", Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200),
                AuthUser(1, "Offline", "offline@example.test", "PHP", listOf("User"))))
        override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
    })
    private val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth,
        FinanceHttpEngine { request ->
            val path = request.url.path; gets += path
            if (path.endsWith("/api/categories")) {
                val income = request.url.query.orEmpty().contains("type=Income")
                FinanceHttpResponse(200, if (income) category(2, "Salary", "Income") else category(7, "Food", "Expense"))
            } else {
                val row = saved
                FinanceHttpResponse(200, if (row == null) """{"items":[],"page":1,"pageSize":20,"totalCount":0}"""
                    else """{"items":[$row],"page":1,"pageSize":20,"totalCount":1}""")
            }
        },
        FinancePostEngine { request ->
            posts += request
            if (postStatus == 201) { val row = created(request.body); saved = row; FinanceHttpResponse(201, row) }
            else FinanceHttpResponse(postStatus, "private response must not be rendered")
        })
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show() {
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            login = ViewModelProvider(owner, LoginViewModel.Factory(auth, authenticatedTransactions = finance.transactions,
                authenticatedCategories = finance.categories, authenticatedCurrency = finance.baseCurrency,
                authenticatedCreation = finance.creation)).get(LoginViewModel::class.java)
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
                    authenticatedCreation = login.authenticatedCreation,
                    dashboardContent = { Text(stringResource(R.string.dashboard_demo_data)) })
            }
            PersonalFinanceTheme { OwliEntryGate(login, authenticatedContent = { content(it) }) { content(null) } }
        }
    }
    private fun authenticate() {
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
    }
    private fun openAddForm() {
        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        compose.onNodeWithText(text(R.string.form_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.form_account_notice)).assertIsDisplayed()
    }
    private fun fillValidExpense() {
        compose.onNode(hasText(text(R.string.form_amount)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextInput("12.34")
        compose.waitUntil(5_000) { gets.any { it.endsWith("/api/categories") } }
        compose.onNodeWithText(text(R.string.form_category) + ": " + text(R.string.form_choose_category)).performScrollTo().performClick()
        compose.onNodeWithText("Food").performClick()
    }

    @Test fun signedInExpenseIsSavedThroughTheApiAndAppearsAfterTheListRefreshes() {
        show(); authenticate(); openAddForm()
        compose.onNodeWithText(text(R.string.form_save_unavailable_short)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertIsNotEnabled()
        fillValidExpense()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            posts.size == 1 && gets.count { it.endsWith("/api/transactions") } >= 2 &&
                compose.onAllNodesWithText(text(R.string.form_account_notice)).fetchSemanticsNodes().isEmpty() // form closed
        }
        compose.onNodeWithText("₱12.34", substring = true).assertExists() // the refreshed server row
        assertEquals("Bearer offline-access", posts.single().authorization())
        val json = JsonParser.parseString(posts.single().body).asJsonObject
        assertEquals("Expense", json["type"].asString); assertEquals("PHP", json["currencyCode"].asString)
        assertEquals(7, json["categoryId"].asInt)
    }

    @Test fun expiredSessionDuringSaveReturnsToLoginWithoutSuccessOrListData() {
        postStatus = 401
        show(); authenticate(); openAddForm(); fillValidExpense()
        compose.onNodeWithText(text(R.string.form_save_expense)).performClick()
        compose.onNodeWithText(text(R.string.auth_session_expired)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_account_success)).assertDoesNotExist()
        assertEquals(1, posts.size)
    }

    @Test fun mockDemoFormIsPreviewOnlyAndNeverReachesTheApi() {
        show()
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.navigation_transactions)).performClick()
        compose.onNodeWithText(text(R.string.form_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.form_demo_notice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_preview)).assertExists()
        compose.onNodeWithText(text(R.string.form_account_notice)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertDoesNotExist()
        assertTrue(posts.isEmpty()); assertTrue(gets.isEmpty())
    }

    companion object {
        private fun category(id: Int, name: String, type: String) =
            """{"items":[{"categoryId":$id,"name":"$name","type":"$type","colorHEX":null,"isActive":true,"isSystemDefault":true}],"page":1,"pageSize":50,"totalCount":1}"""
        private fun created(body: String) = JsonParser.parseString(body).asJsonObject.apply {
            addProperty("transactionId", 501); addProperty("categoryName", "Food")
            addProperty("fxRate", 1.0); add("fxRateDate", get("transactionDate")); add("amountBase", get("amount"))
            addProperty("createdAtUtc", "2026-11-16T01:02:03Z"); add("billId", JsonNull.INSTANCE); add("billDueDate", JsonNull.INSTANCE)
        }.toString()
    }
}
