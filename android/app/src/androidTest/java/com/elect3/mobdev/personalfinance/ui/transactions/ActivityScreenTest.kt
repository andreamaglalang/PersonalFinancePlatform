package com.elect3.mobdev.personalfinance.ui.transactions

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Redesigned Activity over the Mock Demo repository (November 2026 sample month). */
@RunWith(AndroidJUnit4::class)
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private val nov = YearMonth.of(2026, 11)
    private lateinit var vm: TransactionListViewModel

    private fun show(month: YearMonth = nov, dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            vm = androidx.compose.runtime.remember { TransactionListViewModel(MockTransactionRepository(), initialFilter = TransactionFilter().forMonth(month)) }
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.fontScale = fontScale
                if (dark) uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PersonalFinanceTheme { TransactionListRoute(vm, onAddTransaction = {}) }
            }
        }
        compose.waitUntil(5_000) { (vm.state.value as? TransactionListUiState.Content)?.complete == true || vm.state.value == TransactionListUiState.Empty }
    }

    @Test fun monthViewShowsCompleteTotalsDayGroupsAndNoAddButton() {
        show()
        compose.onNodeWithText(text(R.string.transactions_title)).assertIsDisplayed()
        compose.onNodeWithText(monthLabel(nov)).assertIsDisplayed()
        compose.onNodeWithText("+₱24,191.50").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.act_summary_caption, monthLong(nov), 14)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.act_add_first)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.form_title)).assertDoesNotExist()
        // The current month is the latest selectable month.
        compose.onNodeWithContentDescription(text(R.string.act_next_month)).assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.act_end_month, monthLong(nov))))
    }

    @Test fun searchFiltersTheCompleteMonthAndClearAllRestores() {
        show()
        compose.onNodeWithContentDescription(text(R.string.act_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("canteen")
        compose.onNodeWithText(text(R.string.act_search_scope, monthLabel(nov))).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Lunch at the campus canteen", substring = true))
        compose.onAllNodesWithText("Transport").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText(text(R.string.act_search_close)).performClick()
        compose.runOnIdle { assertEquals("", vm.searchQuery.value) }
    }

    @Test fun typeSegmentAndMonthArrowsApplyServerFilters() {
        show()
        compose.onNode(hasText(text(R.string.act_type_income)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { (vm.state.value as? TransactionListUiState.Content)?.items?.all { it.type.name == "Income" } == true }
        compose.onNodeWithContentDescription(text(R.string.act_prev_month)).performClick()
        compose.runOnIdle { assertEquals(nov.minusMonths(1), vm.activeFilter.value.month) }
        compose.onNodeWithText(text(R.string.act_all_time)).performClick()
        compose.runOnIdle { assertNull(vm.activeFilter.value.month); assertNull(vm.activeFilter.value.fromDate) }
    }

    @Test fun tappingARowOpensReadOnlyDetails() {
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Lunch at the campus canteen", substring = true))
        compose.onNode(hasText("Lunch at the campus canteen", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.act_detail_description)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.act_close)).performClick()
    }

    @Test fun emptyMonthOffersAllTimeAndPreviousMonth() {
        show(month = YearMonth.of(2025, 3))
        compose.onNodeWithText(text(R.string.act_empty_month_title, monthLong(YearMonth.of(2025, 3)))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.act_show_all_time)).performClick()
        compose.runOnIdle { assertNull(vm.activeFilter.value.month) }
    }

    @Test fun darkThemeAtTwiceTheFontSizeKeepsAmountsAndControls() {
        show(dark = true, fontScale = 2f)
        compose.onNodeWithText("+₱24,191.50").assertIsDisplayed()
        compose.onNode(hasText(text(R.string.act_type_expenses)) and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("−₱185.50"))
    }
}
