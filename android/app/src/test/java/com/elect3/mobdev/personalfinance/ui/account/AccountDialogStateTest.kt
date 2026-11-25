package com.elect3.mobdev.personalfinance.ui.account

import com.elect3.mobdev.personalfinance.domain.currency.CurrencyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDialogStateTest {
    @Test fun aNewAccountVisitNeverInheritsAnOpenCurrencySheet() {
        val previous=AccountSheetNavigation();previous.open(AccountSheet.Currency)
        assertEquals(AccountSheet.Currency,previous.current)
        assertNull(AccountSheetNavigation().current)
    }
    @Test fun dismissalAndNavigationClearTheDialogBeforeLeaving() {
        val nav=AccountSheetNavigation();nav.open(AccountSheet.Currency);nav.dismiss();assertNull(nav.current)
        nav.open(AccountSheet.Currency);var left=false
        nav.leave{assertNull(nav.current);left=true}
        assertTrue(left);assertNull(nav.current)
    }
    @Test fun backClosesChildBeforeLeavingAccount() {
        val nav=AccountSheetNavigation();var exits=0;nav.open(AccountSheet.Currency)
        nav.back{exits++};assertEquals(0,exits);assertNull(nav.current)
        nav.back{exits++};assertEquals(1,exits)
    }
    @Test fun confirmationWaitsForSuccessfulPersistenceAndConcurrentSaveIsIgnored()=runTest {
        val persisted=CompletableDeferred<Boolean>();var writes=0
        val feedback=CurrencyPreferenceSaveFeedback({writes++;persisted.await()},{true})
        val job=launch{feedback.save(CurrencyPreferences("USD"))};runCurrent()
        assertEquals(CurrencyPreferenceSaveStatus.Saving,feedback.status.value)
        feedback.save(CurrencyPreferences("EUR"));assertEquals(1,writes)
        persisted.complete(true);job.join()
        assertTrue(feedback.status.value is CurrencyPreferenceSaveStatus.Saved)
    }
    @Test fun falseOrThrowingWritesNeverProduceSuccess()=runTest {
        val failed=CurrencyPreferenceSaveFeedback({false},{true});failed.save(CurrencyPreferences("USD"))
        assertEquals(CurrencyPreferenceSaveStatus.Failed,failed.status.value)
        val throwing=CurrencyPreferenceSaveFeedback({throw IllegalStateException("fixture")},{true})
        throwing.save(CurrencyPreferences("USD"));assertEquals(CurrencyPreferenceSaveStatus.Failed,throwing.status.value)
    }
    @Test fun staleSuccessTimeoutDoesNotEraseLaterFeedback()=runTest {
        val feedback=CurrencyPreferenceSaveFeedback({true},{true})
        feedback.save(CurrencyPreferences("USD"));val first=feedback.status.value as CurrencyPreferenceSaveStatus.Saved
        feedback.save(CurrencyPreferences("EUR"));val second=feedback.status.value as CurrencyPreferenceSaveStatus.Saved
        assertNotEquals(first,second);feedback.clearSuccess(first);assertEquals(second,feedback.status.value)
        feedback.clearSuccess(second);assertEquals(CurrencyPreferenceSaveStatus.Idle,feedback.status.value)
    }
    @Test fun changedSessionSuppressesConfirmationAndNewSessionCannotStartOldWrite()=runTest {
        var current=true;var writes=0;val gate=CompletableDeferred<Boolean>()
        val feedback=CurrencyPreferenceSaveFeedback({writes++;gate.await()},{current})
        val job=launch{feedback.save(CurrencyPreferences("USD"))};runCurrent();current=false;gate.complete(true);job.join()
        assertEquals(CurrencyPreferenceSaveStatus.Idle,feedback.status.value)
        feedback.save(CurrencyPreferences("EUR"));assertEquals(1,writes)
    }
    @Test fun initialStorageFailureIsVisibleAndCanRecover()=runTest {
        val feedback=CurrencyPreferenceSaveFeedback({true},{true},loadFailed=true)
        assertEquals(CurrencyPreferenceSaveStatus.Failed,feedback.status.value)
        feedback.save(CurrencyPreferences("USD"));assertTrue(feedback.status.value is CurrencyPreferenceSaveStatus.Saved)
    }
}
