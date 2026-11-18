package com.elect3.mobdev.personalfinance.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real repository and model; the only transport is a test-local deferred fake. No HTTP client. */
@OptIn(ExperimentalCoroutinesApi::class)
class MockDemoDiagnosticContractTest {
    @get:Rule val main = MainDispatcherRule()

    private data class Observation(
        val event: AuthDiagnosticEvent,
        val instance: Int,
        val related: Int?,
        val modeAtCall: EntryMode?,
    )

    private class Fixture {
        val api = FakeApi()
        val observations = mutableListOf<Observation>()
        private var modelReference: LoginViewModel? = null
        val diagnostics = object : AuthDiagnosticSink {
            override val enabled = true
            override fun record(event: AuthDiagnosticEvent, instanceId: Int, relatedInstanceId: Int?) {
                observations += Observation(event, instanceId, relatedInstanceId,
                    modelReference?.state?.value?.entryMode)
            }
        }
        val repository = InMemoryAuthRepository(api,
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC), diagnostics)
        val store = ViewModelStore()
        val model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LoginViewModel(repository, { it == "test@example.test" }, diagnostics) as T
            }
        }).get(LoginViewModel::class.java).also { modelReference = it }

        fun assertNoTransportCalls() {
            assertEquals(0, api.loginCalls)
            assertEquals(0, api.logoutCalls)
        }
    }

    private fun verify(block: suspend TestScope.(Fixture) -> Unit) = runTest(main.dispatcher) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.store.clear(); runCurrent() }
    }

    @Test fun demoEntryRecordsRealRepositoryClearBeforePublishingMockStateWithoutTransport() = verify { f ->
        val created = f.observations.single { it.event == AuthDiagnosticEvent.VIEW_MODEL_CREATED }
        assertEquals(f.repository.diagnosticId, created.related)
        val before = f.observations.size
        f.model.exploreMockDemo()

        val entry = f.observations.drop(before).single()
        assertEquals(AuthDiagnosticEvent.LOCAL_SESSION_CLEARED, entry.event)
        assertEquals(f.repository.diagnosticId, entry.instance)
        // Sample state from inside the synchronous sink call, not after exploreMockDemo returns.
        // This fails if clearSession/its event moves after publication of MockDemo.
        assertEquals(EntryMode.SignedOut, entry.modeAtCall)
        assertEquals(EntryMode.MockDemo, f.model.state.value.entryMode)
        assertFalse(f.model.state.value.isSignedIn)
        assertNull(f.model.state.value.authenticatedContentKey)
        assertFalse(f.repository.hasActiveSession())
        f.assertNoTransportCalls()
        // The JVM has no Compose gate; do not manufacture evidence by invoking its effect manually.
        assertFalse(f.observations.any { it.event == AuthDiagnosticEvent.GATE_MOCK_DEMO })
    }

    @Test fun repeatedDemoRequestDoesNotPublishAnotherModeTransition() = verify { f ->
        val modes = mutableListOf<EntryMode>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.model.state.collect { modes += it.entryMode }
        }
        f.model.exploreMockDemo()
        f.model.exploreMockDemo()
        runCurrent()
        assertEquals(listOf(EntryMode.SignedOut, EntryMode.MockDemo), modes)
        assertEquals(2, f.observations.count { it.event == AuthDiagnosticEvent.LOCAL_SESSION_CLEARED })
        assertEquals(EntryMode.MockDemo, f.observations.last().modeAtCall)
        f.assertNoTransportCalls()
    }

    @Test fun loadingLoginRejectsDemoWithoutAnotherClearOrStatePublication() = verify { f ->
        f.model.login("test@example.test", "test-only-input")
        runCurrent()
        assertTrue(f.model.state.value.isLoading)
        val state = f.model.state.value
        val before = f.observations.size
        f.model.exploreMockDemo()
        runCurrent()
        assertSame(state, f.model.state.value)
        assertEquals(before, f.observations.size)
        assertEquals(1, f.api.loginCalls) // Fake invocation only; no network implementation exists.
        assertEquals(0, f.api.logoutCalls)
    }

    @Test fun authenticatedModelRejectsDemoWithoutClearingItsSession() = verify { f ->
        f.model.login("test@example.test", "test-only-input")
        runCurrent()
        f.api.loginReply.complete(ApiResult.Success(fakePayload()))
        runCurrent()
        assertEquals(EntryMode.Authenticated, f.model.state.value.entryMode)
        val state = f.model.state.value
        val before = f.observations.size
        f.model.exploreMockDemo()
        runCurrent()
        assertSame(state, f.model.state.value)
        assertTrue(f.repository.hasActiveSession())
        assertEquals(before, f.observations.size)
        assertEquals(1, f.api.loginCalls)
        assertEquals(0, f.api.logoutCalls)
    }

    @Test fun pendingLogoutRejectsDemoAndPreservesUnconfirmedRevocationResult() = verify { f ->
        f.model.login("test@example.test", "test-only-input")
        runCurrent()
        f.api.loginReply.complete(ApiResult.Success(fakePayload()))
        runCurrent()
        f.model.logout()
        runCurrent()
        assertTrue(f.model.state.value.isLoggingOut)
        val state = f.model.state.value
        val before = f.observations.size
        f.model.exploreMockDemo()
        runCurrent()
        assertSame(state, f.model.state.value)
        assertEquals(before, f.observations.size)
        f.api.logoutReply.complete(ApiResult.Failure(AuthFailure.Unavailable))
        runCurrent()
        assertEquals(EntryMode.SignedOut, f.model.state.value.entryMode)
        assertTrue(f.model.state.value.revocationUnconfirmed)
        assertFalse(f.repository.hasActiveSession())
        assertFalse(f.observations.any { it.event == AuthDiagnosticEvent.GATE_MOCK_DEMO })
        assertEquals(1, f.api.loginCalls)
        assertEquals(1, f.api.logoutCalls)
    }

    private class FakeApi : AuthApi {
        var loginCalls = 0
        var logoutCalls = 0
        val loginReply = CompletableDeferred<ApiResult<AuthPayload>>()
        val logoutReply = CompletableDeferred<ApiResult<Unit>>()
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> {
            loginCalls++
            return loginReply.await() // Do not retain the supplied arguments.
        }
        override suspend fun logout(refreshToken: String): ApiResult<Unit> {
            logoutCalls++
            return logoutReply.await()
        }
    }

    private fun fakePayload() = AuthPayload("test-only-access", "r".repeat(86),
        Instant.parse("2030-01-01T00:15:00Z"), Instant.parse("2030-01-08T00:00:00Z"),
        AuthUser(1, "Test user", "test@example.test", "PHP", listOf("User")))
}
