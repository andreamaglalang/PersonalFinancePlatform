package com.elect3.mobdev.personalfinance.ui.auth

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.auth.PasswordPolicy
import com.elect3.mobdev.personalfinance.domain.auth.PasswordRule
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

private val PasswordRule.label get() = when (this) {
    PasswordRule.Length -> R.string.password_rule_length
    PasswordRule.Uppercase -> R.string.password_rule_upper
    PasswordRule.Lowercase -> R.string.password_rule_lower
    PasswordRule.Number -> R.string.password_rule_number
    PasswordRule.Special -> R.string.password_rule_special
    PasswordRule.Distinct -> R.string.password_rule_distinct
}

/** "Password must contain A, B and C." naming every unmet rule, or null when the password meets the policy. */
fun passwordRequirementsMessage(resources: Resources, password: String): String? {
    val parts = PasswordPolicy.unmet(password).map { rule ->
        resources.getString(when (rule) {
            PasswordRule.Length -> if (password.length > PasswordPolicy.MAX_LENGTH) R.string.password_need_max else R.string.password_need_length
            PasswordRule.Uppercase -> R.string.password_need_upper
            PasswordRule.Lowercase -> R.string.password_need_lower
            PasswordRule.Number -> R.string.password_need_number
            PasswordRule.Special -> R.string.password_need_special
            PasswordRule.Distinct -> R.string.password_need_distinct
        })
    }
    if (parts.isEmpty()) return null
    val list = if (parts.size == 1) parts[0]
        else resources.getString(R.string.list_pair, parts.dropLast(1).joinToString(resources.getString(R.string.list_separator)), parts.last())
    return resources.getString(R.string.password_needs_sentence, list)
}

/** Live checklist shown under a new-password field; each row reads as "requirement, done/still needed". */
@Composable
fun PasswordChecklist(password: String, modifier: Modifier = Modifier) {
    val typography = LocalOwliTypography.current
    val met = LocalOwliColors.current.income
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(top = 6.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.password_rules_title), style = typography.caption, color = MaterialTheme.colorScheme.onSurface)
        PasswordRule.entries.forEach { rule ->
            val done = password.isNotEmpty() && PasswordPolicy.isMet(rule, password)
            val label = stringResource(rule.label)
            val spoken = stringResource(if (done) R.string.password_rule_state_done else R.string.password_rule_state_needed, label)
            Row(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                Text(if (done) "✓" else "○", style = typography.caption, color = if (done) met else muted,
                    textAlign = TextAlign.Center, modifier = Modifier.width(18.dp))
                Text(label, style = typography.caption, color = if (done) met else muted)
            }
        }
    }
}
