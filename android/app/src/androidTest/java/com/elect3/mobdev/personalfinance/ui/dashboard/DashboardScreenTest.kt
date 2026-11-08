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
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
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

    @Test fun sectionsDemoAndSignedNetArePresent() {
        show(DashboardUiState.Content(summary))
        compose.onNodeWithText(text(R.string.dashboard_demo_data)).assertIsDisplayed()
        reveal(R.string.dashboard_net_flow)
        compose.onNodeWithText(text(R.string.transaction_income_amount, formatPhpAmount(summary.netTransactionFlow)))
            .assertIsDisplayed()
        reveal(R.string.dashboard_monthly_overview)
        reveal(R.string.dashboard_monthly_net)
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

    @Test fun totalsMonthlyAmountsAndExplicitReportingMonthAreShown() {
        show(DashboardUiState.Content(summary))
        val list = compose.onNode(hasScrollAction())
        for (amount in listOf(summary.totalIncome, summary.totalExpenses, summary.monthlyIncome, summary.monthlyExpenses)) {
            val formatted = formatPhpAmount(amount)
            list.performScrollToNode(hasText(formatted, substring = true))
            compose.onNodeWithText(formatted, useUnmergedTree = true).assertIsDisplayed()
        }
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val month = summary.reportingMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        list.performScrollToNode(hasText(month))
        compose.onNodeWithText(month).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.dashboard_income), useUnmergedTree = true).onLast().assertExists()
        compose.onAllNodesWithText(text(R.string.dashboard_expenses), useUnmergedTree = true).onLast().assertExists()
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
        reveal(R.string.dashboard_monthly_overview)
        reveal(R.string.dashboard_recent_transactions)
    }

    @Test fun narrowLightThemePreservesLargeAmounts() = responsive(night = false, scale = 1f)
    @Test fun narrowDarkThemePreservesLargeAmounts() = responsive(night = true, scale = 1f)
    @Test fun narrowLargeFontRemainsScrollableWithoutAmountOverflow() = responsive(night = false, scale = 2f)

    @Test fun dashboardAndSectionTitlesAreSemanticHeadings() {
        show(DashboardUiState.Content(summary))
        compose.onNodeWithText(text(R.string.dashboard_title)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        reveal(R.string.dashboard_monthly_overview).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
