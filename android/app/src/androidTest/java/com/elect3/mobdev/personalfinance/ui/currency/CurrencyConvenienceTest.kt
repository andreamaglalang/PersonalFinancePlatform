package com.elect3.mobdev.personalfinance.ui.currency

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencyPickerDialog
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurrencyConvenienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveQuickAmountsSetInputAndFollowSourceCurrency() {
        val model = CurrencyConverterViewModel("PHP", false, emptyList(), false)
        compose.setContent { PersonalFinanceTheme { CurrencyConverterScreen(model, model.state.collectAsState().value, null, {}) } }
        compose.onNodeWithTag("currency-quick-1000").performClick()
        compose.runOnIdle { assertEquals("1000", model.state.value.amount); model.from("PHP") }
        compose.onNodeWithTag("currency-quick-100").assert(hasText(SupportedCurrencies.symbol("PHP") + "100")).performClick()
        compose.runOnIdle { assertEquals("100", model.state.value.amount) }
    }

    @Test fun demoConverterDoesNotGainQuickAmounts() {
        val model = CurrencyConverterViewModel("PHP", true, emptyList(), false)
        compose.setContent { PersonalFinanceTheme { CurrencyConverterScreen(model, model.state.collectAsState().value, null, {}) } }
        compose.onNodeWithTag("currency-quick-100").assertDoesNotExist()
    }

    @Test fun searchablePickerFiltersAndSelectsAnOfferedCurrency() {
        var picked: String? = null
        compose.setContent { PersonalFinanceTheme {
            CurrencyPickerDialog(SupportedCurrencies.options("PHP"), "USD", "PHP", { picked = it }, {}, searchable = true)
        } }
        compose.onNodeWithText("Search currencies").performTextInput("peso")
        compose.onNodeWithText("USD").assertDoesNotExist()
        compose.onNodeWithText("PHP").performClick()
        compose.runOnIdle { assertEquals("PHP", picked) }
    }

    @Test fun liveConverterPickerUsesConversionCopyWithoutTransactionSaveInstructions() {
        compose.setContent { PersonalFinanceTheme {
            CurrencyPickerDialog(SupportedCurrencies.options("PHP"), "USD", "PHP", {}, {}, searchable = true, readOnlyConversion = true)
        } }
        compose.onNodeWithText("Choose currency").assertIsDisplayed()
        compose.onNodeWithText("Transaction currency").assertDoesNotExist()
        compose.onNodeWithText("Other currencies are converted", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Search currencies").assertIsDisplayed()
    }

    @Test fun pickerShowsEmptySearchAndClearRestoresOptions() {
        compose.setContent { PersonalFinanceTheme {
            CurrencyPickerDialog(SupportedCurrencies.options("PHP"), "USD", "PHP", {}, {}, searchable = true)
        } }
        compose.onNodeWithText("Search currencies").performTextInput("no-match")
        compose.onNodeWithText("No currencies found").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("USD").assertExists()
    }
}
