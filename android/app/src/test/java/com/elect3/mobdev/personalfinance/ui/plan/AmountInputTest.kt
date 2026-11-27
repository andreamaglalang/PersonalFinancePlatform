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
}
