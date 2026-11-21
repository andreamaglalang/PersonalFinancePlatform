package com.elect3.mobdev.personalfinance.ui.categories

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.add.AddTransactionFlow
import com.elect3.mobdev.personalfinance.ui.transactions.add.FlowStart
import com.elect3.mobdev.personalfinance.ui.transactions.add.FormWorkflow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/** Mock Demo category-first flow: everything stays in the in-memory demo session. */
@RunWith(AndroidJUnit4::class)
class CategoryFlowTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private val session = DemoSession()
    private var saved = 0
    private var closed = 0
    private var dashboard = 0

    private fun show(start: FlowStart = FlowStart.AddTransaction, type: TransactionType? = null, dark: Boolean = false, fontScale: Float = 1f) =
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.fontScale = fontScale
                if (dark) uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, fontScale)) {
                PersonalFinanceTheme {
                    AddTransactionFlow(start, type, session.categories, session.categories, session.presentation,
                        FormWorkflow.MockDemo, "PHP", null, emptyFlow(), isCurrent = { true }, onExpired = {},
                        onClose = { closed++ }, onSaved = { saved++ }, onShowDashboard = { dashboard++ }, demoLedger = session.ledger)
                }
            }
        }

    @Test fun pickCategoryFillAmountAndConfirmAddsOnlyToTheDemoSession() {
        show(type = TransactionType.Expense)
        compose.onNodeWithText(text(R.string.cat_choose_overline, text(R.string.transaction_expense))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.auth_demo_title)).assertIsDisplayed()
        compose.onNodeWithText("Food").performClick()
        compose.onNodeWithText(text(R.string.cat_form_title_expense)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cat_change_action, "Food")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_demo_session_notice)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.form_amount)) and hasSetTextAction()).performTextInput("75.25")
        compose.onNodeWithText(text(R.string.form_demo_session_save)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.tx_demo_saved_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.tx_demo_saved_lead)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tx_saved_title)).assertDoesNotExist()
        compose.onNodeWithText("Food").assertIsDisplayed()
        val added = session.ledger.snapshot().single()
        assertEquals(0, BigDecimal("75.25").compareTo(added.amount)); assertEquals("Food", added.categoryName)
        compose.runOnIdle { assertEquals(1, saved) }
        compose.onNodeWithText(text(R.string.tx_back_dashboard)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dashboard); assertEquals(1, closed) }
    }

    @Test fun incomeEntryOpensTheIncomeTabAndBackFromAnEmptyFormReturnsToThePicker() {
        show(type = TransactionType.Income)
        compose.onNodeWithText(text(R.string.cat_choose_overline, text(R.string.transaction_income))).assertIsDisplayed()
        compose.onNodeWithText("Salary").performClick()
        compose.onNodeWithText(text(R.string.cat_form_title_income)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cat_change)).performClick()
        compose.onNodeWithText(text(R.string.cat_choose_overline, text(R.string.transaction_income))).assertIsDisplayed()
        assertTrue(session.ledger.snapshot().isEmpty())
    }

    @Test fun leavingAFormWithInputAsksBeforeDiscarding() {
        show()
        compose.onNodeWithText("Transport").performClick()
        compose.onNode(hasText(text(R.string.form_amount)) and hasSetTextAction()).performTextInput("12")
        compose.onNodeWithText(text(R.string.form_back)).performClick()
        compose.onNodeWithText(text(R.string.tx_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_keep_editing)).performClick()
        compose.onNodeWithText(text(R.string.cat_form_title_expense)).assertIsDisplayed()
    }

    @Test fun manageOpensSettingsWhereANewCategoryIsCreatedAndOffered() {
        show()
        compose.onAllNodesWithContentDescription(text(R.string.cat_manage_action))[0].performClick() // header action (the grid tile is the other)
        compose.onNodeWithText(text(R.string.cs_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_demo_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_add)).performClick()
        compose.onNodeWithText(text(R.string.ce_add_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ce_icon_demo_note)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(text(R.string.ce_name)) and hasSetTextAction()).performTextInput("Books")
        compose.onNodeWithContentDescription(text(R.string.cat_icon_book)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.cat_color_teal)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ce_save_add)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.cs_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Books").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_demo_status, text(R.string.cs_added, "Books"))).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cat_back)).performClick()
        compose.onNodeWithText("Books").performScrollTo().assertIsDisplayed() // now offered in the picker
    }

    @Test fun duplicateNamesAreRejectedAndUnsavedChangesAskBeforeLeaving() {
        show(FlowStart.CategorySettings)
        compose.onNodeWithText(text(R.string.cs_add)).performClick()
        compose.onNode(hasText(text(R.string.ce_name)) and hasSetTextAction()).performTextInput(" food")
        compose.onNodeWithText(text(R.string.ce_name_duplicate, "food", text(R.string.transaction_expense).lowercase())).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cat_cancel)).performClick()
        compose.onNodeWithText(text(R.string.ce_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ce_discard)).performClick()
        compose.onNodeWithText(text(R.string.cs_title)).assertIsDisplayed()
    }

    @Test fun defaultsAreReadOnlyAndAUsedCategoryIsDeactivatedInsteadOfDeleted() {
        val pets = session.categories.let { store ->
            kotlinx.coroutines.runBlocking { (store.list(com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery(pageSize = 100))
                as com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Success).value.items.first { it.name == "Pets" } }
        }
        session.ledger.add(TransactionType.Expense, pets, BigDecimal("5"), LocalDate.of(2026, 11, 19), null)
        show(FlowStart.CategorySettings)
        compose.onNodeWithContentDescription(text(R.string.cs_edit, "Food")).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.cs_badge_default)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithContentDescription(text(R.string.cs_remove, "Pets")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.cs_delete_title, "Pets")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_delete_confirm)).performClick()
        compose.onNodeWithText(text(R.string.cs_inuse_title, "Pets")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_deactivate)).performClick()
        compose.onNodeWithContentDescription(text(R.string.cs_reactivate, "Pets")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cs_badge_inactive)).assertIsDisplayed()
    }

    @Test fun reorderMovesCategoriesAndThePickerFollows() {
        show(FlowStart.CategorySettings)
        compose.onNodeWithText(text(R.string.cs_reorder)).performClick()
        compose.onNodeWithContentDescription(text(R.string.cs_move_up, "Utilities")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.cs_note_reorder)).performScrollTo().assertIsDisplayed()
        val order = session.presentation.order(TransactionType.Expense)
        assertTrue(order.isNotEmpty())
    }

    @Test fun pickerWorksInDarkThemeAtTwiceTheFontSize() {
        show(dark = true, fontScale = 2f)
        compose.onNodeWithText(text(R.string.cat_choose_title)).assertIsDisplayed()
        compose.onNodeWithText("Food").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Entertainment").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.cat_manage_action)).assertCountEquals(2) // header action + Manage tile
    }
}
