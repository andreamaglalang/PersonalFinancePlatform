package com.elect3.mobdev.personalfinance.ui.app

import android.net.ConnectivityManager
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.MainActivity
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Opt-in device UI runtime steps: the real MainActivity against the local Development API (debug build).
 * Runs only with instrumentation arguments `owliUi=true`, `owliEmail`, `owliPassword` (synthetic account only).
 * Key facts are logged under the `OWLI-UI` tag so the database can be checked afterwards.
 */
class DeviceUiRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args = InstrumentationRegistry.getArguments()
    private val runId = System.currentTimeMillis()
    /** Genuine product defects observed during a step; the step continues and fails at the end. */
    private val defects = mutableListOf<String>()
    private fun arg(name: String) = requireNotNull(args.getString(name)) { "missing instrumentation argument $name" }
    private fun text(id: Int, vararg format: Any) = compose.activity.getString(id, *format)
    private fun php(value: String) = formatTransactionAmount(BigDecimal(value), "PHP")
    private fun log(message: String) = Log.i("OWLI-UI", message)

    @Before fun optIn() = assumeTrue("owliUi not set", args.getString("owliUi") == "true")

    // ---- helpers ----
    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(m: SemanticsMatcher, timeout: Long = 20_000) = try { compose.waitUntil(timeout) { exists(m) } } catch (e: Throwable) {
        compose.onAllNodes(isRoot()).printToLog("OWLI-UI", maxDepth = 40); throw AssertionError("not shown within $timeout ms: ${m.description}", e)
    }
    private fun field(label: String) = hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    /** Plan tabs may carry a count suffix ("Goals  2"). */
    private fun planTab(label: Int) = hasText(text(label), substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val clickable = hasClickAction()

    private fun hideKeyboard() {
        // Only fields with an explicit IME action can close the keyboard this way; dialog clicks are injected regardless.
        val focused = isFocused() and hasSetTextAction() and !SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, androidx.compose.ui.text.input.ImeAction.Default)
        if (exists(focused)) compose.onNode(focused).performImeAction()
        compose.waitForIdle()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    /** The Activity list's own "Refresh activity" accessibility action. */
    private fun refreshActivity() {
        val label = text(R.string.act_refresh_action)
        val action = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            .firstNotNullOf { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == label } }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }
    private fun back() { shell("input keyevent 4"); compose.waitForIdle() }

    private val logOut get() = hasText(text(R.string.auth_logout)) and clickable

    private fun signIn() {
        val login = field(text(R.string.auth_email))
        compose.waitUntil(30_000) { exists(login) || exists(logOut) }
        if (!exists(login)) { log("session restored; already signed in"); return }
        repeat(2) { attempt ->
            compose.onNode(login).performTextReplacement(arg("owliEmail"))
            compose.onNode(field(text(R.string.auth_password))).performTextReplacement(arg("owliPassword"))
            compose.onNode(field(text(R.string.auth_password))).performImeAction() // Done submits
            val limited = hasText(text(R.string.auth_rate_limited))
            compose.waitUntil(30_000) { exists(logOut) || exists(limited) }
            if (exists(logOut)) return
            check(attempt == 0) { "login still rate limited" }
            log("login rate limited; waiting 61 s"); Thread.sleep(61_000)
        }
    }

    private fun nav(label: Int) {
        compose.onNode(tab(text(label))).performClick()
        compose.waitForIdle()
    }

    /** The first node matching [m] that follows the node whose text is exactly [name] (tree order). */
    private fun after(name: String, m: SemanticsMatcher): SemanticsNodeInteraction {
        val title = hasText(name)
        val all = compose.onAllNodes(title or m)
        val nodes = all.fetchSemanticsNodes()
        val start = nodes.indexOfFirst { title.matches(it) && !m.matches(it) }
        check(start >= 0) { "no node named $name" }
        val index = (start + 1 until nodes.size).firstOrNull { m.matches(nodes[it]) } ?: error("nothing after $name matches ${m.description}")
        return all[index]
    }
    private fun textAfter(name: String, m: SemanticsMatcher): String? = runCatching {
        after(name, m).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    }.getOrNull()

    private fun displayDate(date: LocalDate) = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    // ---- 1 ----
    private fun networkOff() {
        shell("svc wifi disable"); shell("svc data disable")
        // adb reverse keeps localhost:7027 reachable without Wi-Fi/data, so also refuse loopback traffic to the API port.
        shell("su 0 iptables -I OUTPUT -o lo -p tcp --dport 7027 -j REJECT --reject-with tcp-reset")
        shell("su 0 ip6tables -I OUTPUT -o lo -p tcp --dport 7027 -j REJECT --reject-with tcp-reset")
    }
    private fun networkOn() {
        shell("su 0 iptables -D OUTPUT -o lo -p tcp --dport 7027 -j REJECT --reject-with tcp-reset")
        shell("su 0 ip6tables -D OUTPUT -o lo -p tcp --dport 7027 -j REJECT --reject-with tcp-reset")
        shell("svc wifi enable"); shell("svc data enable")
    }
    private fun connected(): Boolean = compose.activity.getSystemService(ConnectivityManager::class.java).activeNetwork != null

    @Test fun offlineExpenseSyncsOnceAfterReconnect() {
        val description = "ui-offline-$runId"
        log("offline expense description=$description amount=23.45 category=Groceries")
        signIn()
        nav(R.string.navigation_transactions)
        val waiting = hasText(text(R.string.offline_pending_title, 1))
        val anyWaiting = hasText("waiting to sync", substring = true)
        compose.waitForIdle()
        assertTrue("precondition: no queued items", !exists(anyWaiting))
        val confirmedRow = hasText(description, substring = true) and clickable
        try {
            networkOff()
            compose.waitUntil(20_000) { !connected() }
            compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
            val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
            waitFor(groceries)
            compose.onNode(groceries).performClick()
            waitFor(field(text(R.string.form_amount)))
            compose.onNode(field(text(R.string.form_amount))).performTextReplacement("23.45")
            compose.onNode(field(text(R.string.form_description))).performScrollTo().performTextReplacement(description)
            hideKeyboard()
            compose.onNodeWithText(text(R.string.form_save_expense)).assertIsEnabled().performClick()
            waitFor(hasText(text(R.string.tx_queued_title)), 30_000)
            compose.onNodeWithText(text(R.string.tx_back_dashboard)).performScrollTo().performClick()
            nav(R.string.navigation_transactions)
            waitFor(waiting)
            val pendingRow = compose.onNode(hasText(php("23.45"), substring = true) and !clickable).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" | ") { it.text }
            log("offline: pending banner row='$pendingRow'")
            if (!pendingRow.contains("Groceries")) defects += "offline queue banner row names the category '$pendingRow' instead of 'Groceries'"
            assertEquals("queued item must not be a confirmed Activity row", 0, compose.onAllNodes(confirmedRow).fetchSemanticsNodes().size)
            log("offline: banner shows '${text(R.string.offline_pending_title, 1)}', item pending (no confirmed row)")
        } finally {
            networkOn()
        }
        compose.waitUntil(30_000) { connected() }
        // Automatic sync on reconnect; fall back to the user's Sync now.
        runCatching { compose.waitUntil(30_000) { !exists(anyWaiting) } }.onFailure {
            log("no automatic sync within 30 s; tapping Sync now")
            compose.onNodeWithText(text(R.string.offline_sync_now)).performScrollTo().performClick()
            compose.waitUntil(30_000) { !exists(anyWaiting) }
        }
        runCatching { waitFor(confirmedRow) }.onFailure {
            refreshActivity()
            waitFor(confirmedRow)
        }
        assertEquals(1, compose.onAllNodes(confirmedRow).fetchSemanticsNodes().size)
        log("offline: after reconnect the banner is gone and one confirmed Activity row shows '$description'")
        // A second sync attempt (another reconnect fires the app's sync) and an explicit refresh: still exactly one.
        try { networkOff(); compose.waitUntil(20_000) { !connected() } } finally { networkOn() }
        compose.waitUntil(30_000) { connected() }
        Thread.sleep(3_000)
        refreshActivity()
        compose.waitUntil(20_000) { exists(confirmedRow) }
        assertEquals("exactly one synced row after a second sync attempt", 1, compose.onAllNodes(confirmedRow).fetchSemanticsNodes().size)
        assertTrue(!exists(anyWaiting))
        log("offline: synced; exactly one confirmed row '$description'")
        assertTrue("Product defects:\n" + defects.joinToString("\n"), defects.isEmpty())
    }

    // ---- 2 ----
    @Test fun goalAndContributionThroughUi() {
        val name = "UI Goal $runId"
        log("goal name=$name target=1000 contribution=25.00")
        signIn()
        nav(R.string.navigation_plan)
        compose.onNode(planTab(R.string.plan_tab_goals)).performClick()
        val add = hasText(text(R.string.plan_add_goal)) and clickable
        compose.waitUntil(20_000) { exists(add and isEnabled()) }
        compose.onNode(add).performClick()
        compose.onNode(field(text(R.string.plan_field_name))).performTextReplacement(name)
        compose.onNode(field(text(R.string.goal_field_target))).performTextReplacement("1000")
        hideKeyboard()
        compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        waitFor(hasText(name))
        val savedOf = hasText(" saved of ", substring = true)
        val before = text(R.string.goal_saved_of, php("0"), php("1000"))
        compose.waitUntil(20_000) { textAfter(name, savedOf) == before }
        log("goal created: '$before'")

        after(name, hasText(text(R.string.goal_contributions)) and clickable).performScrollTo().performClick()
        waitFor(hasText(text(R.string.contributions_empty)))
        compose.onNode(field(text(R.string.plan_field_amount))).performTextReplacement("25.00")
        hideKeyboard()
        val dateField = field(text(R.string.contribution_field_date))
        val offered = compose.onNode(dateField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        log("contribution form offered date='$offered' (device today=${LocalDate.now()}, UTC today=${LocalDate.now(ZoneOffset.UTC)})")
        compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        val saved = hasText(text(R.string.contribution_saved))
        val rejected = hasText(text(R.string.plan_failure_invalid))
        compose.waitUntil(20_000) { exists(saved) || exists(rejected) }
        if (exists(rejected)) {
            defects += "contribution with the form's default date '$offered' rejected: '${text(R.string.plan_failure_invalid)}'"
            log("DEFECT: ${defects.last()}; retrying with UTC today to verify the rest of the flow")
            compose.onNode(dateField).performTextReplacement(LocalDate.now(ZoneOffset.UTC).toString())
            compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        }
        waitFor(saved)
        waitFor(hasText(php("25.00"), substring = true) and hasAnyAncestor(isDialog()))
        compose.onNode(hasText(text(R.string.plan_close)) and hasAnyAncestor(isDialog())).performClick()
        val updated = text(R.string.goal_saved_of, php("25.00"), php("1000"))
        compose.waitUntil(20_000) { textAfter(name, savedOf) == updated }
        val remaining = textAfter(name, hasText("remaining", substring = true))
        assertEquals(text(R.string.goal_progress_remaining, "2.5", php("975.00")).substringAfter(" · "), remaining?.substringAfter(" · "))
        log("goal after contribution: '$updated', '$remaining'")
        assertTrue("Product defects:\n" + defects.joinToString("\n"), defects.isEmpty())
    }

    // ---- 3 ----
    @Test fun billAndPaymentThroughUi() {
        val name = "UI Bill $runId"
        val firstDue = LocalDate.now()
        log("bill name=$name amount=300 category=Groceries Monthly firstDue=$firstDue")
        signIn()
        nav(R.string.navigation_plan)
        compose.onNode(planTab(R.string.plan_tab_bills)).performClick()
        val add = hasText(text(R.string.plan_add_bill)) and clickable
        compose.waitUntil(20_000) { exists(add and isEnabled()) }
        compose.onNode(add).performClick()
        val dateField = field(text(R.string.bill_field_first_due))
        val offered = compose.onNode(dateField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        log("bill form offered first due='$offered'")
        compose.onNode(field(text(R.string.plan_field_name))).performTextReplacement(name)
        compose.onNode(field(text(R.string.plan_field_amount))).performTextReplacement("300")
        compose.onNode(dateField).performTextReplacement(offered.ifEmpty { firstDue.toString() })
        hideKeyboard()
        val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
        waitFor(groceries)
        compose.onNode(groceries).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.plan_option_selected, "Groceries"))).assertExists()
        compose.onNode(hasText(text(R.string.plan_option_selected, text(R.string.bill_frequency_monthly)))).assertExists()
        compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        waitFor(hasText(name))
        val schedule = hasText(text(R.string.bill_frequency_monthly) + " · ", substring = true)
        val due = LocalDate.parse(offered.ifEmpty { firstDue.toString() })
        compose.waitUntil(20_000) { textAfter(name, schedule)?.contains(displayDate(due)) == true }
        log("bill created: '${textAfter(name, schedule)}'")

        after(name, hasText(text(R.string.bill_record_payment)) and clickable).performScrollTo().performClick()
        waitFor(hasText(text(R.string.bill_pay_title)))
        compose.onNode(hasText(text(R.string.plan_confirm)) and hasAnyAncestor(isDialog())).performClick()
        waitFor(hasText(text(R.string.bill_payment_recorded, php("300.00"))), 30_000)
        compose.onNode(hasText(text(R.string.plan_close)) and hasAnyAncestor(isDialog())).performClick()
        val next = due.plusMonths(1)
        compose.waitUntil(20_000) { textAfter(name, schedule)?.contains(displayDate(next)) == true }
        log("bill after payment: '${textAfter(name, schedule)}' (payment ${php("300.00")} for $due)")
    }

    // ---- 4 ----
    @Test fun profileAndLogoutThroughUi() {
        val fullName = "UI Profile $runId"
        log("profile fullName=$fullName")
        signIn()
        nav(R.string.navigation_dashboard)
        compose.onNodeWithContentDescription(text(R.string.account_open)).performClick()
        waitFor(hasText(text(R.string.account_profile)) and clickable)
        compose.onNode(hasText(text(R.string.account_profile)) and clickable).performClick()
        waitFor(field("Display name"))
        compose.onNode(field("Display name")).performTextReplacement(fullName)
        hideKeyboard()
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        waitFor(hasText("Profile saved."))
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
        val shown = runCatching { waitFor(hasText(fullName), 10_000) }.isSuccess
        log("profile saved; new name shown on Account screen without re-login: $shown")
        compose.onNodeWithContentDescription(text(R.string.account_back)).performClick()
        waitFor(logOut)
        compose.onNode(logOut).performClick()
        waitFor(hasText(text(R.string.auth_welcome)))
        compose.onNodeWithText(text(R.string.auth_sign_in)).assertIsDisplayed()
        log("logged out; Login screen shown")
        if (!shown) {
            // Evidence for the report: does a fresh sign-in show the saved name?
            signIn()
            nav(R.string.navigation_dashboard)
            compose.onNodeWithContentDescription(text(R.string.account_open)).performClick()
            val afterRelogin = runCatching { waitFor(hasText(fullName), 15_000) }.isSuccess
            log("profile name shown on Account after a fresh sign-in: $afterRelogin")
        }
        assertTrue("Account screen did not show the saved full name '$fullName' after Profile saved.", shown)
    }

    // ---- 5 ----
    /**
     * Compose UI test 1.9 (BOM 2025.08.00) only exposes enableAccessibilityChecks() through the separate
     * ui-test-junit4-accessibility artifact, which this project does not depend on. Fallback: every clickable
     * node (merged tree) must expose a non-empty text, editable text or contentDescription.
     */
    /** Rotation: the app keeps the signed-in session and the half-filled form (found by the E2E run). */
    @Test fun configurationChangeKeepsSessionAndForm() {
        signIn()
        nav(R.string.navigation_transactions)
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
        waitFor(groceries); compose.onNode(groceries).performClick()
        waitFor(field(text(R.string.form_amount)))
        compose.onNode(field(text(R.string.form_amount))).performTextReplacement("12.34")
        compose.onNode(field(text(R.string.form_description))).performScrollTo().performTextReplacement("rotate-$runId")
        hideKeyboard()
        // A real rotation (and back), as a user turning the phone would.
        shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 1")
        Thread.sleep(2_500); compose.waitForIdle()
        val amount = runCatching { compose.onNode(field(text(R.string.form_amount))).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text }.getOrNull()
        val description = runCatching { compose.onNode(field(text(R.string.form_description))).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text }.getOrNull()
        shell("settings put system user_rotation 0"); Thread.sleep(1_500); compose.waitForIdle()
        log("rotation: amount='$amount' description='$description'")
        assertTrue("still signed in after rotation", !exists(field(text(R.string.auth_email))))
        assertEquals("amount kept across rotation", "12.34", amount)
        assertEquals("description kept across rotation", "rotate-$runId", description)
    }

    /** Opt-in `owliEmail2`: Back leaves an unsaved form without saving; switching accounts shows only the new data. */
    @Test fun backDiscardsUnsavedFormAndAccountSwitchIsIsolated() {
        val second = args.getString("owliEmail2"); assumeTrue("owliEmail2 not set", second != null)
        signIn()
        nav(R.string.navigation_transactions)
        val mine = hasText("ui-offline-", substring = true)
        refreshActivity(); waitFor(mine)
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
        waitFor(groceries); compose.onNode(groceries).performClick()
        compose.onNode(field(text(R.string.form_amount))).performTextReplacement("9.99")
        compose.onNode(field(text(R.string.form_description))).performScrollTo().performTextReplacement("back-$runId")
        hideKeyboard(); back(); compose.waitForIdle()
        // A draft with input asks before discarding (no silent loss); confirm like a user.
        waitFor(hasText(text(R.string.tx_discard_title)), 10_000)
        log("back: '${text(R.string.tx_discard_title)}' asked before leaving")
        compose.onNode(hasText(text(R.string.form_discard)) and hasClickAction()).performClick(); compose.waitForIdle()
        repeat(2) { if (!exists(tab(text(R.string.navigation_transactions)))) { back(); compose.waitForIdle() } }
        refreshActivity(); Thread.sleep(2_000)
        assertEquals("Back must not save the form", 0, compose.onAllNodes(hasText("back-$runId", substring = true)).fetchSemanticsNodes().size)
        log("back: unsaved form closed, nothing saved")
        nav(R.string.navigation_dashboard)
        waitFor(logOut); compose.onNode(logOut).performClick()
        val login = field(text(R.string.auth_email)); waitFor(login)
        compose.onNode(login).performTextReplacement(second!!)
        compose.onNode(field(text(R.string.auth_password))).performTextReplacement(arg("owliPassword"))
        compose.onNode(field(text(R.string.auth_password))).performImeAction()
        waitFor(logOut)
        nav(R.string.navigation_transactions); refreshActivity(); Thread.sleep(3_000)
        assertEquals("the previous account's transactions must not appear", 0, compose.onAllNodes(mine).fetchSemanticsNodes().size)
        log("switch: second account sees none of the first account's transactions")
    }

    // ---- Validation feedback (system-wide inspection) ----

    private fun shown(message: String) = waitFor(hasText(message, substring = true), 15_000)

    /** Reported defect and multi-field case on the real registration screen; nothing reaches the server. */
    @Test fun registrationNamesEveryUnmetRule() {
        val login = field(text(R.string.auth_email))
        compose.waitUntil(30_000) { exists(login) || exists(logOut) }
        if (exists(logOut)) { compose.onNode(logOut).performClick(); waitFor(login) }
        compose.onNode(hasText(text(R.string.register_entry), substring = true) and clickable).performScrollTo().performClick()
        val name = field(text(R.string.register_full_name)); val email = field(text(R.string.auth_email))
        val password = field(text(R.string.auth_password)); val confirm = field(text(R.string.register_confirm_password))
        waitFor(name)
        compose.onNode(name).performTextReplacement("Validation Member")
        compose.onNode(email).performTextReplacement("validation.$runId@example.test")
        compose.onNode(password).performTextReplacement("abcdefghijk1")
        compose.onNode(hasContentDescription(text(R.string.password_rule_state_needed, text(R.string.password_rule_upper)))).assertExists()
        compose.onNode(hasContentDescription(text(R.string.password_rule_state_done, text(R.string.password_rule_lower)))).assertExists()
        log("register: checklist shows uppercase still needed, lowercase done while typing")
        compose.onNode(confirm).performTextReplacement("abcdefghijk1")
        hideKeyboard()
        compose.onNode(hasText(text(R.string.register_submit)) and clickable).performScrollTo().performClick()
        val expected = "Password must contain an uppercase letter and a special character, such as ! or #."
        shown(expected); log("register: weak 12-char password -> '$expected'")
        val kept = compose.onNode(password).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text?.length
        assertEquals("password kept after an on-device check fails", 12, kept)
        assertTrue(!exists(hasText("at least 12 characters and match", substring = true)))

        compose.onNode(name).performTextReplacement("")
        compose.onNode(email).performTextReplacement("x")
        compose.onNode(password).performTextReplacement("abc")
        compose.onNode(confirm).performTextReplacement("abd")
        hideKeyboard()
        compose.onNode(hasText(text(R.string.register_submit)) and clickable).performScrollTo().performClick()
        for (message in listOf(text(R.string.register_error_full_name), text(R.string.register_error_email),
                "Password must contain at least 12 characters, an uppercase letter, a number, a special character, such as ! or # and at least 4 different characters.",
                text(R.string.register_error_password_mismatch))) { shown(message); log("register: all wrong -> '$message'") }
        // An outdated message disappears once its field is corrected.
        compose.onNode(email).performTextReplacement("validation.$runId@example.test")
        compose.waitUntil(5_000) { !exists(hasText(text(R.string.register_error_email))) }
        log("register: email message cleared after editing the email")
    }

    /** Wrong current password (server), weak and mismatched new password (device), each beside its field. */
    @Test fun changePasswordNamesTheProblem() {
        signIn()
        nav(R.string.navigation_dashboard)
        compose.onNodeWithContentDescription(text(R.string.account_open)).performClick()
        val entry = hasText(text(R.string.account_password)) and clickable
        waitFor(entry); compose.onAllNodes(entry).onFirst().performScrollTo().performClick()
        val current = field("Current password"); val fresh = field("New password"); val confirm = field("Confirm password")
        // The Account row and the dialog's button share the label; the editor dialog is the newest window.
        val submit = hasText("Change password") and hasClickAction()
        waitFor(current)
        compose.onNode(fresh).performTextReplacement("abcdefghijk1")
        compose.onNode(confirm).performTextReplacement("abcdefghijkX")
        hideKeyboard()
        compose.onAllNodes(submit).onLast().performClick()
        shown(text(R.string.password_error_current_empty))
        shown("Password must contain an uppercase letter and a special character, such as ! or #.")
        shown(text(R.string.register_error_password_mismatch))
        log("password: empty current, weak new and mismatch each named")
        compose.onNode(current).performTextReplacement("Wrong!Pass1234")
        compose.onNode(fresh).performTextReplacement("Another!Pass1234")
        compose.onNode(confirm).performTextReplacement("Another!Pass1234")
        hideKeyboard()
        compose.onAllNodes(submit).onLast().performClick()
        shown("Your current password is incorrect."); log("password: server says the current password is incorrect")
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
    }

    /** Plan editor: every problem named, including the exact amount rule. */
    @Test fun planEditorNamesAmountProblems() {
        signIn()
        nav(R.string.navigation_plan)
        compose.onNode(planTab(R.string.plan_tab_goals)).performClick()
        val add = hasText(text(R.string.plan_add_goal)) and clickable
        compose.waitUntil(20_000) { exists(add and isEnabled()) }
        compose.onNode(add).performClick()
        compose.onNode(field(text(R.string.goal_field_target))).performTextReplacement("10.555")
        hideKeyboard()
        compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        shown(text(R.string.plan_error_name)); shown(text(R.string.amount_error_decimals))
        log("plan: '${text(R.string.plan_error_name)} ${text(R.string.amount_error_decimals)}'")
        compose.onNode(field(text(R.string.goal_field_target))).performTextReplacement("0")
        compose.onNode(hasText(text(R.string.plan_save)) and hasAnyAncestor(isDialog())).performClick()
        shown(text(R.string.amount_error_positive)); log("plan: zero -> '${text(R.string.amount_error_positive)}'")
        compose.onNode(hasText(text(R.string.plan_close)) and hasAnyAncestor(isDialog())).performClick()
    }

    /** Opt-in `owliFxDown=true`, with the API unable to reach the FX provider: a JPY save stores nothing. */
    @Test fun foreignSaveDuringFxOutageSavesNothing() {
        assumeTrue("owliFxDown not set", args.getString("owliFxDown") == "true")
        val description = "ui-fxdown-$runId"
        signIn()
        nav(R.string.navigation_transactions)
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
        waitFor(groceries); compose.onNode(groceries).performClick()
        waitFor(field(text(R.string.form_amount)))
        compose.onNodeWithContentDescription(text(R.string.fx_currency_change, "PHP"), substring = true).performScrollTo().performClick()
        val jpy = hasText("JPY") and hasAnyAncestor(isDialog())
        waitFor(hasText(text(R.string.fx_currency_picker_title)))
        val option = jpy and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))[i].performScrollToNode(option) } }
        compose.onNode(option).performClick()
        waitFor(hasText(text(R.string.fx_amount_in, "JPY")))
        compose.onNode(field(text(R.string.form_amount))).performTextReplacement("1000")
        compose.onNode(field(text(R.string.form_description))).performScrollTo().performTextReplacement(description)
        hideKeyboard()
        compose.onNodeWithText(text(R.string.form_save_expense)).assertIsEnabled().performClick()
        waitFor(hasText(text(R.string.fx_conversion_server_failure)), 30_000)
        log("fx outage: form shows '${text(R.string.fx_conversion_server_failure)}'")
        val confirmedRow = hasText(description, substring = true) and clickable and !SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        repeat(3) { if (!exists(tab(text(R.string.navigation_transactions)))) { back(); Thread.sleep(1_000) } }
        nav(R.string.navigation_transactions)
        refreshActivity()
        Thread.sleep(3_000)
        assertEquals("no confirmed row after FX outage", 0, compose.onAllNodes(confirmedRow).fetchSemanticsNodes().size)
        log("fx outage: Activity has no row for $description")
    }

    /** Opt-in `owliSnapshot=yyyy-MM,income,expense,net,savings`: a worker-stored summary (C4) renders on Insights. */
    @Test fun storedMonthlySummaryOnInsights() {
        val spec = args.getString("owliSnapshot"); assumeTrue("owliSnapshot not set", spec != null)
        val (month, income, expense, net, savings) = spec!!.split(",")
        signIn()
        nav(R.string.navigation_insights)
        val expected = text(R.string.reports_snapshot_description,
            com.elect3.mobdev.personalfinance.ui.reports.reportMonthLabel(java.time.YearMonth.parse(month)),
            php(income), php(expense),
            com.elect3.mobdev.personalfinance.ui.reports.formatSignedReportAmount(BigDecimal(net), "PHP"), php(savings))
        Thread.sleep(3_000) // report + snapshots load
        val row = hasContentDescription(expected)
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(row)
        compose.onNode(row).assertIsDisplayed()
        log("snapshot row: $expected")
    }

    @Test fun accessibilityChecks() {
        signIn()
        val failures = mutableListOf<String>()
        fun audit(screen: String) {
            compose.waitForIdle()
            val nodes = compose.onAllNodes(clickable).fetchSemanticsNodes()
            nodes.filter { !it.hasName() }.forEach { failures += "$screen: unlabeled clickable ${describe(it)}" }
            log("a11y $screen: ${nodes.size} clickable nodes checked")
        }
        nav(R.string.navigation_dashboard); Thread.sleep(2_000); audit("Home")
        nav(R.string.navigation_transactions); Thread.sleep(2_000); audit("Activity")
        compose.onNodeWithContentDescription(text(R.string.navigation_add)).performClick()
        val groceries = hasText("Groceries") and clickable and hasAnyAncestor(isDialog())
        waitFor(groceries); audit("Add transaction · category picker")
        compose.onNode(groceries).performClick()
        waitFor(field(text(R.string.form_amount))); audit("Add transaction · form")
        back(); compose.waitForIdle()
        if (exists(hasAnyAncestor(isDialog()) and hasText("Groceries"))) back()
        compose.waitForIdle()
        nav(R.string.navigation_plan)
        compose.onNode(planTab(R.string.plan_tab_goals)).performClick()
        compose.waitUntil(20_000) { exists(hasText(text(R.string.plan_add_goal)) and isEnabled()) }; audit("Plan · Goals")
        compose.onNode(planTab(R.string.plan_tab_bills)).performClick()
        compose.waitUntil(20_000) { exists(hasText(text(R.string.plan_add_bill)) and isEnabled()) }; audit("Plan · Bills")
        nav(R.string.navigation_insights); Thread.sleep(3_000); audit("Insights")
        nav(R.string.navigation_dashboard)
        compose.onNodeWithContentDescription(text(R.string.account_open)).performClick()
        waitFor(hasText(text(R.string.account_profile)) and clickable); audit("Account")
        failures.forEach { log("a11y FAIL $it") }
        assertTrue("Accessibility failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun SemanticsNode.hasName(): Boolean {
        val c = config
        return c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
            c.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true ||
            c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true
    }
    private fun describe(node: SemanticsNode) =
        "id=${node.id} role=${node.config.getOrNull(SemanticsProperties.Role)} bounds=${node.boundsInRoot} tag=${node.config.getOrNull(SemanticsProperties.TestTag)}"
}
