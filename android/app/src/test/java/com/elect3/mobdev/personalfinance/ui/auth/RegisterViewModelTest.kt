package com.elect3.mobdev.personalfinance.ui.auth

import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Registration simulation only; never calls HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeRepository(var result: AuthResult = AuthResult.Success) : AuthRepository {
        val calls = mutableListOf<List<String>>()
        var sessionTouched = false
        override suspend fun register(fullName: String, email: String, password: String, confirmPassword: String): AuthResult {
            calls += listOf(fullName, email, password, confirmPassword); return result
        }
        override suspend fun login(email: String, password: String): AuthResult { sessionTouched = true; return AuthResult.Success }
        override suspend fun logout(): AuthResult = AuthResult.Success
        override fun hasActiveSession() = false
        override fun clearSession() { sessionTouched = true }
    }

    private val strong = "Fixture-Pass-123!"
    private fun model(repository: AuthRepository) = RegisterViewModel(repository, isValidEmail = { it.contains('@') })

    @Test fun clientChecksMirrorServerAndSendNothing() = runTest(main.dispatcher) {
        val repository = FakeRepository()
        val vm = model(repository)
        fun errors() = vm.state.value.inputErrors
        vm.register(" ", "a@b.test", strong, strong); assertEquals(setOf(RegisterInputError.FullName), errors())
        vm.register("A", "not-an-email", strong, strong); assertEquals(setOf(RegisterInputError.Email), errors())
        vm.register("A", "a@b.test", "short", "short"); assertEquals(setOf(RegisterInputError.Password), errors())
        vm.register("A", "a@b.test", strong, "$strong-x"); assertEquals(setOf(RegisterInputError.PasswordMismatch), errors())
        vm.register("x".repeat(101), "a@b.test", strong, strong); assertEquals(setOf(RegisterInputError.FullName), errors())
        runCurrent()
        assertTrue(repository.calls.isEmpty())
    }

    /** Reported defect: 12 matching characters without an uppercase letter or symbol reached the server. */
    @Test fun twelveMatchingCharactersMissingRulesAreCaughtOnDevice() = runTest(main.dispatcher) {
        val repository = FakeRepository()
        val vm = model(repository)
        vm.register("A", "a@b.test", "abcdefghijk1", "abcdefghijk1")
        runCurrent()
        assertEquals(setOf(RegisterInputError.Password), vm.state.value.inputErrors)
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun everyFailedCheckIsReportedAtOnce() = runTest(main.dispatcher) {
        val vm = model(FakeRepository())
        vm.register("", "nope", "abc", "abd")
        assertEquals(setOf(RegisterInputError.FullName, RegisterInputError.Email, RegisterInputError.Password,
            RegisterInputError.PasswordMismatch), vm.state.value.inputErrors)
    }

    @Test fun successEmitsTrimmedEmailForLoginWithoutSigningIn() = runTest(main.dispatcher) {
        val repository = FakeRepository()
        val vm = model(repository)
        vm.register("  Test Member ", " member@example.test ", strong, strong)
        assertTrue(vm.state.value.isLoading)
        runCurrent()
        assertEquals(listOf("Test Member", "member@example.test", strong, strong), repository.calls.single())
        assertEquals("member@example.test", vm.state.value.registeredEmail)
        assertFalse(repository.sessionTouched)
        vm.reset()
        assertEquals(RegisterUiState(), vm.state.value)
    }

    @Test fun serverFailuresAreReportedAndStateHoldsNoPassword() = runTest(main.dispatcher) {
        for (reason in listOf(AuthFailure.InvalidInput, AuthFailure.AlreadyRegistered, AuthFailure.RateLimited, AuthFailure.ConnectionFailed)) {
            val vm = model(FakeRepository(AuthResult.Failure(reason)))
            vm.register("Test Member", "member@example.test", strong, strong)
            runCurrent()
            assertEquals(reason, vm.state.value.failure)
            assertNull(vm.state.value.registeredEmail)
            assertFalse(vm.state.value.toString().contains(strong))
        }
    }

    @Test fun failureMessagesMirrorMvc() {
        assertEquals(com.elect3.mobdev.personalfinance.R.string.register_error_duplicate, registerFailureResource(AuthFailure.AlreadyRegistered))
        assertEquals(com.elect3.mobdev.personalfinance.R.string.auth_rate_limited, registerFailureResource(AuthFailure.RateLimited))
        assertEquals(com.elect3.mobdev.personalfinance.R.string.register_error_invalid, registerFailureResource(AuthFailure.InvalidInput))
        for (other in listOf(AuthFailure.ConnectionFailed, AuthFailure.Timeout, AuthFailure.Unavailable, AuthFailure.InvalidResponse))
            assertEquals(com.elect3.mobdev.personalfinance.R.string.register_error_unavailable, registerFailureResource(other))
    }
}
