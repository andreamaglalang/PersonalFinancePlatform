package com.elect3.mobdev.personalfinance.ui.account

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.local.AppearancePreference
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.ui.app.OwliEntryGate
import com.elect3.mobdev.personalfinance.ui.app.OwliFinanceContent
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals

class AccountScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int, vararg a: Any) = compose.activity.getString(id, *a)
    private var logouts = 0
    private var picked: AppearancePreference? = null

    private fun show(mode: AccountMode, appearance: AppearancePreference = AppearancePreference.System) = compose.setContent {
        PersonalFinanceTheme {
            AccountScreen(mode, appearance, { picked = it }, onManageCategories = {}, onLogout = { logouts++ }, onBack = {})
        }
    }
    private fun scrollTo(label: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))

    @Test fun signedInShowsRealProfileInitialsAndAnUnavailablePasswordChange() {
        show(AccountMode.SignedIn(AccountProfile("Andrea Reyes", "andrea.reyes@example.test", "PHP")))
        compose.onNodeWithText("AR").assertIsDisplayed()
        compose.onNodeWithText("Andrea Reyes").assertIsDisplayed()
        compose.onNodeWithText("andrea.reyes@example.test").assertIsDisplayed()
        // Shown on the identity card and as the Session row subtitle.
        compose.onAllNodesWithText(text(R.string.account_status_signed_in)).assertCountEquals(2)
        scrollTo(text(R.string.account_password))
        compose.onNode(hasText(text(R.string.account_password), substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.account_password_lead)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_close)).performClick()
        scrollTo(text(R.string.account_logout))
        compose.onNodeWithText(text(R.string.account_exit_demo)).assertDoesNotExist()
    }

    @Test fun missingProfileUsesTheFallbackWithoutInventingData() {
        show(AccountMode.SignedIn(null))
        compose.onNodeWithText(text(R.string.account_fallback_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_profile_sub_missing)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.account_profile)) and hasClickAction()).performClick()
        compose.onAllNodesWithText(text(R.string.account_not_provided)).assertCountEquals(2) // name and email
    }

    @Test fun demoShowsExitDemoAndNoSecuritySection() {
        show(AccountMode.Demo)
        compose.onNodeWithText(text(R.string.account_status_demo)).assertIsDisplayed()
        scrollTo(text(R.string.account_exit_demo))
        compose.onNodeWithText(text(R.string.account_password), substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_exit_demo)).performClick()
        compose.onNodeWithText(text(R.string.account_exit_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_exit_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, logouts) }
    }

    @Test fun appearanceOptionsReportTheChoice() {
        show(AccountMode.Demo, AppearancePreference.Light)
        compose.onNode(hasText(text(R.string.account_appearance)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.account_theme_dark_sub)).performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.Dark, picked) }
    }

    @Test fun cancellingLogoutKeepsTheSession() {
        show(AccountMode.SignedIn(AccountProfile("A", "a@example.test", "PHP")))
        scrollTo(text(R.string.account_logout))
        compose.onNodeWithText(text(R.string.account_logout)).performClick()
        compose.onNodeWithText(text(R.string.account_logout_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
    }

    /** Full path: signed in → Dashboard avatar → Account → Log out → the real LoginViewModel logout → Sign in. */
    @Test fun signedInLogoutUsesTheRealLogoutAndReturnsToSignIn() {
        val serverLogouts = AtomicInteger()
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("offline-access", "offline-refresh", Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200),
                    AuthUser(1, "Offline Tester", "offline@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> { serverLogouts.incrementAndGet(); return ApiResult.Success(Unit) }
        })
        val finance = FinanceRepositories(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { request ->
            val size = Regex("pageSize=(\\d+)").find(request.url.query.orEmpty())?.groupValues?.get(1) ?: "20"
            FinanceHttpResponse(200, """{"items":[],"page":1,"pageSize":$size,"totalCount":0}""")
        })
        lateinit var login: LoginViewModel
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            login = remember { ViewModelProvider(owner, LoginViewModel.Factory(auth, authenticatedTransactions = finance.transactions,
                authenticatedCategories = finance.categories)).get(LoginViewModel::class.java) }
            val content: @androidx.compose.runtime.Composable (String?) -> Unit = { key ->
                OwliFinanceContent(key, login.authenticatedTransactions,
                    demoTransactions = { ViewModelProvider(owner, TransactionListViewModel.Factory(MockTransactionRepository())).get("demo", TransactionListViewModel::class.java) },
                    isSessionCurrent = { login.state.value.authenticatedContentKey == it },
                    onSessionExpired = { login.sessionExpired() },
                    sessionKeys = login.state.map { it.authenticatedContentKey },
                    authenticatedCategories = login.authenticatedCategories,
                    dashboardContent = { com.elect3.mobdev.personalfinance.ui.dashboard.DashboardScreen(
                        com.elect3.mobdev.personalfinance.ui.dashboard.DashboardUiState.Empty, {}, {},
                        onOpenAccount = com.elect3.mobdev.personalfinance.ui.components.LocalOwliShellActions.current.openAccount) },
                    accountProfile = login::accountProfile,
                    onLogout = { if (login.state.value.isSignedIn) login.logout() else login.returnToLogin() })
            }
            PersonalFinanceTheme { OwliEntryGate(login, authenticatedContent = { content(it) }) { content(null) } }
        }
        compose.runOnIdle { login.login("offline@example.test", "fixture-only") }
        compose.onNodeWithContentDescription(text(R.string.account_open)).performClick()
        compose.onNodeWithText("Offline Tester").assertIsDisplayed()
        compose.onNodeWithText("OT").assertIsDisplayed()
        compose.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()))
            .performScrollToNode(hasText(text(R.string.account_logout)))
        // Account's button (the session strip above also has a Log out button).
        compose.onNode(hasText(text(R.string.account_logout)) and hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()))
            .performClick()
        compose.onNodeWithText(text(R.string.account_logout_title)).assertIsDisplayed()
        // The confirmation sheet's button sits beside the sheet title.
        compose.onNode(hasText(text(R.string.account_logout)) and hasClickAction() and androidx.compose.ui.test.hasAnySibling(hasText(text(R.string.account_logout_title))))
            .performClick()
        compose.waitUntil(5_000) { !login.state.value.isSignedIn && !login.state.value.isLoading }
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        compose.onNodeWithText("Offline Tester").assertDoesNotExist()
        assertEquals(1, serverLogouts.get())
        assertEquals(null, auth.accountProfile())
    }
}
