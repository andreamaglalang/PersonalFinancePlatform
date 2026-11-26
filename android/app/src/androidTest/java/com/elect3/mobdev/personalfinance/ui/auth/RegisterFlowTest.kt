package com.elect3.mobdev.personalfinance.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elect3.mobdev.personalfinance.data.auth.AuthRepository
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.remote.AuthFailure
import com.elect3.mobdev.personalfinance.ui.app.OwliEntryGate
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake repository only: no HTTP. Register never signs in; failures clear both password fields. */
class RegisterFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val strong = "Fixture-Pass-123!"

    private class Repository(var result: AuthResult) : AuthRepository {
        var registrations = 0
        override suspend fun register(fullName: String, email: String, password: String, confirmPassword: String): AuthResult {
            registrations++; return result
        }
        override suspend fun login(email: String, password: String) = AuthResult.Failure(AuthFailure.InvalidCredentials)
        override suspend fun logout() = AuthResult.Success
        override fun hasActiveSession() = false
        override fun clearSession() = Unit
    }

    private fun show(repository: Repository): LoginViewModel {
        val model = LoginViewModel(repository)
        compose.setContent { PersonalFinanceTheme { OwliEntryGate(model) { Text("demo") } } }
        compose.onNodeWithText("New to OWLI? Create account").performScrollTo().performClick()
        compose.onNodeWithText("Full name").performTextInput("Test Member")
        compose.onNodeWithText("Email address").performTextInput("member@example.test")
        compose.onNodeWithText("Password").performTextInput(strong)
        compose.onNodeWithText("Confirm password").performTextInput(strong)
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.waitForIdle()
        return model
    }

    @Test fun successReturnsToLoginWithNoticeAndEmailWithoutSigningIn() {
        val repository = Repository(AuthResult.Success)
        val model = show(repository)
        compose.onNodeWithText("Your OWLI account is ready. Sign in with your email and password.").assertExists()
        compose.onNodeWithText("member@example.test").assertExists()
        assertEquals(1, repository.registrations)
        assertFalse(model.state.value.isSignedIn)
    }

    @Test fun duplicateShowsMessageAndClearsPasswords() {
        show(Repository(AuthResult.Failure(AuthFailure.AlreadyRegistered)))
        compose.onNodeWithText("That email is already registered. Sign in instead.").assertExists()
        compose.onAllNodes(hasText(strong, substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Test Member").assertExists()
    }
}
