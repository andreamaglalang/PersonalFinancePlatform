package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 device run: a rejected contribution showed "The current password may be incorrect". */
class LiveFailureMessageTest {
    @Test fun planWritesNeverMentionPasswords() {
        assertFalse(liveFailure(FinanceFailure.InvalidInput, writing = true).contains("password", ignoreCase = true))
        assertFalse(liveFailure(FinanceFailure.InvalidInput).contains("password", ignoreCase = true))
    }

    @Test fun accountWritesKeepThePasswordHint() {
        assertTrue(liveFailure(FinanceFailure.InvalidInput, writing = true, account = true).contains("password"))
    }
}
