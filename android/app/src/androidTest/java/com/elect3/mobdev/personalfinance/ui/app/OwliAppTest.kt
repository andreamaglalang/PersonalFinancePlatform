package com.elect3.mobdev.personalfinance.ui.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardError
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardScreen
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardUiState
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwliAppTest {
    @get:Rule val compose = createComposeRule()
    private val positions = mutableMapOf<String, LazyListState>()
    private val entries = mutableMapOf<String, Int>()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun destination(id: Int) = compose.onNode(hasText(text(id), substring = false) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    @Composable
    private fun TestList(name: String) {
        val state = rememberLazyListState()
        DisposableEffect(state) {
            positions[name] = state
            entries[name] = (entries[name] ?: 0) + 1
            onDispose { }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("$name-list"), state = state) {
            items((0..59).toList(), key = { it }) {
                Text("$name record $it", Modifier.padding(16.dp))
            }
        }
    }

    private fun showLists(scale: Float = 1f, night: Boolean = false) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                fontScale = scale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                PersonalFinanceTheme {
                    Box(Modifier.widthIn(max = 320.dp)) {
                        OwliApp({ TestList("dashboard") }, { TestList("transactions") })
                    }
                }
            }
        }
    }

    @Test fun dashboardIsDefaultAndOnlyActiveContentIsComposed() {
        showLists()
        destination(R.string.navigation_dashboard).assertIsSelected().assertIsDisplayed()
        destination(R.string.navigation_transactions).assertIsNotSelected().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-list").assertIsDisplayed()
        compose.onNodeWithTag("transactions-list").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("dashboard"), entries.keys) }
    }

    @Test fun switchingAndReselectionPreserveScrollWithoutReenteringActiveContent() {
        showLists()
        compose.onNodeWithTag("dashboard-list").performScrollToIndex(25)
        var dashboardIndex = 0
        var dashboardOffset = 0
        compose.runOnIdle {
            dashboardIndex = positions.getValue("dashboard").firstVisibleItemIndex
            dashboardOffset = positions.getValue("dashboard").firstVisibleItemScrollOffset
        }
        destination(R.string.navigation_dashboard).performClick()
        compose.runOnIdle { assertEquals(1, entries.getValue("dashboard")) }
        destination(R.string.navigation_transactions).performClick().assertIsSelected()
        compose.onNodeWithTag("transactions-list").performScrollToIndex(35)
        var transactionIndex = 0
        compose.runOnIdle { transactionIndex = positions.getValue("transactions").firstVisibleItemIndex }
        destination(R.string.navigation_dashboard).performClick()
        compose.runOnIdle {
            assertEquals(dashboardIndex, positions.getValue("dashboard").firstVisibleItemIndex)
            assertEquals(dashboardOffset, positions.getValue("dashboard").firstVisibleItemScrollOffset)
        }
        destination(R.string.navigation_transactions).performClick()
        compose.runOnIdle { assertEquals(transactionIndex, positions.getValue("transactions").firstVisibleItemIndex) }
        compose.onNodeWithTag("dashboard-list").assertDoesNotExist()
    }

    @Test fun selectedDestinationAndScrollRestoreFromSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PersonalFinanceTheme { OwliApp({ TestList("dashboard") }, { TestList("transactions") }) } }
        destination(R.string.navigation_transactions).performClick()
        compose.onNodeWithTag("transactions-list").performScrollToIndex(30)
        var index = 0
        compose.runOnIdle { index = positions.getValue("transactions").firstVisibleItemIndex }
        restoration.emulateSavedInstanceStateRestore()
        destination(R.string.navigation_transactions).assertIsSelected()
        compose.runOnIdle { assertEquals(index, positions.getValue("transactions").firstVisibleItemIndex) }
    }

    private fun assertNarrowNavigation(scale: Float, night: Boolean) {
        showLists(scale, night)
        for ((id, name) in listOf(R.string.navigation_dashboard to "dashboard", R.string.navigation_transactions to "transactions")) {
            destination(id).performClick().assertIsSelected().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            // Check the label's own text layout, not just the selectable parent bounds.
            val label = compose.onNode(hasText(text(id)) and hasAnyAncestor(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)), useUnmergedTree = true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val labelVisibility = runCatching { label.assertIsDisplayed() }
                .fold(onSuccess = { "displayed" }, onFailure = { "not displayed: ${it.message}" })
            val itemVisibility = runCatching { destination(id).assertIsDisplayed() }
                .fold(onSuccess = { "displayed" }, onFailure = { "not displayed: ${it.message}" })
            val diagnostics = buildString {
                append("Navigation label='${text(id)}', fontScale=$scale, theme=${if (night) "dark" else "light"}")
                append(", layoutResultCount=${layouts.size}")
                append(", labelVisibility=$labelVisibility, navigationItemVisibility=$itemVisibility")
                append(", labelBoundsDp=${label.getUnclippedBoundsInRoot()}")
                append(", navigationItemBoundsDp=${destination(id).getUnclippedBoundsInRoot()}")
                append(", labelBoundsInRootPx=${label.fetchSemanticsNode().boundsInRoot}")
                layouts.forEachIndexed { index, layout ->
                    append("; layout[$index]: didOverflowWidth=${layout.didOverflowWidth}")
                    append(", didOverflowHeight=${layout.didOverflowHeight}, hasVisualOverflow=${layout.hasVisualOverflow}")
                    append(", sizePx=${layout.size}, lineCount=${layout.lineCount}")
                }
            }
            assertTrue("No text layout results. $diagnostics", layouts.isNotEmpty())
            assertFalse(diagnostics, layouts.first().hasVisualOverflow)
            compose.onNodeWithTag("$name-list").performScrollToIndex(59)
            val last = compose.onNodeWithText("$name record 59").assertIsDisplayed().getUnclippedBoundsInRoot()
            val barItem = destination(id).getUnclippedBoundsInRoot()
            assertTrue("Last content must remain above navigation", last.bottom <= barItem.top)
        }
    }

    @Test fun narrowNavigationAndFinalContentAreUsable() = assertNarrowNavigation(1f, false)
    @Test fun narrowLargeFontDarkNavigationAndFinalContentAreUsable() = assertNarrowNavigation(2f, true)

    @Test fun loadingEmptyAndErrorsRemainInteractiveInsideHost() {
        var state by mutableStateOf<DashboardUiState>(DashboardUiState.InitialLoading)
        var retries = 0
        var refreshes = 0
        compose.setContent {
            PersonalFinanceTheme {
                OwliApp(
                    { DashboardScreen(state, { retries++ }, { refreshes++ }) },
                    { TestList("transactions") },
                )
            }
        }
        compose.onNodeWithText(text(R.string.dashboard_loading)).assertIsDisplayed()
        destination(R.string.navigation_transactions).performClick().assertIsSelected()
        compose.runOnIdle { state = DashboardUiState.Empty }
        destination(R.string.navigation_dashboard).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.dashboard_refresh)))
        compose.onNodeWithText(text(R.string.dashboard_refresh)).performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state = DashboardUiState.InitialFailure(DashboardError.Repository(TransactionFailure.Unavailable))
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.dashboard_retry)))
        compose.onNodeWithText(text(R.string.dashboard_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        destination(R.string.navigation_transactions).performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, refreshes) }
    }
}
