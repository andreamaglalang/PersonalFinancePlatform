package com.elect3.mobdev.personalfinance.ui.dashboard

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.dashboard.DashboardSummaryCalculator
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()
    private val summary = DashboardSummaryCalculator().calculate(TransactionFixtures.items, YearMonth.of(2026, 11))
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun show(state: DashboardUiState, night: Boolean = false, fontScale: Float = 1f,
        retry: () -> Unit = {}, refresh: () -> Unit = {}) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                this.fontScale = fontScale
            }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density, fontScale)) {
                PersonalFinanceTheme {
                    Box(Modifier.widthIn(max = 320.dp)) { DashboardScreen(state, retry, refresh) }
                }
            }
        }
    }

    private fun reveal(id: Int): SemanticsNodeInteraction {
        val value = text(id)
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(value, substring = false))
        return compose.onNodeWithText(value, substring = false)
            .assertIsDisplayed()
    }

    private val month: String get() {
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        return summary.reportingMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
    }

    private fun selectPeriod(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(id)))
        compose.onNodeWithText(text(id)).performClick()
    }

    @Test fun sectionsDemoAndSignedNetArePresent() {
        show(DashboardUiState.Content(summary))
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        reveal(R.string.dashboard_net_flow)
        // Defaults to all recorded sample transactions, explained as not an account balance.
        compose.onNodeWithText(text(R.string.transaction_income_amount, formatPhpAmount(summary.netTransactionFlow)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_net_explanation)).performScrollTo().assertIsDisplayed()
        // The month view names the reporting month explicitly, never an unlabeled "this month".
        selectPeriod(R.string.dashboard_period_month)
        compose.onNodeWithText(text(R.string.transaction_income_amount, formatPhpAmount(summary.monthlyNetTransactionFlow)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_net_month_explanation, month)).performScrollTo().assertIsDisplayed()
        reveal(R.string.dashboard_recent_transactions)
        val description = TransactionFixtures.items.first().description!!
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(description, substring = true))
        compose.onNodeWithText(description, substring = true).assertIsDisplayed()
    }

    @Test fun loadingHasPoliteAnnouncementAndDemoBadge() {
        show(DashboardUiState.InitialLoading)
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_loading)).assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        compose.onNodeWithText(text(R.string.dashboard_net_flow)).assertDoesNotExist()
    }

    private fun assertAmountsShown(vararg amounts: BigDecimal) {
        val list = compose.onNode(hasScrollAction())
        for (amount in amounts) {
            val formatted = formatPhpAmount(amount)
            list.performScrollToNode(hasText(formatted, substring = true))
            compose.onNodeWithText(formatted, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun totalsMonthlyAmountsAndExplicitReportingMonthAreShown() {
        show(DashboardUiState.Content(summary))
        assertAmountsShown(summary.totalIncome, summary.totalExpenses)
        compose.onNodeWithText(text(R.string.dashboard_period_all_time_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(month).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.dashboard_income), useUnmergedTree = true).onLast().assertExists()
        compose.onAllNodesWithText(text(R.string.dashboard_expenses), useUnmergedTree = true).onLast().assertExists()
        selectPeriod(R.string.dashboard_period_month)
        assertAmountsShown(summary.monthlyIncome, summary.monthlyExpenses)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(month))
        compose.onNodeWithText(month).assertIsDisplayed()
    }

    @Test fun periodToggleIsAccessibleRadioGroupDefaultingToAllTime() {
        show(DashboardUiState.Content(summary))
        val monthOption = compose.onNodeWithText(text(R.string.dashboard_period_month))
        val allTimeOption = compose.onNodeWithText(text(R.string.dashboard_period_all_time))
        allTimeOption.performScrollTo().assertIsSelected()
        monthOption.assertIsNotSelected().assertHasClickAction()
        monthOption.performClick()
        monthOption.assertIsSelected()
        allTimeOption.assertIsNotSelected()
    }

    @Test fun entriesAndTopCategoryCardsDescribeReportingMonth() {
        show(DashboardUiState.Content(summary))
        selectPeriod(R.string.dashboard_period_month)
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(text(R.string.dashboard_entries)))
        compose.onNodeWithText(text(R.string.dashboard_entries), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(summary.monthlyRecordCount.toString(), useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithText(text(R.string.dashboard_entries_month, month), useUnmergedTree = true).assertExists()
        list.performScrollToNode(hasText(text(R.string.dashboard_top_category)))
        compose.onNodeWithText(text(R.string.dashboard_top_category), useUnmergedTree = true).assertIsDisplayed()
        val top = summary.topExpenseCategories
        val detail = when (top.size) {
            0 -> text(R.string.dashboard_top_category_none_detail, month)
            1 -> text(R.string.dashboard_top_category_amount, formatPhpAmount(top.first().total), month)
            else -> text(R.string.dashboard_top_category_tie, formatPhpAmount(top.first().total), month)
        }
        compose.onNodeWithText(detail, useUnmergedTree = true).assertExists()
    }

    @Test fun tiedTopCategoriesAreBothNamed() {
        val base = TransactionFixtures.items.first().copy(type = TransactionType.Expense,
            amount = BigDecimal("25.00"), amountBase = BigDecimal("25.00"))
        val rows = listOf(
            base.copy(transactionId = 1, categoryId = 1, categoryName = "Dining"),
            base.copy(transactionId = 2, categoryId = 2, categoryName = "Transport"),
        )
        val tied = DashboardSummaryCalculator().calculate(rows, YearMonth.of(2026, 11))
        show(DashboardUiState.Content(tied))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Dining, Transport", substring = false))
        compose.onNodeWithText("Dining, Transport", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun netFlowIsNeverLabelledAsBalance() {
        show(DashboardUiState.Content(summary))
        compose.onAllNodes(hasText("Balance", substring = false, ignoreCase = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Account balance", substring = false, ignoreCase = true)).assertCountEquals(0)
    }

    @Test fun emptyStateShowsCalmMessageAndSampleLabel() {
        show(DashboardUiState.Empty)
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        reveal(R.string.dashboard_empty_message)
    }

    @Test fun emptyRefreshInvokesCallback() {
        var calls = 0
        show(DashboardUiState.Empty, refresh = { calls++ })
        reveal(R.string.dashboard_empty_title)
        reveal(R.string.dashboard_refresh).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun initialErrorRetryInvokesCallback() {
        var calls = 0
        show(DashboardUiState.InitialFailure(DashboardError.Repository(TransactionFailure.Unavailable)), retry = { calls++ })
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        reveal(R.string.dashboard_retry).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun invalidDataShowsSafeError() {
        show(DashboardUiState.InitialFailure(DashboardError.InvalidSnapshot))
        reveal(R.string.dashboard_invalid_data)
    }

    @Test fun expiredDemoIsExplicitWithoutNonfunctionalActions() {
        show(DashboardUiState.SessionExpired)
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        reveal(R.string.dashboard_session_title)
        compose.onNodeWithText(text(R.string.dashboard_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.dashboard_refresh)).assertDoesNotExist()
    }

    @Test fun contentRefreshInvokesCallback() {
        var calls = 0
        show(DashboardUiState.Content(summary), refresh = { calls++ })
        compose.onNodeWithText(text(R.string.dashboard_refresh)).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun refreshingDisablesActionAndRetainsSummary() {
        show(DashboardUiState.Content(summary, isRefreshing = true))
        compose.onNodeWithText(text(R.string.dashboard_refresh)).assertIsNotEnabled()
        reveal(R.string.dashboard_refreshing)
        reveal(R.string.dashboard_net_flow)
    }

    @Test fun failedRefreshLabelsRetainedSummaryAndRetries() {
        var calls = 0
        show(DashboardUiState.Content(summary, refreshError = DashboardError.Repository(TransactionFailure.Unavailable)), refresh = { calls++ })
        reveal(R.string.dashboard_refresh_failed)
        compose.onNodeWithText(text(R.string.dashboard_retained_summary, text(R.string.dashboard_unavailable_message)))
            .performScrollTo().assertIsDisplayed()
        reveal(R.string.dashboard_retry_refresh).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        reveal(R.string.dashboard_net_flow)
    }

    private fun responsive(night: Boolean, scale: Float) {
        val amount = BigDecimal("9999999999999999.99")
        val row = TransactionFixtures.items.first().copy(amount = amount, amountBase = amount)
        val large = DashboardSummaryCalculator().calculate(listOf(row), YearMonth.of(2026, 11))
        show(DashboardUiState.Content(large), night = night, fontScale = scale)
        reveal(R.string.dashboard_net_flow)
        val signed = text(R.string.transaction_expense_amount, formatPhpAmount(amount))
        val node = compose.onAllNodesWithText(signed, useUnmergedTree = true).onFirst()
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse(layouts.first().hasVisualOverflow)
        // The Midnight Panel's owl-eye values keep the complete unsigned value at narrow widths and large fonts.
        // At 2x text the panel can extend off-screen, where a node may be composed but not placed;
        // node-level performScrollTo cannot position it. Scroll the list to the panel item first.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("net")
        val unsigned = compose.onAllNodesWithText(formatPhpAmount(amount), useUnmergedTree = true).onFirst()
        unsigned.performScrollTo().assertIsDisplayed()
        val cardLayouts = mutableListOf<TextLayoutResult>()
        unsigned.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(cardLayouts) }
        assertFalse(cardLayouts.first().hasVisualOverflow)
        reveal(R.string.dashboard_recent_transactions)
    }

    @Test fun narrowLightThemePreservesLargeAmounts() = responsive(night = false, scale = 1f)
    @Test fun narrowDarkThemePreservesLargeAmounts() = responsive(night = true, scale = 1f)
    @Test fun narrowLargeFontRemainsScrollableWithoutAmountOverflow() = responsive(night = false, scale = 2f)

    @Test fun dashboardAndSectionTitlesAreSemanticHeadings() {
        show(DashboardUiState.Content(summary))
        compose.onNodeWithText(text(R.string.dashboard_title)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        reveal(R.string.dashboard_recent_transactions).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun whereItWentRanksMonthExpensesWithTopBadgeAndFullPeriodPercentages() {
        show(DashboardUiState.Content(summary))
        selectPeriod(R.string.dashboard_period_month)
        reveal(R.string.dashboard_where_title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(text(R.string.dashboard_where_period_month, month)).assertExists()
        val ranked = summary.monthlyExpenseCategoryTotals
        val top = ranked.first()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.dashboard_where_top_badge)))
        compose.onNodeWithText(text(R.string.dashboard_where_top_badge), useUnmergedTree = true).assertIsDisplayed()
        // Denominator is the full month total, not the sum of the displayed rows.
        val share = categoryShareLabel(top.total, summary.monthlyExpenses)
        compose.onAllNodesWithText(share, useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText("01", useUnmergedTree = true).onFirst().assertExists()
        if (ranked.size > 5) compose.onAllNodesWithText("06", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun footerReportsVerifiedCountAndInsightsShortcutIsNotActionable() {
        show(DashboardUiState.Content(summary))
        val count = summary.recordCount.toString()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.dashboard_footer_all_time, count)))
        compose.onNodeWithText(text(R.string.dashboard_footer_all_time, count)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.dashboard_footer_insights_unavailable))
            .assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
