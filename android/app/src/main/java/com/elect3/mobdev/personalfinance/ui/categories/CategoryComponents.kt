package com.elect3.mobdev.personalfinance.ui.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

@Composable internal fun isDarkSurface() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Solid colour disc with a white line icon (palette colours keep white at 4:1 or better). Decorative. */
@Composable
fun CategoryDisc(iconKey: String, hex: String, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    Box(modifier.size(size).clip(CircleShape).background(categoryColor(hex)), contentAlignment = Alignment.Center) {
        Icon(painterResource(CategoryVisuals.icon(iconKey).drawable), contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Picker tile: soft tinted circle, readable label below (wraps, never truncated), selection ring and check. */
@Composable
fun CategoryGridTile(name: String, iconKey: String, hex: String, selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val dark = isDarkSurface()
    val base = categoryColor(hex)
    val surface = MaterialTheme.colorScheme.surface
    val fill = lerp(surface, base, if (dark) 0.24f else 0.15f)
    val tint = if (dark) lerp(base, Color.White, 0.52f) else lerp(base, MaterialTheme.colorScheme.onSurface, 0.22f)
    val ink = MaterialTheme.colorScheme.primary
    val selectedLabel = stringResource(R.string.cat_selected)
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 92.dp).padding(vertical = 4.dp, horizontal = 2.dp)
            .semantics(mergeDescendants = true) { this.selected = selected; if (selected) contentDescription = "$name, $selectedLabel" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(fill)
                .then(if (selected) Modifier.border(BorderStroke(1.5.dp, ink), CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(CategoryVisuals.icon(iconKey).drawable), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(18.dp).clip(CircleShape).background(ink),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_cat_check), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
        Text(name, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** Dashed "Manage" tile that opens Category settings. */
@Composable
fun CategoryManageTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalOwliColors.current.accent
    val description = stringResource(R.string.cat_manage_action)
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 92.dp).padding(vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val dash = LocalOwliColors.current.hairline
        Box(Modifier.size(56.dp).drawBehind {
            drawRoundRect(dash, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                cornerRadius = CornerRadius(size.minDimension / 2))
        }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_cat_sliders), null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Text(stringResource(R.string.cat_manage), style = LocalOwliTypography.current.caption, color = accent, textAlign = TextAlign.Center)
    }
}

/** Expense/Income pill matching the transaction colour roles. */
@Composable
fun CategoryTypePill(type: TransactionType, modifier: Modifier = Modifier) {
    val colors = LocalOwliColors.current
    val income = type == TransactionType.Income
    val fg = if (income) colors.income else colors.expense
    Row(modifier.clip(LocalOwliShapes.current.chip).background(lerp(MaterialTheme.colorScheme.surface, fg, 0.16f))
        .padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(if (income) R.drawable.ic_owli_income_up else R.drawable.ic_owli_expense_down), null, tint = fg, modifier = Modifier.size(11.dp))
        Text(stringResource(if (income) R.string.transaction_income else R.string.transaction_expense),
            style = LocalOwliTypography.current.micro, color = fg)
    }
}

/** Expense / Income segmented control used by the picker, settings and editor. */
@Composable
fun CategoryTypeTabs(selected: TransactionType, onSelect: (TransactionType) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        listOf(TransactionType.Expense, TransactionType.Income).forEachIndexed { index, type ->
            SegmentedButton(selected = selected == type, enabled = enabled || selected == type, onClick = { if (enabled) onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveBorderColor = LocalOwliColors.current.hairline,
                    disabledActiveContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    disabledActiveContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (type == TransactionType.Income) R.string.transaction_income else R.string.transaction_expense))
            }
        }
    }
}
