package com.elect3.mobdev.personalfinance.ui.plan
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elect3.mobdev.personalfinance.data.mock.DemoPlanStore
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class GoalsBillsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun goalDetailsAndDemoContribution() {
        val store = DemoPlanStore()
        compose.setContent { PersonalFinanceTheme { GoalsSection(true, store.goals.collectAsState().value, store::contribute) } }
        compose.onNodeWithText("Emergency Fund details").performClick()
        compose.onNodeWithText("Demo only · No money transferred").assertIsDisplayed()
        compose.onNodeWithText("(demo)", substring = true).performClick()
        compose.runOnIdle { assertEquals(0, java.math.BigDecimal("24500").compareTo(store.goals.value.first().saved)) }
    }
    @Test fun billPaymentRequiresConfirmation() {
        val store = DemoPlanStore()
        compose.setContent { PersonalFinanceTheme { BillsSection(true, store.bills.collectAsState().value, store::markPaid) } }
        compose.onNodeWithText("Mark Electricity as paid (demo)").performClick()
        compose.runOnIdle { assertFalse(store.bills.value.first { it.name == "Electricity" }.paid) }
        compose.onNodeWithText("Mark paid (demo)").performClick()
        compose.onNodeWithText("Paid").assertIsDisplayed()
    }
    @Test fun liveGoalsNeverShowSamplesOrActionsEvenIfProvided() {
        val store = DemoPlanStore()
        compose.setContent { PersonalFinanceTheme { GoalsSection(false, store.goals.collectAsState().value, { error("No live write") }) } }
        compose.onNodeWithText("Your savings goals").assertIsDisplayed()
        compose.onNodeWithText("Emergency Fund").assertDoesNotExist()
    }
    @Test fun liveBillsNeverShowSamplesOrActionsEvenIfProvided() {
        val store = DemoPlanStore()
        compose.setContent { PersonalFinanceTheme { BillsSection(false, store.bills.collectAsState().value, { error("No live write") }) } }
        compose.onNodeWithText("Your recurring bills").assertIsDisplayed()
        compose.onNodeWithText("Electricity").assertDoesNotExist()
    }
    @Test fun planTabsOpenGoalsAndBills() {
        val store = DemoPlanStore()
        compose.setContent { PersonalFinanceTheme {
            PlanScreen(true, java.time.YearMonth.of(2026, 11), PlanSections(
                goals = { GoalsSection(true, store.goals.collectAsState().value, store::contribute) }, goalCount = 2,
                bills = { BillsSection(true, store.bills.collectAsState().value, store::markPaid) }, billCount = 3))
        } }
        compose.onNodeWithText("Goals", substring = true).performClick()
        compose.onNodeWithText("Emergency Fund").assertIsDisplayed()
        compose.onNodeWithText("Bills", substring = true).performClick()
        compose.onNodeWithText("Electricity").assertIsDisplayed()
    }
    @Test fun emptyDemoGoals() {
        compose.setContent { PersonalFinanceTheme { GoalsSection(true, emptyList(), {}) } }
        compose.onNodeWithText("No savings goals").assertIsDisplayed()
    }
    @Test fun emptyDemoBills() {
        compose.setContent { PersonalFinanceTheme { BillsSection(true, emptyList(), {}) } }
        compose.onNodeWithText("No recurring bills").assertIsDisplayed()
    }
}
