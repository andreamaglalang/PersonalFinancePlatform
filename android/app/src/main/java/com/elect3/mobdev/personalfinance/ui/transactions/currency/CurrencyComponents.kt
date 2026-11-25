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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
fun CurrencyPickerDialog(options: List<SupportedCurrency>, selected: String?, baseCurrency: String?, onPick: (String) -> Unit, onDismiss: () -> Unit, searchable: Boolean = false, readOnlyConversion: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    val offered = if (searchable) SupportedCurrencies.search(options, query) else options
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest=onDismiss, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(16.dp),contentAlignment=Alignment.Center) {
            val availableHeight=maxHeight
            Surface(shape=LocalOwliShapes.current.group,color=scheme.surface,contentColor=scheme.onSurface,
                modifier=Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=availableHeight)) {
                Column {
                    Text(stringResource(if(readOnlyConversion) R.string.cur_picker_title else R.string.fx_currency_picker_title),
                        style=typography.screenTitle,color=scheme.onSurface,
                        modifier=Modifier.padding(start=20.dp,end=20.dp,top=20.dp,bottom=12.dp).semantics { heading() })
                    if(baseCurrency!=null && !readOnlyConversion) Text(stringResource(R.string.fx_currency_picker_hint,baseCurrency),
                        style=typography.caption,color=scheme.onSurface,modifier=Modifier.padding(horizontal=20.dp).padding(bottom=8.dp))
                    if(searchable) {
                        OutlinedTextField(query,{query=it.take(80)},singleLine=true,
                            label={Text(stringResource(R.string.cur_search))},
                            modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),
                            trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text(stringResource(R.string.cur_search_clear))}})
                    }
                    if(offered.isEmpty()) Text(stringResource(R.string.cur_search_empty),style=typography.body,
                        modifier=Modifier.padding(20.dp),color=scheme.onSurface)
                    LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth().selectableGroup(),
                        contentPadding=PaddingValues(start=12.dp,end=12.dp,top=12.dp,bottom=16.dp),
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        items(offered,key={it.code}) { option ->
                            val isSelected=option.code==selected
                            val foreground=if(isSelected) scheme.onPrimaryContainer else scheme.onSurface
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clip(LocalOwliShapes.current.field)
                                .background(if(isSelected) scheme.primaryContainer else scheme.surface)
                                .selectable(selected=isSelected,role=Role.RadioButton,onClick={onPick(option.code)})
                                .padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if(isSelected) scheme.primary else owli.accentSoft),contentAlignment=Alignment.Center) {
                                    Text(option.symbol,style=typography.label,textAlign=TextAlign.Center,
                                        color=if(isSelected) scheme.onPrimary else scheme.onSurface)
                                }
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(option.code,style=typography.bodyStrong,color=foreground)
                                    Text(option.name,style=typography.body,color=foreground)
                                }
                                RadioButton(selected=isSelected,onClick=null)
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)) {Text(stringResource(R.string.form_cancel))}
                    }
                }
            }
        }
    }
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

/**
 * Prominent, full-width currency field for the transaction forms: symbol disc, code and name, a clear "Change"
 * affordance and how many currencies are supported. Disabled (with the reason) when the currency is fixed.
 */
@Composable
fun CurrencyFieldCard(code: String, baseCurrency: String?, selectable: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val active = selectable && enabled
    val name = SupportedCurrencies.find(code)?.name ?: code
    val hint = if (selectable) stringResource(R.string.cur_field_hint, SupportedCurrencies.options(baseCurrency).size, baseCurrency ?: code)
        else stringResource(R.string.cur_field_demo)
    val description = stringResource(R.string.fx_currency_change, code)
    Surface(onClick = onClick, enabled = active, shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, if (active) owli.accent.copy(alpha = 0.55f) else owli.hairline),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {
            if (active) { contentDescription = "$description $name. $hint"; role = Role.Button }
        }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (active) owli.accent else owli.accentSoft), contentAlignment = Alignment.Center) {
                Text(SupportedCurrencies.symbol(code), style = typography.bodyStrong, textAlign = TextAlign.Center,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else owli.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.cur_field_label), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$code · $name", style = typography.bodyStrong)
                Text(hint, style = typography.caption, color = if (selectable) owli.accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selectable) Text(stringResource(R.string.cur_field_change) + " ▾", style = typography.label,
                color = if (active) owli.accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** True when the stored snapshot is the API's own, self-consistent result (base = round(amount × rate, 2), 8-dp rate). */
fun isConfirmedSnapshot(t: Transaction): Boolean = t.fxRate.signum() > 0 && t.fxRate.stripTrailingZeros().scale() <= 8 &&
    t.fxRateDate <= t.transactionDate &&
    t.amount.multiply(t.fxRate).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(t.amountBase) == 0

/**
 * Structured conversion details from the server-stored snapshot only: original amount, converted base amount,
 * exchange rate (8 decimals) and rate date. Nothing is recalculated here, so nothing is converted twice.
 * Shows nothing for same-currency transactions or a snapshot that is not self-consistent.
 */
@Composable
fun ConversionDetailsCard(t: Transaction, baseCurrency: String?, modifier: Modifier = Modifier) {
    val base = baseCurrency ?: return
    if (t.currencyCode == base || !isConfirmedSnapshot(t)) return
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Surface(modifier.fillMaxWidth(), shape = LocalOwliShapes.current.group, color = owli.accentSoft,
        border = BorderStroke(1.dp, owli.accent.copy(alpha = 0.35f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cur_details_title), style = typography.bodyStrong, color = owli.accent,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Text(stringResource(R.string.cur_details_confirmed), style = typography.micro, color = owli.accent)
            }
            DetailLine(stringResource(R.string.cur_details_original), formatTransactionAmount(t.amount, t.currencyCode))
            DetailLine(stringResource(R.string.cur_details_converted, base), formatTransactionAmount(t.amountBase, base))
            DetailLine(stringResource(R.string.cur_details_rate), stringResource(R.string.cur_confirmed_row, t.currencyCode, formatRate(t.fxRate), base))
            DetailLine(stringResource(R.string.cur_details_rate_date), formatTransactionDate(t.fxRateDate))
            if (t.fxRateDate < t.transactionDate) Text(stringResource(R.string.cur_details_earlier_rate), style = typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label and value; the value wraps (never ellipsized) and moves under the label on narrow widths or large fonts. */
@Composable
private fun DetailLine(label: String, value: String) {
    val typography = LocalOwliTypography.current
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = typography.bodyStrong, textAlign = TextAlign.End)
    }
}
