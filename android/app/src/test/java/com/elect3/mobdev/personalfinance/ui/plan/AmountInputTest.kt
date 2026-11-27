package com.elect3.mobdev.personalfinance.ui.plan

import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.transactions.add.validAmount
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Input-validation audit: every amount field on the device reads typed text the same, exact way. */
class AmountInputTest {
    @Test fun goalBillAndContributionAmountsNeverReinterpretExponentsSignsOrSeparators() {
        mapOf("1e5" to R.string.amount_error_number, "1E+3" to R.string.amount_error_number, "+5" to R.string.amount_error_number,
            "1,250.50" to R.string.amount_error_number, "₱100" to R.string.amount_error_number, "10..50" to R.string.amount_error_number,
            "abc" to R.string.amount_error_number, "NaN" to R.string.amount_error_number, "-1" to R.string.amount_error_number,
            "0" to R.string.amount_error_positive, "10.555" to R.string.amount_error_decimals, " " to R.string.amount_error_empty,
            "99999999999999999" to R.string.amount_error_large,
        ).forEach { (raw, problem) -> assertEquals(raw, problem, amountProblem(raw)); assertNull(raw, positiveAmount(raw)) }
    }

    @Test fun equalTextIsAnEqualAmountOnEveryForm() {
        for ((raw, expected) in listOf("0.50" to "0.50", "10,50" to "10.50", " 100.5 " to "100.5", "000100" to "100", ".5" to "0.5")) {
            assertEquals(raw, 0, BigDecimal(expected).compareTo(checkNotNull(positiveAmount(raw))))
            assertEquals(raw, 0, BigDecimal(expected).compareTo(checkNotNull(parseLimit(raw))))
            assertEquals(raw, true, validAmount(raw))
            assertEquals(raw, 0, BigDecimal(expected).compareTo(checkNotNull(typedAmount(raw))))
        }
    }

    @Test fun typingAndPastingRefuseTheWholeEditInsteadOfStrippingCharacters() {
        for (ok in listOf("", "0", "0.", ".", ".5", "0,5", "100", "100.5", "100.50", "9999999999999999.99", "000100")) assertNull(ok, amountEditProblem(ok))
        mapOf("100abc" to R.string.input_amount_characters, "1e5" to R.string.input_amount_characters, "-5" to R.string.input_amount_characters,
            "+5" to R.string.input_amount_characters, "₱100" to R.string.input_amount_characters, "1 000" to R.string.input_amount_characters,
            "١٢" to R.string.input_amount_characters, "10..5" to R.string.input_amount_one_separator, "1,000.50" to R.string.input_amount_one_separator,
            "10.555" to R.string.amount_error_decimals, "12345678901234567" to R.string.amount_error_large,
        ).forEach { (raw, problem) -> assertEquals(raw, problem, amountEditProblem(raw)) }

        var field = "100"; var refusal: Int? = null
        val edit = amountEdit({ refusal = it }) { field = it }
        edit("100abc"); assertEquals("100", field); assertEquals(R.string.input_amount_characters, refusal)
        edit("100.555"); assertEquals("100", field); assertEquals(R.string.amount_error_decimals, refusal)
        edit(" 250.75 "); assertEquals("250.75", field); assertNull(refusal) // a pasted value's padding only
        edit("250.7"); assertEquals("250.7", field)                           // deleting stays natural

        var days = "3"
        val whole = wholeEdit(2, { refusal = it }) { days = it }
        whole("3.5"); assertEquals("3", days); assertEquals(R.string.input_whole_digits, refusal)
        whole("-1"); assertEquals("3", days)
        whole("30"); assertEquals("30", days); assertNull(refusal)
        whole("300"); assertEquals("30", days); assertEquals(R.string.input_number_long, refusal)
    }
}
