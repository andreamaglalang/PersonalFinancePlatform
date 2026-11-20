package com.elect3.mobdev.personalfinance.ui.account

import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.data.local.AppearancePreference
import com.elect3.mobdev.personalfinance.data.local.AppearanceStore
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountLogicTest {
    private val expiry = Instant.parse("2030-01-01T00:15:00Z")
    private fun auth(name: String, mail: String, currency: String = "PHP", logout: ApiResult<Unit> = ApiResult.Success(Unit)) =
        InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String): ApiResult<AuthPayload> = ApiResult.Success(
                AuthPayload("access", "refresh", expiry, expiry.plusSeconds(1000), AuthUser(9, name, mail, currency, listOf("User"))))
            override suspend fun logout(refreshToken: String): ApiResult<Unit> = logout
        }, MutableAuthClock(expiry.minusSeconds(60)))

    @Test fun profileComesFromTheSignInResponseAndEndsWithTheSession() = runTest {
        val repo = auth("  Andrea Reyes ", "andrea@example.test")
        assertNull(repo.accountProfile())
        repo.login("andrea@example.test", "fixture-only")
        assertEquals(AccountProfile("Andrea Reyes", "andrea@example.test", "PHP"), repo.accountProfile())
        repo.logout()
        assertNull(repo.accountProfile()) // never shown after logout
    }

    @Test fun missingProfileFieldsStayMissing() = runTest {
        val repo = auth(" ", "", currency = "pesos")
        repo.login("x@example.test", "fixture-only")
        assertEquals(AccountProfile(null, null, null), repo.accountProfile())
        assertFalse(repo.accountProfile().toString().contains("access")) // no token in the display model
    }

    @Test fun failedServerRevocationStillClearsTheLocalSession() = runTest {
        val repo = auth("A B", "a@example.test", logout = ApiResult.Failure(AuthFailure.Unavailable))
        repo.login("a@example.test", "fixture-only")
        assertTrue(repo.logout() is AuthResult.Failure)
        assertFalse(repo.hasActiveSession()); assertNull(repo.accountProfile())
    }

    @Test fun initialsUseOnlyARealName() {
        assertEquals("AR", initials("Andrea  Reyes"))
        assertEquals("C", initials("cher"))
        assertEquals("AM", initials("Andrea Maglalang Cruz"))
        assertEquals("", initials(null)); assertEquals("", initials("   "))
    }

    @Test fun appearanceDefaultsToSystemAndRemembersTheChoice() {
        val store = AppearanceStore(null)
        assertEquals(AppearancePreference.System, store.preference.value)
        store.set(AppearancePreference.Dark)
        assertEquals(AppearancePreference.Dark, store.preference.value)
    }
}
