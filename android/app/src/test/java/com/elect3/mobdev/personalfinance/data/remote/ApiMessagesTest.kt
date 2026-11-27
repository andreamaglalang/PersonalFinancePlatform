package com.elect3.mobdev.personalfinance.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiMessagesTest {
    @Test fun knownTitlesUseTheAppsWording() {
        assertEquals("A budget already exists for this category and month.",
            ApiMessages.read("""{"title":"A budget already exists for this category and month.","status":409}"""))
        assertEquals("This transaction was updated elsewhere. Reload before editing.",
            ApiMessages.read("""{"title":"Transaction changed. Reload and retry."}"""))
    }

    @Test fun fieldErrorsAreCollected() = assertEquals("Your current password is incorrect.",
        ApiMessages.read("""{"title":"One or more validation errors occurred.","errors":{"CurrentPassword":["Your current password is incorrect."]}}"""))

    @Test fun passwordSentenceIsAcceptedOnlyFromKnownParts() {
        assertTrue(ApiMessages.isPasswordRequirements("Password must contain an uppercase letter and a special character, such as ! or #."))
        assertTrue(ApiMessages.isPasswordRequirements("Password must contain at least 12 characters, an uppercase letter, a number and at least 4 different characters."))
        assertFalse(ApiMessages.isPasswordRequirements("Password must contain <script>."))
        assertFalse(ApiMessages.isPasswordRequirements("Password must contain a number and a number."))
    }

    @Test fun unknownOrUnsafeTextIsNeverShown() {
        assertNull(ApiMessages.read("""{"title":"SqlException: Violation of UNIQUE KEY constraint"}"""))
        assertNull(ApiMessages.read("""{"errors":{"$.amount":["The JSON value could not be converted."]}}"""))
        assertNull(ApiMessages.read("<html>502</html>"))
        assertNull(ApiMessages.read(null))
    }
}
