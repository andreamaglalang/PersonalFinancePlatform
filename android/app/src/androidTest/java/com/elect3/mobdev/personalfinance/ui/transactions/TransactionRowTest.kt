package com.elect3.mobdev.personalfinance.ui.transactions

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionRowTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun show(row: Transaction, night: Boolean = false) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PersonalFinanceTheme { TransactionRow(row) }
            }
        }
    }

    private fun signed(row: Transaction): String = text(
        if (row.type == TransactionType.Income) R.string.transaction_income_amount else R.string.transaction_expense_amount,
        formatPhpAmount(row.amount),
    )

    @Test fun categoryDescriptionAndDateAppearInMergedRow() {
        val row = TransactionFixtures.items.first()
        show(row)
        compose.onNode(hasText(row.categoryName) and hasText(row.description!!) and
            hasText(formatTransactionDate(row.transactionDate)))
            .assertExists().assertIsDisplayed()
    }

    @Test fun nullDescriptionIsOmitted() {
        val row = TransactionFixtures.items.single { it.transactionId == 44 }
        val description = "Temporary description for omission verification"
        val supplied = mutableStateOf(row.copy(description = description))
        compose.setContent { PersonalFinanceTheme { TransactionRow(supplied.value) } }
        compose.onNodeWithText(description).assertExists()
        compose.runOnIdle { supplied.value = row }
        compose.onNodeWithText(row.categoryName).assertExists()
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText(signed(row)).assertExists()
    }

    @Test fun incomeLabelAndPositiveSignAreExplicit() {
        val row = TransactionFixtures.items.first { it.type == TransactionType.Income }
        show(row)
        val amount = signed(row)
        assertTrue(amount.startsWith("+"))
        compose.onNode(hasText(text(R.string.transaction_income)) and hasText(amount)).assertExists()
    }

    @Test fun expenseLabelAndNegativeSignAreExplicit() {
        val row = TransactionFixtures.items.first()
        show(row)
        val amount = signed(row)
        assertTrue(amount.startsWith("\u2212"))
        compose.onNode(hasText(text(R.string.transaction_expense)) and hasText(amount)).assertExists()
    }

    @Test fun largePhpValuePreservesPrecisionOnAndroid() {
        val row = TransactionFixtures.items.first().copy(amount = BigDecimal("9999999999999999.99"))
        show(row)
        val formatted = formatPhpAmount(row.amount)
        val parser = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) as DecimalFormat
        parser.isParseBigDecimal = true
        val position = ParsePosition(0)
        val parsed = parser.parse(formatted, position) as BigDecimal
        assertEquals(formatted.length, position.index)
        assertEquals(0, row.amount.compareTo(parsed))
        compose.onNodeWithText(signed(row)).assertExists().assertIsDisplayed()
    }

    @Test fun rowRendersInLightTheme() {
        val row = TransactionFixtures.items.first()
        show(row, night = false)
        compose.onNodeWithText(signed(row)).assertIsDisplayed()
    }

    @Test fun rowRendersInDarkTheme() {
        val row = TransactionFixtures.items.first()
        show(row, night = true)
        compose.onNodeWithText(signed(row)).assertIsDisplayed()
    }
}
