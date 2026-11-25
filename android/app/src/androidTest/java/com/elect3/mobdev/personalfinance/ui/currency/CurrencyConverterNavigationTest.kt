package com.elect3.mobdev.personalfinance.ui.currency

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.AccountProfile
import com.elect3.mobdev.personalfinance.data.local.AppearancePreference
import com.elect3.mobdev.personalfinance.ui.account.AccountMode
import com.elect3.mobdev.personalfinance.ui.account.AccountScreen
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Rule
import org.junit.Test

/** Account > Finance setup > Currency converter, and the converter's honest states. Instrumented (needs a device). */
class CurrencyConverterNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int, vararg a: Any) = compose.activity.getString(id, *a)

    private fun show(demo: Boolean) = compose.setContent {
        PersonalFinanceTheme {
            var open by remember { mutableStateOf(false) }
            if (!open) AccountScreen(if (demo) AccountMode.Demo else AccountMode.SignedIn(AccountProfile("Test User", "test@example.test", "PHP")),
                AppearancePreference.System, {}, onManageCategories = {}, onLogout = {}, onBack = {}, onOpenCurrencyConverter = { open = true })
            else {
                val model = remember { CurrencyConverterViewModel("PHP", demo, emptyList(), canRecordTransaction = !demo) }
                val state by model.state.collectAsState()
                CurrencyConverterScreen(model, state, onRecordTransaction = if (demo) null else ({}), onBack = { open = false })
            }
        }
    }

    @Test fun accountRowOpensTheConverterWithAnHonestNoQuoteState() {
        show(demo = false)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.cur_entry_title)))
        compose.onNode(hasText(text(R.string.cur_entry_title)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.cur_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cur_result_enter)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cur_record_action)).assertIsDisplayed()
    }

    @Test fun liveConverterShowsRateAndDateAndSwapClearsResult() {
        var calls = 0
        val model = CurrencyConverterViewModel("PHP", false, emptyList(), false,
            quotes = com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository { from, to, amount ->
                calls++
                com.elect3.mobdev.personalfinance.domain.repository.FinanceResult.Success(
                    com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuote(from, to, amount,
                        java.math.BigDecimal("58.00000000"), java.time.LocalDate.of(2026, 11, 20), java.time.LocalDate.of(2026, 11, 20),
                        java.math.BigDecimal("580.00"), "Frankfurter", java.time.Instant.parse("2026-11-20T00:00:00Z")))
            })
        model.amount("10")
        compose.setContent { PersonalFinanceTheme {
            val state by model.state.collectAsState()
            CurrencyConverterScreen(model, state, null, {})
        } }
        compose.onNodeWithText(text(R.string.cur_live_get)).performScrollTo().performClick()
        compose.onNodeWithText("580.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Rate date", substring = true).assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, calls) }
        compose.onNodeWithText(text(R.string.cur_swap)).performScrollTo().performClick()
        compose.onNodeWithText("580.00", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cur_how_title)).assertDoesNotExist()
    }

    @Test fun mockDemoShowsThePhpOnlyNoteAndNoRecordAction() {
        show(demo = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.cur_entry_title)))
        compose.onNode(hasText(text(R.string.cur_entry_title)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.cur_demo_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cur_record_action)).assertDoesNotExist()
    }
}
