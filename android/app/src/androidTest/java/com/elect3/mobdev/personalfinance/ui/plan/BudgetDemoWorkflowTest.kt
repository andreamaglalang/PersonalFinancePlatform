package com.elect3.mobdev.personalfinance.ui.plan

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Budget
import com.elect3.mobdev.personalfinance.domain.repository.BudgetLimits
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.ui.app.OwliFinanceContent
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real demo composition and stores only: no AuthApi, HTTP engine, account repository or credentials. */
class BudgetDemoWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = DemoSession()
    // Production Plan follows the current month. Capture it once; all synthetic amounts/IDs are fixed.
    private val month = YearMonth.now()
    private val seededIds = setOf(5, 3, 6, 4, 7)
    private val seededLimit = BigDecimal("8100.00")
    private val housingId = 100
    private var saveCallbacks = 0
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val dialog = hasAnyAncestor(isDialog())
    private val editable = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun money(value: BigDecimal) = formatTransactionAmount(value, "PHP")
    private fun baselineSpent() = TransactionFixtures.items.filter {
        it.type == TransactionType.Expense && it.categoryId in seededIds && YearMonth.from(it.transactionDate) == month
    }.fold(BigDecimal.ZERO) { total, row -> total.add(row.amountBase) }
    private fun budgets(): List<Budget> = runBlocking {
        (session.budgets.list(month) as FinanceResult.Success).value
    }
    private fun seedHousing(): Budget = runBlocking {
        (session.budgets.create(housingId, month, BudgetLimits(BigDecimal("310.00"), 80)) as FinanceResult.Success).value
    }
    private fun showPlan() {
        session.budgets.reset(month)
        show()
    }
    private fun show() {
        assertEquals("Month changed during test setup", month, YearMonth.now())
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            val listing = ViewModelProvider(owner, TransactionListViewModel.Factory(
                MockTransactionRepository(ledger = session.ledger))).get("budget-demo-list", TransactionListViewModel::class.java)
            PersonalFinanceTheme {
                OwliFinanceContent(
                    authenticatedContentKey = null, authenticatedRepository = null,
                    demoTransactions = { listing }, isSessionCurrent = { false },
                    onSessionExpired = { error("Demo must not expire an account") },
                    dashboardContent = { Text("SYNTHETIC DEMO HOME") },
                    demoSession = session,
                    onDemoDataChanged = { saveCallbacks++; listing.refreshAfterConfirmedCreation() },
                )
            }
        }
        openPlan()
    }
    private fun openPlan() {
        compose.onNode(hasText(text(R.string.navigation_plan)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.plan_title)) and heading).assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.budget_loading)).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(text(R.string.budget_left_title).uppercase()).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun assertSummary(limit: BigDecimal, spent: BigDecimal) {
        compose.onNodeWithText(money(limit.subtract(spent))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budget_used_of, money(spent), money(limit)), substring = true)
            .performScrollTo().assertIsDisplayed()
    }
    private fun notice(id: Int) = compose.onNodeWithText(text(id) + " " + text(R.string.budget_demo_suffix))
    private fun waitForNotice(id: Int) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(id) + " " + text(R.string.budget_demo_suffix)).fetchSemanticsNodes().isNotEmpty()
        }
        notice(id).performScrollTo().assertIsDisplayed()
    }
    private fun openHousing() = compose.onNode(hasText("Housing") and hasClickAction() and !dialog)
        .performScrollTo().performClick()

    @Test fun planOpensWithSyntheticBudgetListingAndExactSummary() {
        showPlan()
        compose.onNodeWithText(text(R.string.plan_demo_ribbon)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.plan_tab_budgets), substring = true) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertSummary(seededLimit, baselineSpent())
        compose.onNode(hasText("Food") and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budget_note_demo)).performScrollTo().assertIsDisplayed()
        assertEquals(5, budgets().size)
        assertTrue(session.ledger.snapshot().isEmpty())
    }

    @Test fun createBudgetPersistsOnlyInDemoAndRefreshesSummary() {
        showPlan()
        compose.onNode(hasText(text(R.string.budget_new)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.budget_new_title)) and heading).assertIsDisplayed()
        compose.onNode(hasText("Housing") and hasClickAction() and dialog).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.budget_limit)) and editable and dialog).performScrollTo().performTextInput("310.00")
        compose.onNode(hasText(text(R.string.budget_create)) and hasClickAction() and dialog).performScrollTo().performClick()
        waitForNotice(R.string.budget_notice_created)
        val created = budgets().single { it.categoryId == housingId }
        assertEquals(0, BigDecimal("310.00").compareTo(created.limitAmount))
        assertEquals(80, created.alertThresholdPct)
        assertEquals(0, created.spentAmount.signum())
        assertEquals(6, budgets().size)
        compose.onNode(hasText("Housing") and hasClickAction() and !dialog).performScrollTo().assertIsDisplayed()
        assertSummary(seededLimit.add(BigDecimal("310.00")), baselineSpent())
        assertTrue(session.ledger.snapshot().isEmpty())
    }

    @Test fun editBudgetKeepsIdentityAndRefreshesDisplayedLimit() {
        session.budgets.reset(month)
        val original = seedHousing()
        show()
        openHousing()
        compose.onNode(hasText(text(R.string.budget_change_limit)) and editable and dialog)
            .performScrollTo().performTextReplacement("525.00")
        compose.onNode(hasText(text(R.string.budget_save)) and hasClickAction() and dialog).performScrollTo().performClick()
        waitForNotice(R.string.budget_notice_updated)
        val updated = budgets().single { it.categoryId == housingId }
        assertEquals(original.budgetId, updated.budgetId)
        assertEquals(0, BigDecimal("525.00").compareTo(updated.limitAmount))
        assertEquals(6, budgets().size)
        assertSummary(seededLimit.add(BigDecimal("525.00")), baselineSpent())
        assertTrue(session.ledger.snapshot().isEmpty())
    }

    @Test fun deleteBudgetRequiresConfirmationAndPreservesTransactions() {
        session.budgets.reset(month)
        seedHousing()
        show()
        openHousing()
        compose.onNode(hasText(text(R.string.budget_delete)) and hasClickAction() and dialog).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.budget_delete_title, "Housing")).assertIsDisplayed()
        assertEquals(6, budgets().size) // Opening confirmation must not delete anything.
        compose.onNode(hasText(text(R.string.budget_delete)) and hasClickAction() and
            hasAnySibling(hasText(text(R.string.account_cancel)))).performClick()
        waitForNotice(R.string.budget_notice_deleted)
        assertEquals(5, budgets().size)
        assertFalse(budgets().any { it.categoryId == housingId })
        compose.onNode(hasText("Housing") and hasClickAction() and !dialog).assertDoesNotExist()
        assertSummary(seededLimit, baselineSpent())
        assertTrue(session.ledger.snapshot().isEmpty())
    }

    @Test fun savingMockExpenseRefreshesBudgetSpendingWhenReturningToPlan() {
        showPlan()
        val foodBefore = budgets().single { it.categoryId == 3 }.spentAmount
        assertSummary(seededLimit, baselineSpent())
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Food") and hasClickAction() and dialog).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Food") and hasClickAction() and dialog).performClick()
        compose.onNode(hasText(text(R.string.form_amount)) and editable).performTextInput("12.34")
        compose.onNode(hasText(text(R.string.form_description)) and editable).performScrollTo().performTextInput("Synthetic budget refresh expense")
        compose.onNodeWithText(text(R.string.form_demo_session_save)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { session.ledger.snapshot().size == 1 && saveCallbacks == 1 }
        compose.onNodeWithText(text(R.string.tx_demo_saved_title)).assertIsDisplayed()
        val saved = session.ledger.snapshot().single()
        assertEquals(TransactionType.Expense, saved.type)
        assertEquals(3, saved.categoryId)
        assertEquals(0, BigDecimal("12.34").compareTo(saved.amountBase))
        assertEquals(month, YearMonth.from(saved.transactionDate))
        compose.onNodeWithText(text(R.string.tx_back_dashboard)).performScrollTo().performClick()
        compose.onNodeWithText("SYNTHETIC DEMO HOME").assertIsDisplayed()
        openPlan()
        val foodAfter = budgets().single { it.categoryId == 3 }.spentAmount
        assertEquals(0, foodBefore.add(BigDecimal("12.34")).compareTo(foodAfter))
        assertSummary(seededLimit, baselineSpent().add(BigDecimal("12.34")))
        compose.onNodeWithText(text(R.string.plan_demo_ribbon)).performScrollTo().assertIsDisplayed()
        assertEquals(1, saveCallbacks)
        assertEquals(1, session.ledger.snapshot().size)
    }
}
