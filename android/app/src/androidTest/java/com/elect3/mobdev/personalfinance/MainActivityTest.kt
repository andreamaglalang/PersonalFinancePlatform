package com.elect3.mobdev.personalfinance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity rather than supplying replacement Compose content. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun assertDashboardLoaded() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text(R.string.dashboard_refresh)).fetchSemanticsNodes().isNotEmpty()
        }
        val list = compose.onNode(hasScrollAction())
        for (id in listOf(R.string.dashboard_title, R.string.dashboard_demo_data, R.string.dashboard_net_flow)) {
            list.performScrollToNode(hasText(text(id), substring = false))
            compose.onNodeWithText(text(id), substring = false).assertIsDisplayed()
        }
        // Independently specified total from the normal deterministic demo's six income records.
        val income = formatPhpAmount(BigDecimal("46250.75"))
        list.performScrollToNode(hasText(income, substring = true))
        compose.onNodeWithText(income, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_title)).assertDoesNotExist()
    }

    @Test fun normalLaunchShowsDashboardAndCompleteDemoSummary() {
        assertDashboardLoaded()
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.dashboard_title)))
        assertDashboardLoaded()
    }
}
