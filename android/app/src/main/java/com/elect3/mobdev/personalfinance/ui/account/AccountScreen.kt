package com.elect3.mobdev.personalfinance.ui.account

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.AccountProfile
import com.elect3.mobdev.personalfinance.data.local.AppearancePreference
import com.elect3.mobdev.personalfinance.ui.components.OwliMidnightPanel
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Signed in (with whatever profile the sign-in response carried) or Mock Demo. */
sealed interface AccountMode {
    data class SignedIn(val profile: AccountProfile?) : AccountMode
    data object Demo : AccountMode
}


/** Two-letter initials from a real name only; never invented. */
internal fun initials(name: String?): String = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.take(2)
    ?.joinToString("") { it.first().uppercaseChar().toString() }.orEmpty()

/**
 * Account (approved mockup), pushed over the Dashboard as a full-screen page. Every row uses existing app state:
 * the in-memory sign-in profile, the device theme preference, the existing Category settings and the real logout.
 */
@Composable
fun AccountDialog(
    mode: AccountMode,
    appearance: AppearancePreference,
    onAppearance: (AppearancePreference) -> Unit,
    onManageCategories: (() -> Unit)?,
    /** Real logout (signed in) or the existing demo exit; the owner disposes this screen when the session ends. */
    onLogout: () -> Unit,
    onClose: () -> Unit,
    /** Opens the read-only Currency converter page. Null hides the row. */
    onOpenCurrencyConverter: (() -> Unit)? = null,
    live: com.elect3.mobdev.personalfinance.data.remote.LiveAccountPlanRepository? = null,
    onSessionEnded: () -> Unit = {},
    onPasswordChanged: (Boolean) -> Unit = {},
    currencyPreferences: com.elect3.mobdev.personalfinance.domain.currency.AccountCurrencyPreferences? = null,
    isCurrent: () -> Boolean = { true },
    /** Called when a profile/password sheet closes so the owner re-reads the signed-in name. */
    onProfileChanged: () -> Unit = {},
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AccountScreen(mode, appearance, onAppearance, onManageCategories, onLogout, onClose, onOpenCurrencyConverter, live, onSessionEnded, onPasswordChanged, currencyPreferences, isCurrent, onProfileChanged)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    mode: AccountMode,
    appearance: AppearancePreference,
    onAppearance: (AppearancePreference) -> Unit,
    onManageCategories: (() -> Unit)?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOpenCurrencyConverter: (() -> Unit)? = null,
    live: com.elect3.mobdev.personalfinance.data.remote.LiveAccountPlanRepository? = null,
    onSessionEnded: () -> Unit = {},
    onPasswordChanged: (Boolean) -> Unit = {},
    currencyPreferences: com.elect3.mobdev.personalfinance.domain.currency.AccountCurrencyPreferences? = null,
    isCurrent: () -> Boolean = { true },
    onProfileChanged: () -> Unit = {},
) {
    val sheetNavigation = remember(currencyPreferences, live, mode is AccountMode.Demo) { AccountSheetNavigation() }
    val sheet = sheetNavigation.current
    val leaveAccount: () -> Unit = { sheetNavigation.leave(onBack) }
    var loggingOut by remember { mutableStateOf(false) }
    val demo = mode is AccountMode.Demo
    val profile = (mode as? AccountMode.SignedIn)?.profile
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—" }
    val debuggable = remember { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    BackHandler(enabled = !loggingOut) { sheetNavigation.back(onBack) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leaveAccount, enabled = !loggingOut, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.account_back))
            }
            Text(stringResource(R.string.account_title), style = typography.screenTitle, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            IdentityCard(mode)
            Group(R.string.account_group_account) {
                AccountRow(R.drawable.ic_acc_user, stringResource(R.string.account_profile), stringResource(when {
                    demo -> R.string.account_profile_sub_demo
                    profile?.fullName != null || profile?.email != null -> R.string.account_profile_sub
                    else -> R.string.account_profile_sub_missing })) { sheetNavigation.open(AccountSheet.Profile) }
                if (demo) {
                    RowDivider()
                    AccountRow(R.drawable.ic_acc_shield, stringResource(R.string.account_session),
                        stringResource(R.string.account_session_sub_demo)) { sheetNavigation.open(AccountSheet.Session) }
                }
            }
            Group(R.string.account_group_preferences) {
                AccountRow(R.drawable.ic_acc_palette, stringResource(R.string.account_appearance), null, value = stringResource(themeLabel(appearance))) { sheetNavigation.open(AccountSheet.Appearance) }
                RowDivider()
                AccountRow(R.drawable.ic_acc_globe, stringResource(R.string.account_currency), if (demo) stringResource(R.string.account_currency_sub) else null,
                    value = if (demo) "PHP" else profile?.baseCurrency ?: "—") { sheetNavigation.open(AccountSheet.Currency) }
            }
            if (onManageCategories != null || onOpenCurrencyConverter != null) Group(R.string.account_group_finance) {
                if (onManageCategories != null)
                    AccountRow(R.drawable.ic_cat_tag, stringResource(R.string.account_categories), stringResource(R.string.account_categories_sub), onClick = { sheetNavigation.leave(onManageCategories) })
                if (onManageCategories != null && onOpenCurrencyConverter != null) RowDivider()
                if (onOpenCurrencyConverter != null)
                    AccountRow(R.drawable.ic_acc_globe, stringResource(R.string.cur_entry_title),
                        stringResource(R.string.cur_entry_sub, com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies.all.size),
                        onClick = { sheetNavigation.leave(onOpenCurrencyConverter) })
            }
            if (!demo) Group(R.string.account_group_security) {
                AccountRow(R.drawable.ic_acc_key, stringResource(R.string.account_password), null, pill = if (live == null) stringResource(R.string.account_unavailable_pill) else null) { sheetNavigation.open(AccountSheet.Password) }
            }
            Group(R.string.account_group_about) {
                AccountRow(R.drawable.ic_cat_info, stringResource(R.string.account_about), stringResource(R.string.app_tagline), value = "v$version") { sheetNavigation.open(AccountSheet.About) }
            }
            val danger = scheme.error
            OutlinedButton(onClick = { sheetNavigation.open(AccountSheet.Logout) }, enabled = !loggingOut, shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, danger.copy(alpha = 0.55f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp).heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.ic_owli_logout), null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(if (demo) R.string.account_exit_demo else R.string.account_logout), style = typography.button)
            }
            Text(stringResource(R.string.account_version_line, version), style = typography.caption, color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        }
    }

    if (!demo && live != null && (sheet == AccountSheet.Profile || sheet == AccountSheet.Password)) {
        LiveAccountEditor(live, sheet == AccountSheet.Password, onClose = { sheetNavigation.dismiss(); onProfileChanged() }, onSessionEnded = onSessionEnded, onPasswordChanged = onPasswordChanged)
    }
    if (!demo && sheet == AccountSheet.Currency && currencyPreferences != null) {
        CurrencyPreferencesEditor(currencyPreferences, profile?.baseCurrency, isCurrent) { sheetNavigation.dismiss() }
    }
    sheet?.takeUnless { (!demo && it == AccountSheet.Currency && currencyPreferences != null) || !demo && live != null && (it == AccountSheet.Profile || it == AccountSheet.Password) }?.let { current ->
        ModalBottomSheet(onDismissRequest = { if (!loggingOut) sheetNavigation.dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val close: @Composable () -> Unit = { SheetButton(stringResource(R.string.account_close)) { sheetNavigation.dismiss() } }
                when (current) {
                    AccountSheet.Profile -> {
                        SheetTitle(R.string.account_profile)
                        Lead(stringResource(if (demo) R.string.account_profile_lead_demo else R.string.account_profile_lead))
                        Rows(listOf(
                            stringResource(R.string.account_name) to (if (demo) stringResource(R.string.account_demo_name) else profile?.fullName ?: stringResource(R.string.account_not_provided)),
                            stringResource(R.string.account_email) to (if (demo) stringResource(R.string.account_not_applicable) else profile?.email ?: stringResource(R.string.account_not_provided))))
                        close()
                    }
                    AccountSheet.Session -> {
                        SheetTitle(R.string.account_session)
                        Lead(stringResource(if (demo) R.string.account_session_lead_demo else R.string.account_session_lead))
                        Rows(listOf(
                            stringResource(R.string.account_status) to stringResource(if (demo) R.string.account_status_value_demo else R.string.account_status_value_signed_in),
                            stringResource(R.string.account_storage) to stringResource(R.string.account_storage_value)))
                        close()
                    }
                    AccountSheet.Appearance -> {
                        SheetTitle(R.string.account_appearance)
                        Lead(stringResource(R.string.account_appearance_lead))
                        listOf(AppearancePreference.Light to R.string.account_theme_light_sub, AppearancePreference.Dark to R.string.account_theme_dark_sub,
                            AppearancePreference.System to R.string.account_theme_system_sub).forEach { (pref, sub) ->
                            ThemeOption(stringResource(themeLabel(pref)), stringResource(sub), appearance == pref) { onAppearance(pref) }
                        }
                        close()
                    }
                    AccountSheet.Currency -> {
                        SheetTitle(R.string.account_currency)
                        Lead(stringResource(if (demo) R.string.account_currency_lead_demo else R.string.account_currency_lead))
                        Rows(buildList {
                            add(stringResource(R.string.account_currency_account) to (if (demo) "PHP (â‚±)" else profile?.baseCurrency ?: stringResource(R.string.account_not_provided)))
                            add(stringResource(R.string.account_currency_display) to stringResource(R.string.account_currency_display_value))
                            if (!demo) add(stringResource(R.string.account_rates) to stringResource(R.string.account_rates_value))
                        })
                        close()
                    }
                    AccountSheet.Password -> {
                        SheetTitle(R.string.account_password)
                        Lead(stringResource(R.string.account_password_lead))
                        close()
                    }
                    AccountSheet.About -> {
                        SheetTitle(R.string.account_about)
                        Lead(stringResource(R.string.account_about_lead))
                        Rows(buildList {
                            add(stringResource(R.string.account_version) to version)
                            if (demo) add(stringResource(R.string.account_build) to stringResource(if (debuggable) R.string.account_build_debug else R.string.account_build_release))
                            add(stringResource(R.string.account_made_by) to stringResource(R.string.account_made_by_value))
                        })
                        close()
                    }
                    AccountSheet.Logout -> {
                        SheetTitle(if (demo) R.string.account_exit_title else R.string.account_logout_title)
                        Lead(stringResource(if (demo) R.string.account_exit_lead else R.string.account_logout_lead))
                        Button(onClick = { loggingOut = true; onLogout() }, enabled = !loggingOut, shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            if (loggingOut) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onError)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.account_logging_out), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            } else Text(stringResource(if (demo) R.string.account_exit_confirm else R.string.account_logout), style = typography.button)
                        }
                        if (!loggingOut) SheetButton(stringResource(R.string.account_cancel)) { sheetNavigation.dismiss() }
                    }
                }
            }
        }
    }
}

private fun themeLabel(p: AppearancePreference) = when (p) {
    AppearancePreference.Light -> R.string.account_theme_light
    AppearancePreference.Dark -> R.string.account_theme_dark
    AppearancePreference.System -> R.string.account_theme_system
}

@Composable
private fun IdentityCard(mode: AccountMode) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val demo = mode is AccountMode.Demo
    val profile = (mode as? AccountMode.SignedIn)?.profile
    val name = if (demo) stringResource(R.string.account_demo_name) else profile?.fullName
    val ini = if (demo) "" else initials(profile?.fullName)
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)) {
        OwliMidnightPanel {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0x1FF4EFFF)).border(1.5.dp, Color(0x73C6ADF1), CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (ini.isNotEmpty()) Text(ini, style = typography.screenTitle, color = Color(0xFFF4EFFF))
                    else Icon(painterResource(R.drawable.ic_owli_mark), null, tint = Color(0xFFF4EFFF), modifier = Modifier.size(34.dp))
                }
                Text(name ?: stringResource(R.string.account_fallback_name), style = typography.sectionTitle, color = owli.onPanel,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                if (!demo) profile?.email?.let { Text(it, style = typography.caption, color = owli.onPanelMuted, textAlign = TextAlign.Center) }
                Row(Modifier.padding(top = 12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (demo) Color(0xFFFFD38A) else owli.panelIncome))
                    Text(stringResource(if (demo) R.string.account_status_demo else R.string.account_status_signed_in), style = typography.label, color = owli.onPanel)
                }
            }
        }
    }
}

@Composable
private fun Group(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        Text(stringResource(title).uppercase(), style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, LocalOwliColors.current.hairline)) {
            Column { content() }
        }
    }
}

@Composable private fun RowDivider() = HorizontalDivider(color = LocalOwliColors.current.hairline)

@Composable
private fun AccountRow(icon: Int, title: String, subtitle: String?, value: String? = null, pill: String? = null, onClick: () -> Unit) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(owli.tile), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = owli.tileContent, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = typography.bodyStrong, modifier = Modifier.weight(1f, fill = false))
                pill?.let { Text(it.uppercase(), style = typography.micro, color = owli.accent,
                    modifier = Modifier.clip(CircleShape).background(owli.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp)) }
            }
            subtitle?.let { Text(it, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        value?.let { Text(it, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(painterResource(R.drawable.ic_cat_chevr), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun SheetTitle(id: Int) = Text(stringResource(id), style = LocalOwliTypography.current.sectionTitle, modifier = Modifier.semantics { heading() })
@Composable private fun Lead(text: String) = Text(text, style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    Surface(shape = LocalOwliShapes.current.group, border = BorderStroke(1.dp, LocalOwliColors.current.hairline), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(top = 6.dp)) {
        Column {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(title: String, subtitle: String, on: Boolean, onClick: () -> Unit) {
    val owli = LocalOwliColors.current
    val ink = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) owli.accentSoft else owli.field)
        .border(if (on) 2.dp else 1.dp, if (on) ink else owli.hairline, RoundedCornerShape(16.dp))
        .clickable(role = Role.RadioButton, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { selected = on }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LocalOwliTypography.current.bodyStrong)
            Text(subtitle, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) ink else owli.hairline, CircleShape))
    }
}

@Composable
private fun SheetButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LocalOwliColors.current.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label, style = LocalOwliTypography.current.button) }
}
