package com.elect3.mobdev.personalfinance.ui.transactions

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionListScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Actions {
        var initial = 0
        var refresh = 0
        var more = 0
        var append = 0
        var restart = 0
    }

    private fun text(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun content(
        append: TransactionAppendState = TransactionAppendState.Ready,
        refreshing: Boolean = false,
        error: TransactionFailure? = null,
        rows: List<Transaction> = TransactionFixtures.items.take(1),
    ) = TransactionListUiState.Content(rows, 1, 45L, append, refreshing, error)

    private fun show(state: TransactionListUiState, actions: Actions = Actions()) {
        compose.setContent {
            PersonalFinanceTheme {
                TransactionListScreen(state, { actions.initial++ }, { actions.refresh++ },
                    { actions.more++ }, { actions.append++ }, { actions.restart++ })
            }
        }
    }

    private fun reveal(id: Int): SemanticsNodeInteraction {
        val value = text(id)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(value, substring = false))
        return compose.onNodeWithText(value, substring = false)
    }

    private fun assertProgress() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
    }

    @Test fun initialLoadingHasProgressAndPoliteAnnouncement() {
        show(TransactionListUiState.InitialLoading)
        reveal(R.string.transactions_loading).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertProgress()
    }

    @Test fun contentShowsBrandHeadingDemoAndRow() {
        show(content())
        reveal(R.string.app_name).assertIsDisplayed()
        reveal(R.string.app_tagline).assertIsDisplayed()
        reveal(R.string.transactions_demo_data).assertIsDisplayed()
        reveal(R.string.transactions_title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(TransactionFixtures.items.first().description!!, substring = true))
        compose.onNodeWithText(TransactionFixtures.items.first().description!!, substring = true).assertIsDisplayed()
    }

    @Test fun emptyStateRefreshInvokesCallback() {
        val actions = Actions()
        show(TransactionListUiState.Empty, actions)
        reveal(R.string.transactions_empty_message).assertIsDisplayed()
        reveal(R.string.transactions_refresh).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.refresh) }
    }

    @Test fun initialErrorRetryInvokesCallback() {
        val actions = Actions()
        show(TransactionListUiState.InitialError(TransactionFailure.Unavailable), actions)
        reveal(R.string.transactions_unavailable_message).assertIsDisplayed()
        reveal(R.string.transactions_retry).performClick()
        compose.runOnIdle { assertEquals(1, actions.initial) }
    }

    @Test fun refreshingRetainsRowsAndDisablesRefreshAndAppend() {
        show(content(refreshing = true))
        reveal(R.string.transactions_refresh).assertIsNotEnabled()
        reveal(R.string.transactions_refreshing).assertIsDisplayed()
        assertProgress()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(TransactionFixtures.items.first().description!!, substring = true))
        compose.onNodeWithText(TransactionFixtures.items.first().description!!, substring = true).assertIsDisplayed()
        reveal(R.string.transactions_load_more).assertIsNotEnabled()
    }

    @Test fun refreshFailureShowsSafeMessageAndRetry() {
        val actions = Actions()
        show(content(error = TransactionFailure.Unavailable), actions)
        val message = text(R.string.transactions_refresh_error_message, text(R.string.transactions_unavailable_message))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(message))
        compose.onNodeWithText(message).assertIsDisplayed()
        reveal(R.string.transactions_retry_refresh).performClick()
        compose.runOnIdle { assertEquals(1, actions.refresh) }
    }

    @Test fun suppliedRefreshSuccessReplacesRows() {
        val state = mutableStateOf<TransactionListUiState>(content(refreshing = true))
        compose.setContent {
            PersonalFinanceTheme { TransactionListScreen(state.value, {}, {}, {}, {}, {}) }
        }
        val oldDescription = TransactionFixtures.items.first().description!!
        val replacement = TransactionFixtures.items.last()
        compose.runOnIdle { state.value = content(append = TransactionAppendState.End, rows = listOf(replacement)) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(replacement.description!!, substring = true))
        compose.onNodeWithText(replacement.description!!, substring = true).assertIsDisplayed()
        compose.onNodeWithText(oldDescription, substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_refreshing)).assertDoesNotExist()
    }

    @Test fun appendReadyInvokesLoadMore() {
        val actions = Actions()
        show(content(), actions)
        // Diagnose attachment and expected content before attempting any scrolling.
        // These checks do not establish whether the host Activity was destroyed.
        compose.onRoot().assertExists("Compose root unavailable immediately after show()")
        compose.onNodeWithText(text(R.string.transactions_title), substring = false)
            .assertExists("Transaction heading missing before scrolling")
        compose.onNodeWithText(text(R.string.transactions_refresh), substring = false)
            .assertExists("Refresh action missing before scrolling")
            .assertHasClickAction()
        compose.onNode(hasScrollAction())
            .assertExists("Scrollable transaction list missing before scrolling")
        reveal(R.string.transactions_load_more).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.more) }
    }

    @Test fun appendLoadingShowsProgressWithoutLoadMore() {
        show(content(append = TransactionAppendState.Loading))
        reveal(R.string.transactions_loading_more).assertIsDisplayed()
        assertProgress()
        compose.onNodeWithText(text(R.string.transactions_load_more)).assertDoesNotExist()
    }

    @Test fun appendErrorInvokesRetry() {
        val actions = Actions()
        show(content(append = TransactionAppendState.Error(TransactionFailure.Unavailable)), actions)
        reveal(R.string.transactions_append_error_title).assertIsDisplayed()
        reveal(R.string.transactions_retry_append).performClick()
        compose.runOnIdle { assertEquals(1, actions.append) }
    }

    @Test fun appendEndHasNoLoadMoreAction() {
        show(content(append = TransactionAppendState.End))
        reveal(R.string.transactions_end).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_load_more)).assertDoesNotExist()
    }

    @Test fun availableSessionRestartInvokesCallback() {
        val actions = Actions()
        show(TransactionListUiState.SessionExpired(DemoRestartState.Available), actions)
        reveal(R.string.transactions_restart_available).assertIsDisplayed()
        reveal(R.string.transactions_restart).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.restart) }
    }

    @Test fun unsupportedSessionHasExplanationWithoutRestart() {
        show(TransactionListUiState.SessionExpired(DemoRestartState.Unsupported))
        reveal(R.string.transactions_restart_unsupported).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_restart)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_retry_restart)).assertDoesNotExist()
    }

    @Test fun failedReplacementAllowsExplicitRetry() {
        val actions = Actions()
        show(TransactionListUiState.SessionExpired(DemoRestartState.ReplacementFailed), actions)
        reveal(R.string.transactions_restart_failed).assertIsDisplayed()
        reveal(R.string.transactions_retry_restart).performClick()
        compose.runOnIdle { assertEquals(1, actions.restart) }
    }

    @Test fun lazyListCanScrollToLastTransactionAndFooter() {
        show(content(append = TransactionAppendState.End, rows = TransactionFixtures.items))
        val last = TransactionFixtures.items.last()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(last.description!!, substring = true))
        compose.onNodeWithText(last.description!!, substring = true).assertIsDisplayed()
        reveal(R.string.transactions_end).assertIsDisplayed()
    }

    private fun themeRendering(night: Boolean) {
        var observedNight: Boolean? = null
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                val actualNight = isSystemInDarkTheme()
                SideEffect { observedNight = actualNight }
                PersonalFinanceTheme { TransactionListScreen(content(), {}, {}, {}, {}, {}) }
            }
        }
        reveal(R.string.transactions_title).assertIsDisplayed()
        compose.runOnIdle { assertEquals(night, observedNight) }
    }

    @Test fun lightThemeRendersRepresentativeContent() = themeRendering(false)
    @Test fun darkThemeRendersRepresentativeContent() = themeRendering(true)
}
