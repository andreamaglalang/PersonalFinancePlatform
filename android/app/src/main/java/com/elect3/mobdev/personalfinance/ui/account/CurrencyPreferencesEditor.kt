package com.elect3.mobdev.personalfinance.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.currency.*
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencyPickerDialog
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CurrencyPreferencesEditor(store: AccountCurrencyPreferences,baseCurrency: String?,isCurrent: () -> Boolean,onClose: () -> Unit) {
    val preferences by store.preferences.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    val currentSession by rememberUpdatedState(isCurrent)
    val feedback = remember(store) { CurrencyPreferenceSaveFeedback(
        write={ value -> withContext(Dispatchers.IO) { if(currentSession()) store.set(value) else false } },
        isCurrent={currentSession()}, loadFailed=store.loadFailed) }
    val saveStatus by feedback.status.collectAsStateWithLifecycle()
    val saving=saveStatus==CurrencyPreferenceSaveStatus.Saving
    val failed=saveStatus==CurrencyPreferenceSaveStatus.Failed
    val saved=saveStatus is CurrencyPreferenceSaveStatus.Saved
    val successTimeout=LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(3000L, containsText=true) ?: 3000L
    LaunchedEffect(saveStatus) {
        val event=saveStatus as? CurrencyPreferenceSaveStatus.Saved ?: return@LaunchedEffect
        delay(successTimeout)
        feedback.clearSuccess(event)
    }
    fun update(value: CurrencyPreferences) {
        if(saving || !currentSession()) return
        scope.launch { feedback.save(value) }
    }
    val scheme = MaterialTheme.colorScheme
    val selectedName = SupportedCurrencies.find(preferences.defaultCurrency)?.name ?: preferences.defaultCurrency
    val chooseLabel = stringResource(R.string.prefs_currency_choose_action)
    AlertDialog(onDismissRequest={if(!saving)onClose()},
        title={Text(stringResource(R.string.prefs_currency_title), color=scheme.onSurface, modifier=Modifier.semantics { heading() })},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Surface(shape=LocalOwliShapes.current.group, color=scheme.primaryContainer, contentColor=scheme.onPrimaryContainer) {
                Text(stringResource(R.string.prefs_currency_base,baseCurrency ?: "PHP"),
                    style=MaterialTheme.typography.bodyLarge, modifier=Modifier.fillMaxWidth().padding(16.dp))
            }
            Text(stringResource(R.string.prefs_currency_transactions), style=MaterialTheme.typography.titleSmall,
                color=scheme.onSurface, modifier=Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth().clip(LocalOwliShapes.current.field)
                .background(scheme.surfaceContainerHigh)
                .clickable(enabled=!saving, role=Role.Button, onClickLabel=chooseLabel, onClick={picker=true})
                .semantics(mergeDescendants=true) {}
                .heightIn(min=72.dp).padding(12.dp),
                verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_acc_globe),contentDescription=null,tint=scheme.primary,modifier=Modifier.size(24.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.prefs_currency_default_label),style=MaterialTheme.typography.bodyMedium,color=scheme.onSurface)
                    Text("${preferences.defaultCurrency} · $selectedName",style=MaterialTheme.typography.titleSmall,color=scheme.onSurface)
                }
                Icon(painterResource(R.drawable.ic_owli_chevron_right),contentDescription=null,tint=scheme.primary,modifier=Modifier.size(24.dp))
            }
            HorizontalDivider()
            CurrencyPreferenceSwitch(stringResource(R.string.prefs_currency_original),preferences.showOriginalAmounts,!saving) {
                update(preferences.copy(showOriginalAmounts=it))
            }
            HorizontalDivider()
            CurrencyPreferenceSwitch(stringResource(R.string.prefs_currency_review),preferences.reviewRateBeforeSave,!saving) {
                update(preferences.copy(reviewRateBeforeSave=it))
            }
            Text(stringResource(R.string.prefs_currency_provider),style=MaterialTheme.typography.bodyMedium,color=scheme.onSurface)
            if(saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={
            Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(failed) Text(stringResource(R.string.prefs_currency_save_error),color=scheme.error,
                    modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                if(saved) Text(stringResource(R.string.prefs_currency_saved),color=scheme.onSurface,
                    style=MaterialTheme.typography.bodyMedium,
                    modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                TextButton(enabled=!saving,onClick=onClose){Text(stringResource(R.string.account_close))}
            }
        })
    if(picker) CurrencyPickerDialog(SupportedCurrencies.options(baseCurrency),preferences.defaultCurrency,baseCurrency,
        onPick={code->picker=false;update(preferences.copy(defaultCurrency=code))},onDismiss={picker=false},searchable=true,readOnlyConversion=true)
}

/** One accessible switch target; the thumb is decorative, so it cannot toggle twice. */
@Composable
private fun CurrencyPreferenceSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val scheme=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(LocalOwliShapes.current.field)
        .toggleable(value=checked, enabled=enabled, role=Role.Switch, onValueChange=onChange)
        .heightIn(min=56.dp).padding(horizontal=4.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=scheme.onSurface)
        Switch(checked=checked,onCheckedChange=null,enabled=enabled,
            colors=SwitchDefaults.colors(checkedTrackColor=scheme.primary,checkedThumbColor=scheme.onPrimary,
                uncheckedTrackColor=scheme.surfaceContainerHighest,uncheckedThumbColor=scheme.onSurfaceVariant,
                uncheckedBorderColor=scheme.outline))
    }
}
