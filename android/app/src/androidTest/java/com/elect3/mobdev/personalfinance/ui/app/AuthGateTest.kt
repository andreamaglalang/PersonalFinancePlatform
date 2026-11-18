package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.ui.auth.EntryMode
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake repository only: no HTTP, credentials, token values or device configuration changes. */
class AuthGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: LoginViewModel
    private lateinit var back: OnBackPressedDispatcherOwner
    private lateinit var owner: ViewModelStoreOwner
    private val repository = LocalRepository()
    private val diagnosticEvents = mutableListOf<AuthDiagnosticEvent>()
    private val diagnostics = object : AuthDiagnosticSink {
        override val enabled = true
        override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) {
            diagnosticEvents += event
        }
    }
    private var recompositions by mutableIntStateOf(0)
    private var fallbackBacks = 0

    private fun showGate() {
        compose.setContent { GateContent() }
    }

    @Composable
    private fun GateContent() {
        owner = checkNotNull(LocalViewModelStoreOwner.current)
        back = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
        // Registered before the gate's children: real navigation handlers take precedence.
        DisposableEffect(back) {
            val fallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { fallbackBacks++ }
            }
            back.onBackPressedDispatcher.addCallback(fallback)
            onDispose { fallback.remove() }
        }
        model = ViewModelProvider(owner, LoginViewModel.Factory(repository, diagnostics)).get(LoginViewModel::class.java)
        PersonalFinanceTheme {
            Text("composition-$recompositions")
            OwliEntryGate(model) {
                OwliApp({ Text("AUTH_TEST_DASHBOARD") }, { Text("AUTH_TEST_TRANSACTIONS") },
                    diagnostics = diagnostics, diagnosticInstanceId = model.diagnosticId)
            }
        }
    }

    private fun authenticate() {
        compose.runOnIdle { model.login("test@example.test", "test-only-input") }
        compose.waitUntil(5_000) { model.state.value.entryMode == EntryMode.Authenticated }
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertIsDisplayed()
    }

    @Test fun loginFirstRecompositionAndTabsPreserveAuthentication() {
        showGate()
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertDoesNotExist()
        authenticate()
        val original = model
        compose.runOnIdle { recompositions++ }
        compose.waitForIdle()
        assertSame(original, model)
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("AUTH_TEST_TRANSACTIONS").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertIsDisplayed()
        assertEquals(1, repository.loginCalls)
        assertTrue(repository.active)
        assertTrue(AuthDiagnosticEvent.NAV_TRANSACTIONS_SELECTED in diagnosticEvents)
        assertTrue(AuthDiagnosticEvent.BACK_TRANSACTIONS_TO_DASHBOARD in diagnosticEvents)
        assertFalse(AuthDiagnosticEvent.EXPLICIT_LOGOUT_INITIATED in diagnosticEvents)
    }

    @Test fun authenticatedActivityRecreationRetainsModelSessionAndGate() {
        showGate()
        authenticate()
        val original = model
        val originalKey = model.state.value.authenticatedContentKey
        compose.activityRule.scenario.recreate()
        // The test host has no application content factory; reinstall its identical gate after recreation.
        compose.activityRule.scenario.onActivity { it.setContent { GateContent() } }
        compose.waitForIdle()
        assertSame(original, model)
        assertEquals(originalKey, model.state.value.authenticatedContentKey)
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertIsDisplayed()
        assertEquals(1, repository.loginCalls)
        assertEquals(0, repository.logoutCalls)
    }

    @Test fun visibleLogoutRemovesAuthenticatedBackStackAndBackCannotReopenIt() {
        showGate()
        authenticate()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Log out").performClick()
        compose.waitUntil(5_000) { !model.state.value.isLoading }
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertDoesNotExist()
        compose.onNodeWithText("AUTH_TEST_TRANSACTIONS").assertDoesNotExist()
        assertEquals(EntryMode.SignedOut, model.state.value.entryMode)
        assertEquals(1, repository.logoutCalls)
        // With the authenticated NavHost disposed, Back has no authenticated destination to reopen.
        compose.runOnIdle { back.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, fallbackBacks)
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertDoesNotExist()
    }

    @Test fun failedLogoutDisplaysUnconfirmedRevocationAndCannotRestoreContent() {
        repository.logoutResult = AuthResult.Failure(AuthFailure.Unavailable)
        showGate()
        authenticate()
        compose.onNodeWithText("Log out").performClick()
        compose.waitUntil(5_000) { !model.state.value.isLoading }
        assertTrue(model.state.value.revocationUnconfirmed)
        compose.onNodeWithText("AUTH_TEST_DASHBOARD").assertDoesNotExist()
        compose.onNodeWithText("You are signed out on this device. Server sign-out could not be confirmed.")
            .performScrollTo().assertIsDisplayed()
    }

    private class LocalRepository : AuthRepository {
        var active = false
        var loginCalls = 0
        var logoutCalls = 0
        var logoutResult: AuthResult = AuthResult.Success
        override suspend fun login(email: String, password: String): AuthResult {
            loginCalls++
            active = true
            return AuthResult.Success
        }
        override suspend fun logout(): AuthResult {
            logoutCalls++
            active = false
            return logoutResult
        }
        override fun hasActiveSession() = active
        override fun clearSession() { active = false }
    }
}
