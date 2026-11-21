package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.*
import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** Isolated fake-only host: no production POST engine, sockets, credentials or runtime wiring. */
class OfflineSaveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = ViewModelStore()
    private lateinit var form: AddTransactionViewModel
    private lateinit var list: TransactionListViewModel
    private lateinit var receipt: OfflineSaveReceipt
    private var open by mutableStateOf(true)
    private var expired by mutableStateOf(false)
    private var posts = 0
    private var reads = 0
    private var failRefresh = false
    private val requests = mutableListOf<CreateTransactionRequest>()
    private var reply: suspend (CreateTransactionRequest) -> CreationResult = { success(it) }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show(enabled: Boolean = true) {
        compose.runOnUiThread {
            list = TransactionListViewModel(object : TransactionRepository {
                override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
                    reads++
                    return if (failRefresh) TransactionPageResult.Failure(TransactionFailure.Unavailable)
                    else TransactionPageResult.Success(TransactionPage(TransactionFixtures.items.take(1), page, pageSize, 1))
                }
            })
            owner.put("list", list)
            receipt = OfflineSaveReceipt(list)
            form = AddTransactionViewModel(DemoCategoryRepository(), FormWorkflow.Authenticated, "PHP",
                { !expired }, { expired = true })
            owner.put("form", form)
            if (enabled) form.enableOfflineSave(OfflineSaveWorkflow(ControlledCreationRepository {
                posts++; requests += it; reply(it)
            }))
        }
        compose.setContent {
            PersonalFinanceTheme {
                if (expired) Text("SESSION_EXPIRED_TEST_LOGIN") else {
                    val confirmed by receipt.confirmed.collectAsStateWithLifecycle()
                    Column {
                        OfflineSaveReceiptBanner(confirmed)
                        TransactionListRoute(list, isAuthenticated = true)
                    }
                    if (open) Dialog(onDismissRequest = form::back,
                        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
                        BackHandler { form.back() }
                        val state by form.state.collectAsStateWithLifecycle()
                        AddTransactionScreen(form, state)
                        AddTransactionCompletionEffects(form, state, { open = false }, receipt::confirmAndRefresh)
                    }
                }
            }
        }
    }
    private fun valid() {
        compose.runOnIdle { form.amount("12.34"); form.category(1) }
    }
    private fun save() = compose.onNodeWithText(text(R.string.form_offline_save)).performClick()
    private fun assertAmountEditingUnavailable() {
        // Disabled CoreTextFields retain EditableText but intentionally omit SetText.
        compose.onNode(hasText(text(R.string.form_amount)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .assertExists()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
            .assert(!hasSetTextAction())
            .assertTextContains("12.34")
    }
    @After fun cleanup() { compose.runOnUiThread { owner.clear() } }

    @Test fun fakeSuccessClosesFormReturnsToTransactionsAndRefreshesOnlyOnce() {
        show(); valid(); save()
        compose.waitUntil(5000) { !open }
        compose.onNodeWithText(text(R.string.form_offline_success)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_account_data)).assertIsDisplayed()
        assertEquals(1, posts); assertEquals(2, reads)
    }
    @Test fun fakeSuccessFollowedByRefreshFailureStillShowsConfirmedOutcome() {
        reply = { failRefresh = true; success(it) }
        show(); valid(); save()
        compose.waitUntil(5000) { !open }
        compose.onNodeWithText(text(R.string.form_offline_success)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_refresh_error_title)).performScrollTo().assertIsDisplayed()
        assertEquals(1, posts); assertTrue(receipt.confirmed.value)
    }
    @Test fun unknownRetryKeepsOriginalClientIdAndReplayClosesForm() {
        reply = { CreationResult.UnknownOutcome(UnknownCreationReason.Timeout) }
        show(); valid(); save()
        compose.onNodeWithText(text(R.string.form_outcome_unknown)).assertIsDisplayed()
        assertAmountEditingUnavailable()
        reply = { success(it, true) }
        compose.onNodeWithText(text(R.string.form_retry_unchanged)).performClick()
        compose.waitUntil(5000) { !open }
        assertEquals(2, posts); assertEquals(requests.first().clientId, requests.last().clientId)
    }
    @Test fun unknownEditRequiresAcknowledgementAndKnownFailureKeepsDraft() {
        reply = { CreationResult.UnknownOutcome(UnknownCreationReason.Timeout) }
        show(); valid(); save()
        compose.onNodeWithText(text(R.string.form_edit_unknown)).performClick()
        compose.onNodeWithText(text(R.string.form_edit_unknown_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.form_acknowledge_edit)).performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.form_amount))).performTextReplacement("99")
        reply = { CreationResult.Failure(FinanceFailure.InvalidInput) }
        save()
        compose.onNodeWithText(text(R.string.form_save_failed)).assertIsDisplayed()
        assertTrue(open); assertEquals("99", form.state.value.amount)
        assertNotEquals(requests.first().clientId, requests.last().clientId)
    }
    @Test fun submittingDisablesFieldsAndBackCannotDiscardAnInFlightAttempt() {
        val pending = CompletableDeferred<CreationResult>()
        reply = { pending.await() }
        show(); valid(); save()
        compose.onNodeWithText(text(R.string.form_offline_saving)).assertIsNotEnabled()
        assertAmountEditingUnavailable()
        compose.onNodeWithText(text(R.string.form_back)).performClick()
        compose.onNodeWithText(text(R.string.form_discard_title)).assertDoesNotExist()
        assertTrue(open); assertEquals(1, posts)
        compose.runOnIdle { pending.complete(CreationResult.Failure(FinanceFailure.Unavailable)) }
        compose.onNodeWithText(text(R.string.form_save_failed)).assertIsDisplayed()
    }
    @Test fun runtimeStyleAuthenticatedFormWithoutCapabilityCannotSave() {
        show(enabled = false); valid()
        compose.onNodeWithText(text(R.string.form_save_unavailable_short)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.form_offline_notice)).assertDoesNotExist()
        assertEquals(0, posts); assertFalse(receipt.confirmed.value)
    }
    @Test fun fake401UsesSessionExpiryCallbackWithoutSuccessFeedback() {
        reply = { CreationResult.Failure(FinanceFailure.SessionExpired) }
        show(); valid(); save()
        compose.onNodeWithText("SESSION_EXPIRED_TEST_LOGIN").assertIsDisplayed()
        assertFalse(receipt.confirmed.value); assertEquals(1, posts)
    }
    private fun success(request: CreateTransactionRequest, replayed: Boolean = false): CreationResult.Success {
        val d = request.draft
        return CreationResult.Success(Transaction(42, request.clientId, d.type, d.categoryId, "Offline", d.amount,
            d.currencyCode, BigDecimal.ONE, d.transactionDate, d.amount, d.transactionDate, d.description,
            Instant.parse("2026-11-15T00:00:00Z"), null, null), replayed)
    }
}
