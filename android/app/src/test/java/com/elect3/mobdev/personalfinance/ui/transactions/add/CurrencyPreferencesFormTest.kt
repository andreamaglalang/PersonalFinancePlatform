package com.elect3.mobdev.personalfinance.ui.transactions.add
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.domain.currency.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyPreferencesFormTest {
    @get:Rule val main=MainDispatcherRule()
    private val date=LocalDate.parse("2026-11-20")
    private fun quote(amount: BigDecimal, day: LocalDate=date)=ExchangeRateQuote("USD","PHP",amount,BigDecimal("56.12345679"),day,day,amount.multiply(BigDecimal("56.12345679")).setScale(2,java.math.RoundingMode.HALF_UP),"Frankfurter",Instant.EPOCH)
    @Test fun defaultCurrencyAndOverrideDoNotChangeAccountBase()=runTest(main.dispatcher) {
        val model=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",defaultCurrency="USD")
        val store=ViewModelStore().apply{put("m",model)}
        try {runCurrent();assertEquals("USD",model.state.value.currency);assertEquals("PHP",model.state.value.baseCurrency);assertTrue(model.state.value.foreignCurrency);assertFalse(model.state.value.dirty);model.currency("EUR");assertEquals("EUR",model.state.value.currency);assertTrue(model.state.value.dirty)}finally{store.clear()}
    }
    @Test fun demoIgnoresPreferencesAndUnsupportedDefaultFallsBack()=runTest(main.dispatcher) {
        for((workflow,code) in listOf(FormWorkflow.MockDemo to "USD",FormWorkflow.Authenticated to "XYZ")) {
            val model=AddTransactionViewModel(DemoCategoryRepository(),workflow,"PHP",defaultCurrency=code)
            val store=ViewModelStore().apply{put("m",model)}
            try{assertEquals("PHP",model.state.value.currency)}finally{store.clear()}
        }
    }
    @Test fun datedReviewIsReadOnlyUntilConfirmedAndDuplicateSaveIsBlocked()=runTest(main.dispatcher) {
        var reads=0;var writes=0;var requested: LocalDate?=null
        val gate=CompletableDeferred<Unit>()
        val model=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",
            accountCreation=TransactionCreationRepository{writes++;CreationResult.Failure(FinanceFailure.InvalidInput)},defaultCurrency="USD",reviewRateBeforeSave=true,
            historicalQuotes=HistoricalExchangeRateQuoteRepository{_,_,a,d->reads++;requested=d;gate.await();FinanceResult.Success(quote(a,d))})
        val store=ViewModelStore().apply{put("m",model)}
        try {runCurrent();model.amount("10.25");model.date(date);model.category(1);model.submit();model.submit();runCurrent();assertEquals(1,reads);assertEquals(0,writes);gate.complete(Unit);runCurrent();assertEquals(date,requested);assertEquals(BigDecimal("575.27"),model.state.value.rateReview?.convertedAmount);model.confirmRateReview();model.confirmRateReview();runCurrent();assertEquals(1,writes)}finally{store.clear()}
    }
    @Test fun staleDraftCannotBeSavedAndFailureNeverFabricatesQuote()=runTest(main.dispatcher) {
        var writes=0
        val model=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",accountCreation=TransactionCreationRepository{writes++;CreationResult.Failure(FinanceFailure.InvalidInput)},defaultCurrency="USD",reviewRateBeforeSave=true,
            historicalQuotes=HistoricalExchangeRateQuoteRepository{_,_,a,d->FinanceResult.Success(quote(a,d))})
        val store=ViewModelStore().apply{put("m",model)}
        try{runCurrent();model.amount("10.25");model.date(date);model.category(1);model.submit();runCurrent();model.amount("20");model.confirmRateReview();runCurrent();assertEquals(0,writes);assertNull(model.state.value.rateReview)}finally{store.clear()}
        val failed=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",defaultCurrency="USD",reviewRateBeforeSave=true,historicalQuotes=HistoricalExchangeRateQuoteRepository{_,_,_,_->FinanceResult.Failure(FinanceFailure.Unavailable)})
        val owner=ViewModelStore().apply{put("m",failed)}
        try{runCurrent();failed.amount("10");failed.category(1);failed.submit();runCurrent();assertTrue(failed.state.value.rateError);assertNull(failed.state.value.rateReview);assertFalse(failed.state.value.submitting)}finally{owner.clear()}
    }

    @Test fun phpBypassesReviewAndLogoutPreventsForeignSave()=runTest(main.dispatcher) {
        var reads=0;var writes=0;var current=true
        val provider=HistoricalExchangeRateQuoteRepository{_,_,a,d->reads++;FinanceResult.Success(quote(a,d))}
        val creator=TransactionCreationRepository{writes++;CreationResult.Failure(FinanceFailure.InvalidInput)}
        val php=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",accountCreation=creator,reviewRateBeforeSave=true,historicalQuotes=provider)
        val owner=ViewModelStore().apply{put("m",php)}
        try{runCurrent();php.amount("10");php.category(1);php.submit();runCurrent();assertEquals(0,reads);assertEquals(1,writes)}finally{owner.clear()}
        val foreign=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",isSessionCurrent={current},accountCreation=creator,defaultCurrency="USD",reviewRateBeforeSave=true,historicalQuotes=provider)
        val holder=ViewModelStore().apply{put("m",foreign)}
        try{runCurrent();foreign.amount("10");foreign.date(date);foreign.category(1);foreign.submit();runCurrent();current=false;foreign.confirmRateReview();runCurrent();assertEquals(1,writes)}finally{holder.clear()}
    }
}
