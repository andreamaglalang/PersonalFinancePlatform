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
            // Hero: what OWLI supports.
            Surface(shape = shapes.hero, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.cur_hero_title, state.options.size), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.cur_hero_body, state.baseCurrency), style = typography.body)
                    SymbolStrip(state.options.map { it.symbol })
                    if (state.demo) Text(stringResource(R.string.cur_demo_note), style = typography.caption, color = colors.accent)
                }
            }
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
                    OutlinedButton(onClick = { focus.clearFocus(); model.swap() }, shape = shapes.chip, border = BorderStroke(1.dp, colors.hairline),
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                        Text("⇅  " + stringResource(R.string.cur_swap), style = typography.label)
                    }
                    CurrencyLine(stringResource(R.string.cur_to), state.to) { focus.clearFocus(); picking = "to" }
                }
            }
            ResultCard(state, model)
            if (onRecordTransaction != null) Button(onClick = onRecordTransaction, shape = shapes.button,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.cur_record_action), textAlign = TextAlign.Center) }
            HowItWorks(state.baseCurrency)
            ConfirmedRates(state)
            SupportedList(state)
        }
    }
    picking?.let { which ->
        CurrencyPickerDialog(state.options, if (which == "from") state.from else state.to, state.baseCurrency,
            onPick = { if (which == "from") model.from(it) else model.to(it); picking = null }, onDismiss = { picking = null })
    }
}

@Composable
private fun SymbolStrip(symbols: List<String>) {
    val colors = LocalOwliColors.current
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics { }) {
        symbols.forEach { s ->
            Box(Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).clip(CircleShape).background(colors.accentSoft).padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) { Text(s, style = LocalOwliTypography.current.label, color = colors.accent) }
        }
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
            Text(stringResource(R.string.fx_equals, formatTransactionAmount(q.amount, q.from), formatTransactionAmount(q.convertedAmount, q.to)), style = typography.bodyStrong)
            Text(stringResource(R.string.cur_confirmed_row, q.from, formatRate(q.rate), q.to), style = typography.body)
            Text(stringResource(R.string.cur_live_rate_date, formatTransactionDate(q.rateDate)), style = typography.caption, color = muted)
            if (q.rateDate < q.requestedDate) Text(stringResource(R.string.cur_live_rate_earlier), style = typography.caption, color = muted)
            Text(stringResource(R.string.cur_live_source, q.source, formatRetrieved(q.retrievedAt)), style = typography.caption, color = muted)
            Text(stringResource(R.string.cur_live_quote_only), style = typography.caption, color = colors.accent)
            OutlinedButton(onClick = model::refresh, enabled = state.canRequestQuote, shape = LocalOwliShapes.current.button,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)) { Text("↻  " + stringResource(R.string.cur_live_refresh)) }
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

/** Local time and date the quote was retrieved (device time zone). */
private fun formatRetrieved(at: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withZone(java.time.ZoneId.systemDefault()).format(at)

@Composable
private fun HowItWorks(base: String) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val steps = listOf(stringResource(R.string.cur_how_1), stringResource(R.string.cur_how_2, base), stringResource(R.string.cur_how_3),
        stringResource(R.string.cur_how_4, base), stringResource(R.string.cur_how_5), stringResource(R.string.cur_how_6))
    Surface(shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, colors.hairline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.cur_how_title), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = typography.label, color = colors.accent)
                    }
                    Text(step, style = typography.body, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConfirmedRates(state: CurrencyConverterUiState) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Surface(shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, colors.hairline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cur_confirmed_title), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            when {
                state.demo -> Text(stringResource(R.string.cur_confirmed_demo), style = typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.confirmed.isEmpty() -> Text(stringResource(R.string.cur_confirmed_empty), style = typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(stringResource(R.string.cur_confirmed_lead), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.confirmed.forEachIndexed { i, rate ->
                        if (i > 0) HorizontalDivider(color = colors.hairline)
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { }) {
                            Text(stringResource(R.string.cur_confirmed_row, rate.currencyCode, formatRate(rate.rate), rate.baseCurrency), style = typography.bodyStrong)
                            Text(stringResource(R.string.cur_confirmed_date, formatTransactionDate(rate.rateDate), formatTransactionDate(rate.transactionDate)),
                                style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportedList(state: CurrencyConverterUiState) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Surface(shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, colors.hairline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cur_supported_title), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            state.options.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                        Text(c.symbol, style = typography.label, color = colors.accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(c.code + " · " + c.name, style = typography.bodyStrong)
                        if (c.code == state.baseCurrency) Text(stringResource(R.string.cur_supported_account), style = typography.caption, color = colors.accent)
                    }
                }
            }
        }
    }
}
