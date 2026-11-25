package com.elect3.mobdev.personalfinance.ui.currency

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.currency.ConfirmedRate
import com.elect3.mobdev.personalfinance.domain.currency.ConverterResult
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.ui.auth.owliFieldColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencyPickerDialog
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencySelectorButton
import com.elect3.mobdev.personalfinance.ui.transactions.currency.formatRate
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionDate

/** Full-screen, read-only converter page. Owns its ViewModel; never writes data. */
@Composable
fun CurrencyConverterDialog(
    baseCurrency: String?,
    demo: Boolean,
    confirmed: List<ConfirmedRate>,
    /** Signed-in only: closes the converter and opens Add Transaction. Null hides the action. */
    onRecordTransaction: (() -> Unit)?,
    onClose: () -> Unit,
    /** Signed-in only: the OWLI server's read-only quote endpoint. Null keeps the honest "no live quote" state. */
    quotes: com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository? = null,
    isCurrent: () -> Boolean = { true },
    onExpired: () -> Unit = {},
) {
    val store = remember { ViewModelStore() }
    val model = remember {
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == CurrencyConverterViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return CurrencyConverterViewModel(baseCurrency, demo, confirmed, onRecordTransaction != null,
                    quotes = if (demo) null else quotes, isSessionCurrent = isCurrent, onSessionExpired = onExpired) as T
            }
        })[CurrencyConverterViewModel::class.java]
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    val state by model.state.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BackHandler { onClose() }
            CurrencyConverterScreen(model, state, onRecordTransaction?.takeIf { state.canRecordTransaction }, onClose)
        }
    }
}

@Composable
fun CurrencyConverterScreen(model: CurrencyConverterViewModel, state: CurrencyConverterUiState, onRecordTransaction: (() -> Unit)?, onBack: () -> Unit) {
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val colors = LocalOwliColors.current
    val focus = LocalFocusManager.current
    var picking by remember { mutableStateOf<String?>(null) } // "from" or "to"
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.cur_back)) }
            Text(stringResource(R.string.cur_title), style = typography.screenTitle, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (state.demo) R.string.cur_demo_note else R.string.cur_live_mode),
                style = typography.caption, color = colors.accent)
            // Converter inputs.
            Surface(shape = shapes.group, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, colors.hairline)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CurrencyLine(stringResource(R.string.cur_from), state.from) { focus.clearFocus(); picking = "from" }
                    val invalid = state.result == ConverterResult.InvalidAmount
                    OutlinedTextField(state.amount, model::amount, singleLine = true, label = { Text(stringResource(R.string.cur_amount)) },
                        prefix = { Text(SupportedCurrencies.symbol(state.from) + " ", style = typography.amountMedium) },
                        suffix = { Text(state.from, style = typography.label) },
                        textStyle = typography.amountMedium, shape = shapes.field, colors = owliFieldColors(), isError = invalid,
                        supportingText = { if (invalid) Text(stringResource(R.string.cur_amount_error), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
                    if (!state.demo) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 500, 1000).forEach { value ->
                            OutlinedButton(onClick = { focus.clearFocus(); model.quickAmount(value) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("currency-quick-$value")) {
                                Text(SupportedCurrencies.symbol(state.from) + java.text.NumberFormat.getIntegerInstance().format(value), style = typography.label)
                            }
                        }
                    }
                    OutlinedButton(onClick = { focus.clearFocus(); model.swap() }, shape = shapes.chip, border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cur_swap), style = typography.label)
                    }
                    CurrencyLine(stringResource(R.string.cur_to), state.to) { focus.clearFocus(); picking = "to" }
                }
            }
            ResultCard(state, model)
            if (onRecordTransaction != null) Button(onClick = onRecordTransaction, shape = shapes.button,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.cur_record_action), textAlign = TextAlign.Center) }
        }
    }
    picking?.let { which ->
        CurrencyPickerDialog(state.options, if (which == "from") state.from else state.to, state.baseCurrency,
            onPick = { if (which == "from") model.from(it) else model.to(it); picking = null }, onDismiss = { picking = null }, searchable = !state.demo, readOnlyConversion = !state.demo)
    }
}

@Composable
private fun CurrencyLine(label: String, code: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(SupportedCurrencies.find(code)?.name ?: code, style = LocalOwliTypography.current.bodyStrong)
        }
        CurrencySelectorButton(code, enabled = true, onClick = onPick)
    }
}

@Composable
private fun ResultCard(state: CurrencyConverterUiState, model: CurrencyConverterViewModel) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Surface(Modifier.fillMaxWidth(), shape = LocalOwliShapes.current.group, color = colors.accentSoft, border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f))) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cur_result_heading), style = typography.label, color = colors.accent, modifier = Modifier.semantics { heading() })
            when (val r = state.result) {
                ConverterResult.EnterAmount -> Text(stringResource(R.string.cur_result_enter), style = typography.body)
                ConverterResult.InvalidAmount -> Text(stringResource(R.string.cur_amount_error), style = typography.body, color = MaterialTheme.colorScheme.error)
                is ConverterResult.SameCurrency -> {
                    Text(formatTransactionAmount(r.amount, r.currencyCode), style = typography.amountMedium)
                    Text(stringResource(R.string.cur_result_same), style = typography.caption)
                }
                is ConverterResult.QuoteUnavailable -> LiveQuoteSection(state, model, r.reference)
            }
        }
    }
}

/** Live quote from the OWLI server, or the honest "no live quote" state when quotes are not available. */
@Composable
private fun LiveQuoteSection(state: CurrencyConverterUiState, model: CurrencyConverterViewModel, reference: ConfirmedRate?) {
    val typography = LocalOwliTypography.current
    val colors = LocalOwliColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun Action(label: Int, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = state.canRequestQuote, shape = LocalOwliShapes.current.button,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)) { Text(stringResource(label)) }
    }
    @Composable fun Problem(message: Int) {
        Text(stringResource(message), style = typography.body, color = MaterialTheme.colorScheme.error)
        Action(R.string.cur_live_retry, model::requestQuote)
    }
    if (!state.liveQuotesAvailable) {
        // Mock Demo never contacts the server: say why, instead of implying a phone problem.
        Text(stringResource(if (state.demo) R.string.cur_live_demo_title else R.string.cur_result_unavailable_title), style = typography.bodyStrong)
        Text(stringResource(if (state.demo) R.string.cur_live_signin else R.string.cur_result_unavailable_body, state.baseCurrency), style = typography.caption)
    } else when (val live = state.liveQuote) {
        LiveQuote.Idle -> {
            Text(stringResource(R.string.cur_live_prompt), style = typography.body)
            Action(R.string.cur_live_get, model::requestQuote)
        }
        LiveQuote.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = colors.accent)
            Text(stringResource(R.string.cur_live_loading), style = typography.body)
        }
        is LiveQuote.Ready -> {
            val q = live.quote
            Text(formatTransactionAmount(q.convertedAmount, q.to), style = typography.heroAmount.copy(fontSize = typography.heroAmount.fontSize * 0.8f))
            Text(stringResource(R.string.cur_confirmed_row, q.from, formatRate(q.rate), q.to), style = typography.body)
            Text(stringResource(R.string.cur_live_rate_date, formatTransactionDate(q.rateDate)), style = typography.caption, color = muted)
            if (q.rateDate < q.requestedDate) Text(stringResource(R.string.cur_live_rate_earlier), style = typography.caption, color = muted)
            Text(stringResource(R.string.cur_live_quote_only), style = typography.caption, color = colors.accent)
            OutlinedButton(onClick = model::refresh, enabled = state.canRequestQuote, shape = LocalOwliShapes.current.button,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)) { Text(stringResource(R.string.cur_live_refresh)) }
        }
        LiveQuote.Offline -> Problem(R.string.cur_live_offline)
        LiveQuote.ProviderUnavailable -> Problem(R.string.cur_live_provider)
        LiveQuote.NotSupported -> Problem(R.string.cur_live_not_supported)
        LiveQuote.NotDeployed -> Problem(R.string.cur_live_not_deployed)
        LiveQuote.Failed -> Problem(R.string.cur_live_failed)
    }
    // A historical rate from the account stays a labelled reference, never a stand-in for a live quote.
    if (state.liveQuote !is LiveQuote.Ready) reference?.let { ref ->
        Text(stringResource(R.string.cur_result_reference, ref.currencyCode, formatRate(ref.rate), ref.baseCurrency,
            formatTransactionDate(ref.rateDate)), style = typography.caption, color = muted)
    }
}
