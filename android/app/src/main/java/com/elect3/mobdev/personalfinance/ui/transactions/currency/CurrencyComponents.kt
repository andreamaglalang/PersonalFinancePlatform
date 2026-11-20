package com.elect3.mobdev.personalfinance.ui.transactions.currency

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrency
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionDate

/** Rate text with the API's 8-decimal precision, never rounded or re-scaled for display. */
fun formatRate(rate: java.math.BigDecimal): String = rate.setScale(8, java.math.RoundingMode.UNNECESSARY).toPlainString()

/** Pill showing the selected currency's symbol and code; opens the picker. At least 48dp tall. */
@Composable
fun CurrencySelectorButton(code: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val owli = LocalOwliColors.current
    val description = stringResource(R.string.fx_currency_change, code)
    OutlinedButton(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.chip,
        border = BorderStroke(1.dp, owli.accent.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = owli.accentSoft, contentColor = owli.accent),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description; role = Role.Button }) {
        Text(SupportedCurrencies.symbol(code) + "  " + code + "  ▾", style = LocalOwliTypography.current.label)
    }
}

/** Accessible single-choice list of the offered currencies (base currency first). */
@Composable
fun CurrencyPickerDialog(options: List<SupportedCurrency>, selected: String?, baseCurrency: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fx_currency_picker_title), modifier = Modifier.semantics { heading() }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_cancel)) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (baseCurrency != null) Text(stringResource(R.string.fx_currency_picker_hint, baseCurrency), style = typography.caption)
                LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                    items(options, key = { it.code }) { option ->
                        val isSelected = option.code == selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(LocalOwliShapes.current.field)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(option.code) })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(if (isSelected) owli.accent else owli.accentSoft),
                                contentAlignment = Alignment.Center) {
                                Text(option.symbol, style = typography.label, textAlign = TextAlign.Center,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else owli.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(option.code, style = typography.bodyStrong)
                                Text(option.name, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = isSelected, onClick = null)
                        }
                    }
                }
            }
        })
}

/** Shown before saving when a foreign currency is selected: the conversion is the server's, after saving. */
@Composable
fun ConversionNoticeCard(baseCurrency: String, dateText: String, showOfflineNote: Boolean, modifier: Modifier = Modifier) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Surface(modifier.fillMaxWidth(), shape = LocalOwliShapes.current.group, color = owli.accentSoft,
        border = BorderStroke(1.dp, owli.accent.copy(alpha = 0.35f))) {
        Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.fx_conversion_title, baseCurrency), style = typography.bodyStrong, color = owli.accent)
            Text(stringResource(R.string.fx_conversion_body, dateText, baseCurrency), style = typography.caption)
            if (showOfflineNote) Text(stringResource(R.string.fx_conversion_offline), style = typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The server-confirmed conversion of one transaction: source amount, base amount, rate (8 dp) and rate date. */
@Composable
fun ConversionSummary(t: Transaction, baseCurrency: String?, modifier: Modifier = Modifier) {
    val typography = LocalOwliTypography.current
    val base = baseCurrency ?: return
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (t.currencyCode == base) {
            Text(stringResource(R.string.fx_same_currency), style = typography.caption)
            return@Column
        }
        Text(stringResource(R.string.fx_equals, formatTransactionAmount(t.amount, t.currencyCode), formatTransactionAmount(t.amountBase, base)),
            style = typography.bodyStrong)
        Text(stringResource(R.string.fx_rate, t.currencyCode, formatRate(t.fxRate), base), style = typography.caption)
        Text(stringResource(R.string.fx_rate_date, formatTransactionDate(t.fxRateDate)), style = typography.caption)
    }
}
