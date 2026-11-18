package com.elect3.mobdev.personalfinance.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.auth.AuthDiagnosticEvent
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.data.remote.ApiResult
import com.elect3.mobdev.personalfinance.data.remote.AuthApi
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.data.remote.AuthPayload
import com.elect3.mobdev.personalfinance.data.remote.AuthUser
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.elect3.mobdev.personalfinance.testutil.RecordingAuthDiagnostics
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** M1b: the session watcher schedules proactive refresh; failures never extend the session. Fakes only. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelRefreshTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")

    private inner class FakeApi(val refreshResult: () -> ApiResult<AuthPayload>) : AuthApi {
        val refreshTokens = mutableListOf<String>()
        var logoutCalls = 0
        override suspend fun login(email: String, password: String): ApiResult<AuthPayload> =
            ApiResult.Success(payload("access-old", 'a', expiry))
        override suspend fun logout(refreshToken: String): ApiResult<Unit> { logoutCalls++; return ApiResult.Success(Unit) }
        override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> {
            refreshTokens += refreshToken
            return refreshResult()
        }
    }

    private fun payload(access: String, refresh: Char, expires: Instant) = AuthPayload(access, refresh.toString().repeat(86),
        expires, expires.plusSeconds(3600), AuthUser(1, "Test user", "demo@example.test", "PHP", listOf("User")))

    private fun model(api: AuthApi, clock: MutableAuthClock, diagnostics: RecordingAuthDiagnostics): LoginViewModel {
        val repository = InMemoryAuthRepository(api, clock, diagnostics)
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LoginViewModel(repository, isValidEmail = { true }, diagnostics = diagnostics) as T
            }
        }).get(LoginViewModel::class.java)
    }

    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally { stores.forEach { it.clear() }; runCurrent() }
    }

    @Test fun watcherRefreshesOnceInsideTheWindowAndKeepsTheUserSignedInPastTheOldExpiry() = verify {
        val clock = MutableAuthClock(expiry.minusSeconds(300))
        val diagnostics = RecordingAuthDiagnostics()
        val api = FakeApi { ApiResult.Success(payload("access-new", 'b', expiry.plusSeconds(900))) }
        val vm = model(api, clock, diagnostics)
        vm.login("demo@example.test", "test-only-input"); runCurrent()
        val key = vm.state.value.authenticatedContentKey
        advanceTimeBy(3000); runCurrent()
        assertTrue("Not due yet", api.refreshTokens.isEmpty())
        clock.now = expiry.minusSeconds(90)
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, api.refreshTokens.size)
        clock.now = expiry.plusSeconds(60)
        advanceTimeBy(3000); runCurrent()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
        assertEquals("Rotation must not rebuild account content", key, vm.state.value.authenticatedContentKey)
        assertEquals(1, api.refreshTokens.size)
        assertEquals(0, api.logoutCalls)
        assertTrue(AuthDiagnosticEvent.REFRESH_SUCCEEDED in diagnostics.events)
    }

    @Test fun rejectedRefreshSignsOutAtTheOriginalExpiryWithoutRetryOrLogout() = verify {
        val clock = MutableAuthClock(expiry.minusSeconds(90))
        val diagnostics = RecordingAuthDiagnostics()
        val api = FakeApi { ApiResult.Failure(AuthFailure.SessionExpired) }
        val vm = model(api, clock, diagnostics)
        vm.login("demo@example.test", "test-only-input"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
        assertEquals(1, api.refreshTokens.size)
        clock.now = expiry
        advanceTimeBy(1000); runCurrent()
        assertEquals(EntryMode.SignedOut, vm.state.value.entryMode)
        assertEquals(AuthFailure.SessionExpired, vm.state.value.error)
        assertEquals(1, api.refreshTokens.size)
        assertEquals(0, api.logoutCalls)
    }

    @Test fun timeoutIsNeverRetriedWithTheSameToken() = verify {
        val clock = MutableAuthClock(expiry.minusSeconds(100))
        val api = FakeApi { ApiResult.Failure(AuthFailure.Timeout) }
        val vm = model(api, clock, RecordingAuthDiagnostics())
        vm.login("demo@example.test", "test-only-input"); runCurrent()
        repeat(5) { clock.now = clock.now.plusSeconds(10); advanceTimeBy(1000); runCurrent() }
        assertEquals(1, api.refreshTokens.size)
        assertEquals(EntryMode.Authenticated, vm.state.value.entryMode)
    }

    @Test fun mockDemoNeverRefreshes() = verify {
        val clock = MutableAuthClock(expiry.minusSeconds(30))
        val diagnostics = RecordingAuthDiagnostics()
        val api = FakeApi { error("Demo must never refresh") }
        val vm = model(api, clock, diagnostics)
        vm.exploreMockDemo(); runCurrent()
        assertEquals(EntryMode.MockDemo, vm.state.value.entryMode)
        advanceTimeBy(5000); runCurrent()
        assertTrue(api.refreshTokens.isEmpty())
        assertFalse(diagnostics.events.any { it.name.startsWith("REFRESH_") })
    }
}
