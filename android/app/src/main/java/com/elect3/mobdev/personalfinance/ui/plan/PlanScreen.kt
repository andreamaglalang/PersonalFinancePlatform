package com.elect3.mobdev.personalfinance.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class PlanTab { Budgets, Goals, Bills }

/** Content supplied per tab by the owner (Budgets and Goals arrive in later phases). */
class PlanSections(
    val budgets: (@Composable () -> Unit)? = null,
    val budgetCount: Int = 0,
    val goals: (@Composable () -> Unit)? = null,
    val goalCount: Int = 0,
)

/**
 * Plan (approved mockup): Budgets, savings Goals and Bills. Bills is honestly unavailable: the API has no
 * recurring-bill endpoint. [demo] keeps the sample-data label visible, as everywhere else in Mock Demo.
 */
@Composable
fun PlanScreen(demo: Boolean, month: YearMonth, sections: PlanSections, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(PlanTab.Budgets) }
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxSize(), color = scheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                    style = typography.greeting, color = scheme.onSurfaceVariant)
                Text(stringResource(R.string.plan_title), style = typography.screenTitle, modifier = Modifier.semantics { heading() })
            }
            if (demo) Text(stringResource(R.string.plan_demo_ribbon), style = typography.caption, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            PlanTabs(tab, sections) { tab = it }
            when (tab) {
                PlanTab.Budgets -> sections.budgets?.invoke() ?: PlanEmpty(R.drawable.ic_cat_wallet, R.string.plan_budgets_pending_title, R.string.plan_pending_message)
                PlanTab.Goals -> sections.goals?.invoke() ?: PlanEmpty(R.drawable.ic_cat_star, R.string.plan_goals_pending_title, R.string.plan_pending_message)
                PlanTab.Bills -> PlanEmpty(R.drawable.ic_cat_card, R.string.plan_bills_title, R.string.plan_bills_message)
            }
        }
    }
}

@Composable
private fun PlanTabs(selected: PlanTab, sections: PlanSections, onSelect: (PlanTab) -> Unit) {
    val owli = LocalOwliColors.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp).clip(CircleShape).background(owli.field)
        .border(1.dp, owli.hairline, CircleShape).padding(3.dp)) {
        listOf(PlanTab.Budgets to (R.string.plan_tab_budgets to 0), PlanTab.Goals to (R.string.plan_tab_goals to sections.goalCount),
            PlanTab.Bills to (R.string.plan_tab_bills to 0)).forEach { (t, info) ->
            val on = selected == t
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(role = Role.Tab) { onSelect(t) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                Text(stringResource(info.first) + if (info.second > 0) "  ${info.second}" else "", style = LocalOwliTypography.current.label,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Dashed empty/notice card from the mockup. */
@Composable
internal fun PlanEmpty(icon: Int, title: Int, message: Int, action: (@Composable () -> Unit)? = null) {
    val owli = LocalOwliColors.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).clip(RoundedCornerShape(20.dp))
        .border(1.dp, owli.accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(owli.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = owli.accent, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(title), style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        Text(stringResource(message), style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
