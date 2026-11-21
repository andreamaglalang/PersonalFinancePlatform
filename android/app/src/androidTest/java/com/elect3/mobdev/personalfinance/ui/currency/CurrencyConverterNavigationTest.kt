package com.elect3.mobdev.personalfinance.ui.currency

import androidx.activity.ComponentActivity
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
                CurrencyConverterScreen(model, model.state.value, onRecordTransaction = if (demo) null else ({}), onBack = { open = false })
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

    @Test fun mockDemoShowsThePhpOnlyNoteAndNoRecordAction() {
        show(demo = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.cur_entry_title)))
        compose.onNode(hasText(text(R.string.cur_entry_title)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.cur_demo_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cur_record_action)).assertDoesNotExist()
    }
}
