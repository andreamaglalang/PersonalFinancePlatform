package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionFilterSheetTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val today = LocalDate.of(2026, 11, 19)
    private var applied: TransactionFilter? = null
    private var dismissed = 0

    private fun show(current: TransactionFilter = TransactionFilter()) = compose.setContent {
        PersonalFinanceTheme {
            TransactionFilterSheet(current, DemoCategoryRepository(), today,
                onApply = { applied = it }, onReset = { applied = TransactionFilter() }, onDismiss = { dismissed++ })
        }
    }

    @Test fun applyBuildsAServerFilterFromTheSelections() {
        show()
        compose.onNodeWithText(text(R.string.filter_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transaction_income)).performClick()
        compose.onNodeWithText(text(R.string.filter_date_this_month)).performClick()
        compose.onNodeWithText(text(R.string.filter_sort_oldest)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.filter_apply)).performScrollTo().performClick()
        compose.runOnIdle {
            val f = applied!!
            assertEquals(TransactionType.Income, f.type)
            assertEquals(LocalDate.of(2026, 11, 1), f.fromDate)
            assertEquals(LocalDate.of(2026, 11, 30), f.toDate)
            assertEquals(TransactionListSort.Oldest, f.sort)
        }
    }

    @Test fun cancelAppliesNothing() {
        show(TransactionFilter(type = TransactionType.Expense))
        compose.onNodeWithText(text(R.string.filter_date_last_month)).performClick()
        compose.onNodeWithText(text(R.string.filter_cancel)).performScrollTo().performClick()
        compose.runOnIdle { assertNull(applied); assertEquals(1, dismissed) }
    }

    @Test fun resetRequestsTheDefaultFilter() {
        show(TransactionFilter(type = TransactionType.Expense, sort = TransactionListSort.Oldest))
        compose.onNodeWithText(text(R.string.filter_reset)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TransactionFilter(), applied) }
    }

    @Test fun presetsAreRecognisedFromTheCurrentFilter() {
        assertEquals(TransactionDatePreset.ThisMonth, presetOf(TransactionFilter(fromDate = LocalDate.of(2026, 11, 1), toDate = LocalDate.of(2026, 11, 30)), today))
        assertEquals(TransactionDatePreset.LastMonth, presetOf(TransactionFilter(fromDate = LocalDate.of(2026, 10, 1), toDate = LocalDate.of(2026, 10, 31)), today))
        assertEquals(TransactionDatePreset.Custom, presetOf(TransactionFilter(fromDate = LocalDate.of(2026, 11, 3)), today))
        assertEquals(TransactionDatePreset.AllTime, presetOf(TransactionFilter(), today))
    }
}
