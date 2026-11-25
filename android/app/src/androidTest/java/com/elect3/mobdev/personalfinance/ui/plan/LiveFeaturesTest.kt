package com.elect3.mobdev.personalfinance.ui.plan

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.account.LiveAccountEditor
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Physical-device Compose controls, isolated HTTP doubles. These tests do not claim live SQL persistence. */
class LiveFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun repository(get: (FinanceHttpRequest) -> FinanceHttpResponse, write: (FinanceWriteRequest) -> FinanceHttpResponse = { error("No write expected") }): LiveAccountPlanRepository {
        val now = Instant.now()
        val auth = InMemoryAuthRepository(object : AuthApi {
            override suspend fun login(email: String, password: String) = ApiResult.Success(AuthPayload("fixture-access", "fixture-refresh", now.plusSeconds(900), now.plusSeconds(9000), AuthUser(42, "Original", "fixture@example.test", "PHP", listOf("User"))))
            override suspend fun logout(refreshToken: String) = ApiResult.Success(Unit)
        })
        runBlocking { auth.login("fixture@example.test", "fixture-only") }
        return LiveAccountPlanRepository(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { get(it) }, FinanceWriteEngine { write(it) })
    }
    @Test fun liveGoalsEmptyStateNeverContainsDemoRecords() {
        val repo = repository({ FinanceHttpResponse(200, "{\"items\":[],\"page\":1,\"pageSize\":50,\"totalCount\":0}") })
        compose.setContent { PersonalFinanceTheme { LivePlanSection(repo, false, null) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("No savings goals yet.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Emergency Fund").assertDoesNotExist()
        compose.onNodeWithText("Add goal").performClick()
        compose.onNodeWithText("Target amount").assertIsDisplayed()
    }
    @Test fun unavailableBillsDoNotPretendToBeEmptyOrEnableCreate() {
        val repo = repository({ FinanceHttpResponse(500, "") })
        compose.setContent { PersonalFinanceTheme { LivePlanSection(repo, true, null) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Cannot load", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add bill").assertIsNotEnabled()
        compose.onNodeWithText("No recurring bills yet.").assertDoesNotExist()
        compose.onNodeWithText("Electricity").assertDoesNotExist()
    }
    @Test fun profileLoadsRealProjectionAndSavesOnlySupportedFields() {
        val profile = "{\"id\":42,\"fullName\":\"Original\",\"email\":\"fixture@example.test\",\"baseCurrency\":\"PHP\",\"version\":\"${"A".repeat(64)}\"}"
        val repo = repository({ FinanceHttpResponse(200, profile) }, { request ->
            val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
            assertEquals(setOf("fullName", "version"), body.keySet()); assertEquals("Updated", body.get("fullName").asString)
            FinanceHttpResponse(200, profile.replace("Original", "Updated"))
        })
        compose.setContent { PersonalFinanceTheme { LiveAccountEditor(repo, false, {}, {}, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Original").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Display name").performTextReplacement("Updated")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Profile saved.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun passwordValidationDoesNotSendInvalidValues() {
        val repo = repository({ error("No read expected") })
        compose.setContent { PersonalFinanceTheme { LiveAccountEditor(repo, true, {}, {}, {}) } }
        compose.onAllNodesWithText("Change password").onLast().performClick()
        compose.onNodeWithText("Check the passwords and confirmation.").assertIsDisplayed()
    }
}
