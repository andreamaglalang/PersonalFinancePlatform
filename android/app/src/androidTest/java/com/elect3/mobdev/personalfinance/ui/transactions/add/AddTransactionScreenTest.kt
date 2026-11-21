package com.elect3.mobdev.personalfinance.ui.transactions.add

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Compile-only in UI-2. All dependencies are local fakes; no HTTP implementation is created. */
class AddTransactionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: AddTransactionViewModel
    private val owner = ViewModelStore()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show(workflow: FormWorkflow = FormWorkflow.MockDemo, dark: Boolean = false,
        repository: CategoryRepository = DemoCategoryRepository(), large: Boolean = false) {
        compose.runOnUiThread {
            model = AddTransactionViewModel(repository, workflow, if (workflow == FormWorkflow.MockDemo) "PHP" else "USD")
            owner.put("form", model)
        }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(density.density, if (large) 2f else density.fontScale)) {
                PersonalFinanceTheme {
                    val state = model.state.collectAsStateWithLifecycle().value
                    AddTransactionScreen(model, state)
                }
            }
        }
    }
    private fun field(id: Int) = compose.onNode(hasSetTextAction() and hasText(text(id)))
    private fun choose(name: String) {
        compose.onNodeWithText(text(R.string.form_category) + ": " + text(R.string.form_choose_category)).performScrollTo().performClick()
        field(R.string.form_search).performTextInput(name)
        compose.onNode(hasText(name) and hasClickAction() and !hasSetTextAction()).performClick()
    }
    @After fun cleanup() { compose.runOnUiThread { owner.clear() } }

    @Test fun demoModeSearchSelectionAndPreviewAreExplicitlyNonPersistent() {
        show()
        compose.onNode(hasText(text(R.string.transaction_income)) and hasClickAction()).performClick()
        field(R.string.form_amount).performTextInput("12.34")
        choose("Salary")
        compose.onNodeWithText(text(R.string.form_preview)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { model.state.value.completed }
        compose.onAllNodesWithText(text(R.string.form_demo_complete)).onFirst().assertIsDisplayed()
        assertTrue(model.state.value.completed)
    }
    @Test fun authenticatedSaveStaysDisabledAndUsesAccountCurrency() {
        show(FormWorkflow.Authenticated)
        field(R.string.form_amount).performTextInput("12.34")
        choose("Food")
        compose.onNodeWithText(text(R.string.form_save_unavailable_short)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.form_currency, "USD")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_save_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertDoesNotExist()
    }
    @Test fun dirtyBackConfirmationKeepsOrErasesDraft() {
        show()
        field(R.string.form_amount).performTextInput("8")
        compose.onNodeWithText(text(R.string.form_back)).performClick()
        compose.onNodeWithText(text(R.string.form_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_keep_editing)).performClick()
        assertEquals("8", model.state.value.amount)
        compose.onNodeWithText(text(R.string.form_back)).performClick()
        compose.onNodeWithText(text(R.string.form_discard)).performClick()
        assertTrue(model.state.value.closeRequested)
        assertEquals("", model.state.value.amount)
    }
    @Test fun categoryLoadingErrorRetryAndEmptyAreVisible() {
        val reply = CompletableDeferred<FinanceResult<CategoryPage>>()
        var calls = 0
        val fake = object : CategoryRepository {
            override suspend fun list(query: CategoryQuery): FinanceResult<CategoryPage> {
                calls++
                return if (calls == 1) reply.await() else FinanceResult.Success(CategoryPage(emptyList(), 1, 50, 0))
            }
        }
        show(repository = fake)
        compose.onNodeWithText(text(R.string.form_categories_loading)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reply.complete(FinanceResult.Failure(FinanceFailure.Unavailable)) }
        compose.onNodeWithText(text(R.string.form_category) + ": " + text(R.string.form_choose_category)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.transactions_retry)).performClick()
        compose.onNodeWithText(text(R.string.form_categories_empty)).assertIsDisplayed()
    }
    @Test fun lightLargeTextRetainsAccessibleFormControls() {
        show(large = true)
        compose.onNodeWithText(text(R.string.form_title)).assertIsDisplayed()
        field(R.string.form_amount).performScrollTo().assertIsDisplayed()
        field(R.string.form_description).performScrollTo().performTextInput("x".repeat(201))
        compose.onNodeWithText(text(R.string.form_description_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_preview)).assertIsNotEnabled()
    }
    @Test fun darkThemeDatePickerAndDescriptionRemainInteractive() {
        show(dark = true)
        compose.onNodeWithText(text(R.string.form_date), substring = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.form_cancel)).performClick()
        field(R.string.form_description).performScrollTo().performTextInput("Offline note")
        assertEquals("Offline note", model.state.value.description)
        compose.onNodeWithText(text(R.string.form_demo_notice)).assertIsDisplayed()
    }
}
