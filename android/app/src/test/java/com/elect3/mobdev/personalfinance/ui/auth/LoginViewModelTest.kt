package com.elect3.mobdev.personalfinance.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticSink
import com.elect3.mobdev.personalfinance.data.auth.NoAuthDiagnostics
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecyclePhase
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecycleSnapshot
import com.elect3.mobdev.personalfinance.data.auth.ActivityLifecycleDiagnosticRecord
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.time.Instant
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Authentication simulation exists only in this local test source set; never calls HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()

    private fun model(
        repository: AuthRepository,
        diagnostics: AuthDiagnosticSink = NoAuthDiagnostics,
        store: ViewModelStore = ViewModelStore().also { stores += it },
    ): LoginViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LoginViewModel(repository, isValidEmail = { it == "demo@example.test" }, diagnostics = diagnostics) as T
            }
        }
        return ViewModelProvider(store, factory).get(LoginViewModel::class.java)
    }

    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally { stores.forEach { it.clear() }; runCurrent() }
    }

    private fun TestScope.authenticate(vm: LoginViewModel, repository: ControlledAuthRepository) {
        vm.login("demo@example.test", "test-only-input")
        runCurrent()
        repository.logins.last().complete(AuthResult.Success)
        runCurrent()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
    }

    @Test fun freshModelStartsAtLoginWithoutCallingRepository() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertNull(vm.state.value.authenticatedContentKey)
        assertTrue(repository.logins.isEmpty())
        assertTrue(repository.logouts.isEmpty())
    }

    @Test fun authenticatedEntryWaitsForSuccessAndActiveRepositorySession() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        vm.login("demo@example.test", "test-only-input")
        vm.login("demo@example.test", "test-only-input")
        runCurrent()
        assertEquals(1, repository.logins.size)
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertTrue(vm.state.value.isLoading)
        repository.logins.single().complete(AuthResult.Success)
        runCurrent()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
        assertFalse(vm.state.value.isExploringDemo)
        assertNotNull(vm.state.value.authenticatedContentKey)
        vm.cancelLogin() // Login route disposal must preserve the successful session.
        assertTrue(repository.active)
    }

    @Test fun successWithoutActiveSessionCannotOpenAuthenticatedContent() = verify {
        val repository = ControlledAuthRepository().apply { grantSession = false }
        val vm = model(repository)
        vm.login("demo@example.test", "test-only-input")
        runCurrent()
        repository.logins.single().complete(AuthResult.Success)
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(AuthFailure.SessionExpired, vm.state.value.error)
        assertNull(vm.state.value.authenticatedContentKey)
    }

    @Test fun failedLoginStaysUnauthenticatedAndCanRetry() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        vm.login("demo@example.test", "test-only-input")
        runCurrent()
        repository.logins.single().complete(AuthResult.Failure(AuthFailure.InvalidCredentials))
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(AuthFailure.InvalidCredentials, vm.state.value.error)
        authenticate(vm, repository)
    }

    @Test fun logoutImmediatelyClosesAuthenticatedGateAndWaitsForRevocation() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        vm.logout()
        vm.logout()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertNull(vm.state.value.authenticatedContentKey)
        assertTrue(vm.state.value.isLoggingOut)
        runCurrent()
        assertFalse(repository.active)
        assertEquals(1, repository.logouts.size)
        vm.cancelLogin() // Route recreation must not cancel the pending logout.
        vm.exploreMockDemo()
        assertTrue(vm.state.value.isLoggingOut)
        assertFalse(repository.logouts.single().isCancelled)
        repository.logouts.single().complete(AuthResult.Success)
        runCurrent()
        assertEquals(LoginUiState(), vm.state.value)
        assertFalse(repository.active)
    }

    @Test fun logoutFailureReturnsToLoginWithRevocationUnconfirmed() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        vm.logout()
        runCurrent()
        repository.logouts.single().complete(AuthResult.Failure(AuthFailure.Unavailable))
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertTrue(vm.state.value.revocationUnconfirmed)
        assertFalse(vm.state.value.isLoading)
        assertFalse(repository.active)
        vm.returnToLogin()
        vm.sessionExpired()
        assertTrue(vm.state.value.revocationUnconfirmed)
    }

    @Test fun logoutExceptionAlsoClearsLocalStateAndDoesNotClaimRevocation() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        vm.logout()
        runCurrent()
        repository.logouts.single().completeExceptionally(IllegalStateException("test-only failure"))
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertTrue(vm.state.value.revocationUnconfirmed)
        assertFalse(repository.active)
    }

    @Test fun expiryClosesAuthenticatedGateAndClearsSession() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        repository.active = false
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(AuthFailure.SessionExpired, vm.state.value.error)
        assertNull(vm.state.value.authenticatedContentKey)
        assertFalse(repository.active)
    }

    @Test fun nextLoginGetsFreshNavigationIdentityAfterLogout() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        val previousKey = vm.state.value.authenticatedContentKey
        vm.logout()
        runCurrent()
        repository.logouts.single().complete(AuthResult.Success)
        runCurrent()
        authenticate(vm, repository)
        assertNotEquals(previousKey, vm.state.value.authenticatedContentKey)
    }

    @Test fun mockDemoNeverAuthenticatesOrCallsLoginOrLogout() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        vm.exploreMockDemo()
        vm.login("demo@example.test", "test-only-input")
        vm.logout()
        vm.sessionExpired()
        runCurrent()
        assertEquals(EntryMode.MockDemo, vm.state.value.entryMode)
        assertFalse(vm.state.value.isSignedIn)
        assertNull(vm.state.value.authenticatedContentKey)
        assertFalse(repository.active)
        assertTrue(repository.logins.isEmpty())
        assertTrue(repository.logouts.isEmpty())
        vm.returnToLogin()
        assertEquals(LoginUiState(), vm.state.value)
    }

    @Test fun authenticatedSessionCannotBeSilentlyConvertedToMockDemo() = verify {
        val repository = ControlledAuthRepository()
        val vm = model(repository)
        authenticate(vm, repository)
        val key = vm.state.value.authenticatedContentKey
        vm.exploreMockDemo()
        vm.returnToLogin()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
        assertEquals(key, vm.state.value.authenticatedContentKey)
        assertTrue(repository.active)
    }

    @Test fun retainedStoreReusesAuthenticatedModelButFreshStoreStartsSignedOut() = verify {
        val repository = ControlledAuthRepository()
        val diagnostics = RecordingAuthDiagnostics()
        val retainedStore = ViewModelStore().also { stores += it }
        val original = model(repository, diagnostics, retainedStore)
        authenticate(original, repository)
        val key = original.state.value.authenticatedContentKey
        val retained = model(ControlledAuthRepository(), diagnostics, retainedStore)
        assertSame(original, retained)
        assertEquals(key, retained.state.value.authenticatedContentKey)
        assertEquals(1, diagnostics.events.count { it == AuthDiagnosticEvent.VIEW_MODEL_CREATED })
        retainedStore.clear()
        runCurrent()
        assertFalse(repository.active)
        assertTrue(AuthDiagnosticEvent.VIEW_MODEL_CLEARED in diagnostics.events)
        val fresh = model(ControlledAuthRepository(), diagnostics)
        assertNotEquals(original.diagnosticId, fresh.diagnosticId)
        assertEquals(EntryMode.SignedOut, fresh.state.value.entryMode)
        assertNull(fresh.state.value.authenticatedContentKey)
    }

    @Test fun gateObservationAndLoginRouteDisposalDoNotResetAuthenticatedState() = verify {
        val repository = ControlledAuthRepository()
        val diagnostics = RecordingAuthDiagnostics()
        val vm = model(repository, diagnostics)
        vm.recordGateTransition(vm.state.value.entryMode)
        authenticate(vm, repository)
        val key = vm.state.value.authenticatedContentKey
        vm.recordGateTransition(vm.state.value.entryMode)
        vm.cancelLogin()
        assertTrue(repository.active)
        assertEquals(key, vm.state.value.authenticatedContentKey)
        vm.logout()
        vm.recordGateTransition(vm.state.value.entryMode)
        runCurrent()
        repository.logouts.single().complete(AuthResult.Success)
        runCurrent()
        assertEquals(listOf(AuthDiagnosticEvent.GATE_SIGNED_OUT, AuthDiagnosticEvent.GATE_AUTHENTICATED,
            AuthDiagnosticEvent.GATE_SIGNED_OUT), diagnostics.events.filter { it.name.startsWith("GATE_") })
        assertTrue(AuthDiagnosticEvent.LOGIN_SUCCEEDED in diagnostics.events)
        assertEquals(1, diagnostics.events.count { it == AuthDiagnosticEvent.EXPLICIT_LOGOUT_INITIATED })
        assertFalse(AuthDiagnosticEvent.LOGOUT_HTTP_204_ACKNOWLEDGED in diagnostics.events)
    }

    @Test fun lifecycleObservationDoesNotClearSessionOrAlterAuthenticationPolicy() = verify {
        val repository = ControlledAuthRepository()
        val diagnostics = RecordingAuthDiagnostics()
        val vm = model(repository, diagnostics)
        authenticate(vm, repository)
        val originalState = vm.state.value
        val snapshot = ActivityLifecycleSnapshot(false, false, 42, true, false, 0x10200000, vm.diagnosticId)
        for (phase in ActivityLifecyclePhase.entries) {
            diagnostics.recordLifecycle(ActivityLifecycleDiagnosticRecord(phase, 5, snapshot))
        }
        vm.cancelLogin()
        assertSame(originalState, vm.state.value)
        assertTrue(repository.active)
        assertTrue(repository.logouts.isEmpty())
        assertEquals(ActivityLifecyclePhase.entries.size, diagnostics.lifecycleRecords.size)
        assertFalse(AuthDiagnosticEvent.EXPLICIT_LOGOUT_INITIATED in diagnostics.events)
    }

    @Test fun demoBackIsRecordedWithoutAddingLoginLogoutOrSessionRestoration() = verify {
        val repository = ControlledAuthRepository()
        val diagnostics = RecordingAuthDiagnostics()
        val vm = model(repository, diagnostics)
        vm.exploreMockDemo()
        vm.returnToLoginFromBack()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(1, diagnostics.events.count { it == AuthDiagnosticEvent.BACK_DEMO_TO_LOGIN })
        assertTrue(repository.logins.isEmpty())
        assertTrue(repository.logouts.isEmpty())
        assertFalse(repository.active)
    }

    @Test fun clockExpiryWatcherSignsOutWithoutSendingLogoutOrRestoringTheGate() = verify {
        val expiry = Instant.parse("2030-01-01T00:15:00Z")
        val clock = MutableAuthClock(expiry.minusSeconds(1))
        val diagnostics = RecordingAuthDiagnostics()
        var logoutCalls = 0
        val api = object : AuthApi {
            override suspend fun login(email: String, password: String) = ApiResult.Success(AuthPayload(
                "test-only-access", "r".repeat(86), expiry, expiry.plusSeconds(3600),
                AuthUser(1, "Test user", "demo@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> {
                logoutCalls++
                return ApiResult.Success(Unit)
            }
        }
        val repository = InMemoryAuthRepository(api, clock, diagnostics)
        val vm = model(repository, diagnostics)
        vm.login("demo@example.test", "test-only-input")
        runCurrent()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
        clock.now = expiry
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(AuthFailure.SessionExpired, vm.state.value.error)
        assertNull(vm.state.value.authenticatedContentKey)
        assertFalse(vm.state.value.revocationUnconfirmed) // Expiry is not a logout acknowledgement.
        assertEquals(0, logoutCalls)
        assertTrue(AuthDiagnosticEvent.ACCESS_TOKEN_EXPIRED in diagnostics.events)
        assertTrue(AuthDiagnosticEvent.SESSION_RESET in diagnostics.events)
        assertFalse(AuthDiagnosticEvent.EXPLICIT_LOGOUT_INITIATED in diagnostics.events)
        vm.returnToLogin()
        vm.cancelLogin()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
    }

    @Test fun clearingOwnerDuringLogoutRecordsInterruptionAndCannotRestoreAuthentication() = verify {
        val repository = ControlledAuthRepository()
        val diagnostics = RecordingAuthDiagnostics()
        val store = ViewModelStore().also { stores += it }
        val vm = model(repository, diagnostics, store)
        authenticate(vm, repository)
        vm.logout()
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertFalse(repository.active)
        assertTrue(AuthDiagnosticEvent.LOGOUT_REQUEST_INTERRUPTED in diagnostics.events)
        assertTrue(AuthDiagnosticEvent.SERVER_REVOCATION_NOT_INDEPENDENTLY_VERIFIED in diagnostics.events)
    }

    private class ControlledAuthRepository : AuthRepository {
        var active = false
        var grantSession = true
        val logins = mutableListOf<CompletableDeferred<AuthResult>>()
        val logouts = mutableListOf<CompletableDeferred<AuthResult>>()

        override suspend fun login(email: String, password: String): AuthResult {
            val result = CompletableDeferred<AuthResult>().also { logins += it }.await()
            if (result == AuthResult.Success) active = grantSession
            return result
        }

        override suspend fun logout(): AuthResult {
            active = false
            return CompletableDeferred<AuthResult>().also { logouts += it }.await()
        }

        override fun hasActiveSession() = active
        override fun clearSession() { active = false }
    }
}
