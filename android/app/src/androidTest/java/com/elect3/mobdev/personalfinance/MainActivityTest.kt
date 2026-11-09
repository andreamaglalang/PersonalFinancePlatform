package com.elect3.mobdev.personalfinance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListUiState
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity rather than supplying replacement Compose content. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun heading(id: Int) = hasText(text(id), substring = false) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun destination(id: Int) = compose.onNode(hasText(text(id), substring = false) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun awaitTransactionsHeading() {
        compose.waitForIdle()
        compose.onNode(heading(R.string.transactions_title)).assertIsDisplayed()
    }

    /** Inspect an already-created model; never let this test construct one. */
    private fun existingTransactions(activity: MainActivity): TransactionListViewModel {
        val failFastFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                throw AssertionError("Expected activity-owned ${modelClass.simpleName} after Transactions composition; model is missing")
        }
        return ViewModelProvider(activity, failFastFactory).get(TransactionListViewModel::class.java)
    }

    private fun assertDashboardLoaded() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text(R.string.dashboard_refresh)).fetchSemanticsNodes().isNotEmpty()
        }
        val list = compose.onNode(hasScrollAction())
        for (id in listOf(R.string.dashboard_title, R.string.dashboard_demo_data, R.string.dashboard_net_flow)) {
            list.performScrollToNode(hasText(text(id), substring = false))
            if (id == R.string.dashboard_title) compose.onNode(heading(id)).assertIsDisplayed()
            else compose.onNodeWithText(text(id), substring = false).assertIsDisplayed()
        }
        // Independently specified total from the normal deterministic demo's six income records.
        val income = formatPhpAmount(BigDecimal("46250.75"))
        list.performScrollToNode(hasText(income, substring = true))
        compose.onNodeWithText(income, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(heading(R.string.transactions_title)).assertDoesNotExist()
        destination(R.string.navigation_dashboard).assertIsSelected().assertIsDisplayed()
        destination(R.string.navigation_transactions).assertIsNotSelected().assertIsDisplayed()
    }

    @Test fun normalLaunchShowsDashboardAndCompleteDemoSummary() {
        assertDashboardLoaded()
        // An existing model bypasses this factory; a missing model must reach it.
        // Throwing avoids creating the hidden Transactions ViewModel during the assertion.
        compose.activityRule.scenario.onActivity { activity ->
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    throw IllegalStateException("Transactions has not been created")
            }
            val exception = assertThrows(IllegalStateException::class.java) {
                ViewModelProvider(activity, factory).get(TransactionListViewModel::class.java)
            }
            assertEquals("Transactions has not been created", exception.message)
        }
    }

    @Test fun activityRecreationRetainsDashboardViewModel() {
        assertDashboardLoaded()
        lateinit var original: DashboardViewModel
        compose.activityRule.scenario.onActivity { activity ->
            original = ViewModelProvider(activity).get(DashboardViewModel::class.java)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            assertSame(original, ViewModelProvider(activity).get(DashboardViewModel::class.java))
        }
        // Reposition to the header; list scroll position may survive recreation.
        compose.onNode(hasScrollAction()).performScrollToNode(heading(R.string.dashboard_title))
        assertDashboardLoaded()
    }

    @Test fun switchingPreservesBothModelsLoadedPagesAndTransactionScroll() {
        assertDashboardLoaded()
        lateinit var dashboard: DashboardViewModel
        compose.activityRule.scenario.onActivity { dashboard = ViewModelProvider(it).get(DashboardViewModel::class.java) }
        val dashboardState = dashboard.state.value
        destination(R.string.navigation_transactions).performClick()
        awaitTransactionsHeading()
        lateinit var transactions: TransactionListViewModel
        compose.activityRule.scenario.onActivity { transactions = existingTransactions(it) }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(text(R.string.transactions_load_more)))
        compose.onNodeWithText(text(R.string.transactions_load_more)).performClick()
        compose.waitUntil(5_000) {
            (transactions.state.value as? TransactionListUiState.Content)?.lastSuccessfulPage == 2
        }
        val loaded = transactions.state.value as TransactionListUiState.Content
        assertEquals(40, loaded.items.size)
        val description = TransactionFixtures.items[30].description!!
        list.performScrollToNode(hasText(description, substring = true))
        compose.onNodeWithText(description, substring = true).assertIsDisplayed()
        val scroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        destination(R.string.navigation_dashboard).performClick()
        assertSame(dashboardState, dashboard.state.value)
        destination(R.string.navigation_transactions).performClick()
        // Do not scroll to the heading here: the restored position is under test.
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertSame(dashboard, ViewModelProvider(it).get(DashboardViewModel::class.java))
            assertSame(transactions, existingTransactions(it))
        }
        assertSame(loaded, transactions.state.value)
        assertEquals(scroll, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.1f)
        compose.onNodeWithText(description, substring = true).assertIsDisplayed()
        // Reselecting the active item must not replace loaded state.
        destination(R.string.navigation_transactions).performClick()
        assertSame(loaded, transactions.state.value)
    }

    @Test fun transactionsSelectionAndModelSurviveRecreationAndBackReturnsDashboard() {
        destination(R.string.navigation_transactions).performClick()
        awaitTransactionsHeading()
        lateinit var original: TransactionListViewModel
        compose.activityRule.scenario.onActivity { original = existingTransactions(it) }
        compose.activityRule.scenario.recreate()
        awaitTransactionsHeading()
        destination(R.string.navigation_transactions).assertIsSelected()
        compose.activityRule.scenario.onActivity {
            assertSame(original, existingTransactions(it))
            it.onBackPressedDispatcher.onBackPressed()
        }
        destination(R.string.navigation_dashboard).assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(heading(R.string.dashboard_title))
        compose.onNode(heading(R.string.dashboard_title)).assertIsDisplayed()
    }
}
