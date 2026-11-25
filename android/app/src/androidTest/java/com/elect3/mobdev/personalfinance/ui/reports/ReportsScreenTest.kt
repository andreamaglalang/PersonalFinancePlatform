package com.elect3.mobdev.personalfinance.ui.reports

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportCalculator
import com.elect3.mobdev.personalfinance.ui.app.OwliApp
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Screen states and Insights navigation. Requires a device or emulator. */
class ReportsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nov = YearMonth.of(2026, 11)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun month(m: YearMonth) = reportMonthLabel(m, compose.activity.resources.configuration.locales[0] ?: Locale.getDefault())

    private fun row(id: Int, type: TransactionType, amount: String, date: LocalDate, currency: String = "PHP", rate: String = "1",
                    base: String = amount, category: String = "Food") = Transaction(id, UUID.randomUUID(), type, id, category,
        BigDecimal(amount), currency, BigDecimal(rate), if (currency == "PHP") date else date.minusDays(1), BigDecimal(base), date,
        null, Instant.EPOCH, null, null)

    private val report = FinancialReportCalculator().calculate(listOf(
        row(1, TransactionType.Income, "50000.00", nov.atDay(1), category = "Salary"),
        row(2, TransactionType.Expense, "1200.00", nov.atDay(3), category = "Rent"),
        row(3, TransactionType.Expense, "10.00", nov.atDay(9), currency = "USD", rate = "58.12345678", base = "581.23", category = "Books"),
        row(4, TransactionType.Expense, "300.00", nov.minusMonths(1).atDay(5), category = "Rent"),
    ), nov, "PHP")

    private fun show(state: ReportsUiState, onRetry: () -> Unit = {}, onPrevious: () -> Unit = {}, onSelect: (YearMonth) -> Unit = {}) =
        compose.setContent {
            PersonalFinanceTheme {
                ReportsScreen(state, currentMonth = nov, demo = false, onPrevious = onPrevious, onNext = {}, onSelectMonth = onSelect,
                    onRetry = onRetry, onRefresh = {})
            }
        }

    @Test fun readyShowsOverviewBreakdownTrendAndForeignCurrencyNote() {
        show(ReportsUiState.Ready(nov, report))
        compose.onNodeWithText(text(R.string.reports_ribbon_account)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reports_ribbon_account_detail, "PHP")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reports_title)).assertIsDisplayed()
        compose.onAllNodesWithText(month(nov)).onFirst().assertIsDisplayed()
        // The panel is announced as one description holding every overview figure.
        compose.onNodeWithContentDescription("+₱48,218.77", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("USD", substring = true))
        compose.onNodeWithText("USD", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription("Rank 1, Rent", substring = true))
        compose.onNodeWithContentDescription("Rank 1, Rent", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.reports_trend_title)))
        compose.onNodeWithText(text(R.string.reports_trend_title)).assertIsDisplayed()
    }

    @Test fun nextMonthIsDisabledAtTheCurrentMonthAndPreviousWorks() {
        var previous = 0
        show(ReportsUiState.Ready(nov, report), onPrevious = { previous++ })
        compose.onNodeWithContentDescription(text(R.string.reports_next_month, month(nov.plusMonths(1)))).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.reports_previous_month, month(nov.minusMonths(1)))).assertIsEnabled().performClick()
        assertEquals(1, previous)
    }

    @Test fun loadingIsLabelledWithTheRequestedMonth() {
        show(ReportsUiState.Loading(nov.minusMonths(1)))
        compose.onNodeWithText(text(R.string.reports_loading, month(nov.minusMonths(1)))).assertIsDisplayed()
    }

    @Test fun errorOffersRetry() {
        var retries = 0
        show(ReportsUiState.Failure(nov, ReportsError.Unavailable), onRetry = { retries++ })
        compose.onNodeWithText(text(R.string.reports_unavailable_title)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.reports_retry)) and hasClickAction()).performClick()
        assertEquals(1, retries)
    }

    @Test fun sessionExpiredHidesFiguresAndPeriodControls() {
        show(ReportsUiState.SessionExpired(nov))
        compose.onNodeWithText(text(R.string.reports_session_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.reports_previous_month, month(nov.minusMonths(1)))).assertDoesNotExist()
    }

    @Test fun emptyWindowShowsEmptyState() {
        show(ReportsUiState.Ready(nov, FinancialReportCalculator().calculate(emptyList(), nov, "PHP")))
        compose.onNodeWithText(text(R.string.reports_empty_title)).assertIsDisplayed()
    }

    @Test fun insightsTabOpensReportsAndBackReturnsHome() {
        compose.setContent {
            PersonalFinanceTheme {
                OwliApp(dashboardContent = { Text("HOME CONTENT") }, transactionsContent = { Text("ACTIVITY CONTENT") },
                    insightsContent = { Text("INSIGHTS CONTENT") })
            }
        }
        compose.onNode(hasText(compose.activity.getString(R.string.navigation_insights)) and hasClickAction()).performClick()
        compose.onNodeWithText("INSIGHTS CONTENT").assertIsDisplayed()
        compose.onNode(hasText(compose.activity.getString(R.string.navigation_insights)) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("HOME CONTENT").assertIsDisplayed()
    }

    @Test fun withoutInsightsContentInsightsStaysUnavailable() {
        compose.setContent {
            PersonalFinanceTheme { OwliApp(dashboardContent = { Text("HOME CONTENT") }, transactionsContent = { Text("ACTIVITY CONTENT") }) }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.navigation_coming_soon_description,
            compose.activity.getString(R.string.navigation_insights))).assertExists()
    }
}
