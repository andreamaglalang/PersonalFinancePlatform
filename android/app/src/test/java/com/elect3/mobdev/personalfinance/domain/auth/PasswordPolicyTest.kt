package com.elect3.mobdev.personalfinance.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordPolicyTest {
    @Test fun reportedPasswordMissesUppercaseAndSpecial() =
        assertEquals(listOf(PasswordRule.Uppercase, PasswordRule.Special), PasswordPolicy.unmet("abcdefghijk1"))

    @Test fun strongPasswordMeetsEveryRule() = assertEquals(emptyList<PasswordRule>(), PasswordPolicy.unmet("Synthetic!Pass123"))

    @Test fun everyRuleCanFailAtOnce() = assertEquals(PasswordRule.entries.toList(), PasswordPolicy.unmet(""))

    @Test fun tooLongFailsLength() = assertEquals(listOf(PasswordRule.Length), PasswordPolicy.unmet("Aa1!" + "x".repeat(125)))

    /** Identity counts only ASCII letters, so an accented capital is a special character, not an uppercase letter. */
    @Test fun charactersAreClassifiedLikeTheServer() =
        assertEquals(listOf(PasswordRule.Uppercase), PasswordPolicy.unmet("Éabcdefghij1"))

    @Test fun fewerThanFourDistinctCharacters() =
        assertEquals(listOf(PasswordRule.Number, PasswordRule.Special, PasswordRule.Distinct), PasswordPolicy.unmet("Aaaaaaaaaaaa"))
}
