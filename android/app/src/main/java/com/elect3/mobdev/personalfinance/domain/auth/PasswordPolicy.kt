package com.elect3.mobdev.personalfinance.domain.auth

/**
 * The server's password policy (ASP.NET Core Identity options), rule by rule, so the app can name every unmet
 * requirement before a password is sent. Character classes are ASCII, exactly as Identity counts them: any other
 * character, including accented letters, counts as a special character. The server stays authoritative.
 */
enum class PasswordRule { Length, Uppercase, Lowercase, Number, Special, Distinct }

object PasswordPolicy {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128
    const val MIN_DISTINCT = 4

    fun isMet(rule: PasswordRule, password: String): Boolean = when (rule) {
        PasswordRule.Length -> password.length in MIN_LENGTH..MAX_LENGTH
        PasswordRule.Uppercase -> password.any { it in 'A'..'Z' }
        PasswordRule.Lowercase -> password.any { it in 'a'..'z' }
        PasswordRule.Number -> password.any { it in '0'..'9' }
        PasswordRule.Special -> password.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }
        PasswordRule.Distinct -> password.toSet().size >= MIN_DISTINCT
    }

    /** Unmet rules in checklist order; empty when the password meets the policy. */
    fun unmet(password: String): List<PasswordRule> = PasswordRule.entries.filterNot { isMet(it, password) }
}
