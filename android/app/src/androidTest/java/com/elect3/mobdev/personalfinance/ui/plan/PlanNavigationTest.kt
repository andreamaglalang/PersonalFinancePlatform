package com.elect3.mobdev.personalfinance.ui.plan

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.app.OwliApp
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test

class PlanNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    private fun show(withPlan: Boolean = true) = compose.setContent {
        PersonalFinanceTheme {
            OwliApp(dashboardContent = { Text("HOME CONTENT") }, transactionsContent = { Text("ACTIVITY CONTENT") },
                planContent = if (withPlan) ({ PlanScreen(demo = true, month = YearMonth.of(2026, 11), sections = PlanSections()) }) else null)
        }
    }

    @Test fun planTabOpensPlanWithThreeSectionsAndBackReturnsHome() {
        show()
        compose.onNode(hasText(text(R.string.navigation_plan)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.plan_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.plan_demo_ribbon)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.plan_tab_budgets)) and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNode(hasText(text(R.string.plan_tab_bills)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.plan_bills_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.plan_bills_message)).assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("HOME CONTENT").assertIsDisplayed()
    }

    @Test fun liveTabsDoNotAppendRecordCounts() {
        compose.setContent { PersonalFinanceTheme {
            PlanScreen(false, YearMonth.of(2026, 11), PlanSections(budgetCount = 5, goalCount = 2, billCount = 3))
        } }
        compose.onNodeWithText(text(R.string.plan_tab_budgets)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.plan_tab_budgets) + "  5").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.plan_demo_ribbon)).assertDoesNotExist()
    }

    @Test fun withoutPlanContentPlanStaysAnUnavailableSoonItem() {
        show(withPlan = false)
        compose.onNode(hasText(text(R.string.plan_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertDoesNotExist()
        compose.onNodeWithText("HOME CONTENT").assertIsDisplayed()
    }
}
