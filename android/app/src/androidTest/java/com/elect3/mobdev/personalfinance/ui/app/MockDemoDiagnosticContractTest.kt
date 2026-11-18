package com.elect3.mobdev.personalfinance.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.ui.auth.EntryMode
import com.elect3.mobdev.personalfinance.ui.auth.LoginViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI-to-effect contract with the real memory repository and a transport that cannot access HTTP. */
class MockDemoDiagnosticContractTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Observation(val event: AuthDiagnosticEvent, val instance: Int, val modeAtCall: EntryMode?)
    private val observations = CopyOnWriteArrayList<Observation>()
    private var modelReference: LoginViewModel? = null
    private lateinit var model: LoginViewModel
    private val diagnostics = object : AuthDiagnosticSink {
        override val enabled = true
        override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) {
            observations += Observation(event, instanceId, modelReference?.state?.value?.entryMode)
        }
    }
    private val api = FakeApi()
    private val repository = InMemoryAuthRepository(api, diagnostics = diagnostics)
    private var compositionVersion by mutableIntStateOf(0)
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun showGate() {
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            model = ViewModelProvider(owner, LoginViewModel.Factory(repository, diagnostics))
                .get(LoginViewModel::class.java)
            modelReference = model
            PersonalFinanceTheme {
                Text("composition-$compositionVersion")
                OwliEntryGate(model) { Text("MOCK_DIAGNOSTIC_CONTENT") }
            }
        }
        compose.waitForIdle()
    }

    private fun enterFromVisibleButton() {
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo().performClick()
        compose.waitUntil(5_000) { observations.any { it.event == AuthDiagnosticEvent.GATE_MOCK_DEMO } }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertIsDisplayed()
        compose.onNodeWithText("MOCK_DIAGNOSTIC_CONTENT").assertIsDisplayed()
    }

    @Test fun actualDemoClickClearsBeforePublicationAndEmitsTheVisibleGateDiagnostic() {
        showGate()
        val before = observations.size
        enterFromVisibleButton()
        compose.runOnIdle {
            val entry = observations.drop(before)
            assertEquals(listOf(AuthDiagnosticEvent.LOCAL_SESSION_CLEARED, AuthDiagnosticEvent.GATE_MOCK_DEMO),
                entry.map { it.event })
            assertEquals(repository.diagnosticId, entry[0].instance)
            assertEquals(EntryMode.SignedOut, entry[0].modeAtCall)
            assertEquals(model.diagnosticId, entry[1].instance)
            assertEquals(EntryMode.MockDemo, entry[1].modeAtCall)
            assertFalse(model.state.value.isSignedIn)
            assertFalse(repository.hasActiveSession())
            assertEquals(0, api.loginCalls)
            assertEquals(0, api.logoutCalls)
        }
    }

    @Test fun repeatedDemoRequestAndRecompositionDoNotInventAnotherGateTransition() {
        showGate()
        enterFromVisibleButton()
        val original = model
        compose.runOnIdle {
            model.exploreMockDemo() // The button has left composition; simulate a duplicate request.
            compositionVersion++
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertIsDisplayed()
        compose.runOnIdle {
            assertSame(original, model)
            assertEquals(2, observations.count { it.event == AuthDiagnosticEvent.LOCAL_SESSION_CLEARED })
            assertEquals(1, observations.count { it.event == AuthDiagnosticEvent.GATE_MOCK_DEMO })
            assertEquals(0, api.loginCalls)
            assertEquals(0, api.logoutCalls)
        }
    }

    @Test fun disabledDemoButtonDuringFakePendingLoginCannotPublishDemoOrClearAgain() {
        showGate()
        compose.runOnIdle { model.login("test@example.test", "test-only-input") }
        compose.waitUntil(5_000) { api.loginCalls == 1 }
        val before = observations.size
        compose.onNodeWithText(text(R.string.auth_explore_mock_demo)).performScrollTo()
            .assertIsNotEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.state.value.isLoading)
            assertEquals(EntryMode.SignedOut, model.state.value.entryMode)
            assertEquals(before, observations.size)
            assertEquals(1, api.loginCalls) // Fake deferred call, never HTTP.
            assertEquals(0, api.logoutCalls)
        }
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertDoesNotExist()
    }

    private class FakeApi : AuthApi {
        @Volatile var loginCalls = 0
        @Volatile var logoutCalls = 0
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> {
            loginCalls++
            return awaitCancellation()
        }
        override suspend fun logout(refreshToken: String): ApiResult<Unit> {
            logoutCalls++
            error("Mock Demo must not request logout")
        }
    }
}
