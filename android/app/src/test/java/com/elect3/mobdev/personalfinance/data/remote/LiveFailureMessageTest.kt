package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 device run: a rejected contribution showed "The current password may be incorrect". */
class LiveFailureMessageTest {
    @Test fun planWritesNeverMentionPasswords() {
        assertFalse(liveFailure(FinanceFailure.InvalidInput, writing = true).contains("password", ignoreCase = true))
        assertFalse(liveFailure(FinanceFailure.InvalidInput).contains("password", ignoreCase = true))
    }

    @Test fun accountWritesShowTheServersSpecificReason() {
        assertEquals("Your current password is incorrect.", liveFailure(FinanceFailure.InvalidInput, writing = true, account = true,
            message = "Your current password is incorrect."))
    }

    /** Without a known reason, the account message no longer guesses that the password was wrong. */
    @Test fun accountWritesWithoutReasonDoNotGuess() {
        assertFalse(liveFailure(FinanceFailure.InvalidInput, writing = true, account = true).contains("may be incorrect"))
    }

    @Test fun uncertainOutcomesNeverUseAServerReason() {
        assertTrue(liveFailure(FinanceFailure.Unavailable, writing = true, message = "Enter an amount greater than zero.")
            .contains("outcome is unknown"))
    }

    @Test fun expiredSessionSaysSo() {
        assertEquals("Your session has expired. Please sign in again.", liveFailure(FinanceFailure.SessionExpired))
    }
}
