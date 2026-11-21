package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.*
import com.elect3.mobdev.personalfinance.data.local.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import com.elect3.mobdev.personalfinance.ui.categories.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineCategoryWorkflowTest {
    @get:Rule val main = MainDispatcherRule()
    private val store=ViewModelStore()
    @After fun close()=store.clear()
    private var next=0
    private inline fun <reified T:ViewModel> own(crossinline make:()->T):T =
        ViewModelProvider(store,object:ViewModelProvider.Factory{
            override fun <V:ViewModel> create(modelClass:Class<V>):V { @Suppress("UNCHECKED_CAST") return make() as V }
        })["vm"+next++,T::class.java]
    private val food=Category(7,"Synthetic food",TransactionType.Expense,null,true,true)
    private class Remote(var result: suspend (CategoryQuery)->FinanceResult<CategoryPage>):CategoryRepository {
        val queries=mutableListOf<CategoryQuery>()
        override suspend fun list(query:CategoryQuery):FinanceResult<CategoryPage>{queries+=query;return result(query)}
    }
    @Test fun cachedChooserAndRealFormCanQueueExpenseWhileManagementStaysOnline()=runTest(main.dispatcher){
        val cache=InMemoryCategoryCache();val ledger=InMemoryLedger()
        val remote=Remote{ q->FinanceResult.Success(CategoryPage(listOf(food),q.page,q.pageSize,1)) }
        val cached=CachedCategoryRepository(remote,cache,{1},{true},{"session-1"})
        cached.list(CategoryQuery(type=TransactionType.Expense))
        remote.result={FinanceResult.Failure(FinanceFailure.Unavailable,isConnectivityFailure=true)}
        val picker=own{CategoryCatalogViewModel(cached,null,InMemoryCategoryPresentationStore(),false,includeInactive=false)}
        val management=own{CategoryCatalogViewModel(remote,null,InMemoryCategoryPresentationStore(),false)}
        val creator=OfflineFirstCreationRepository(TransactionCreationRepository{
            CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure)
        },ledger,{1})
        val form=own{AddTransactionViewModel(cached,FormWorkflow.Authenticated,"PHP",accountCreation=creator)}
        advanceUntilIdle()
        assertTrue(picker.state.value.loaded);assertEquals(listOf(food),picker.active(TransactionType.Expense))
        assertEquals(FinanceFailure.Unavailable,management.state.value.error)
        assertTrue(remote.queries.any{it.includeInactive});assertTrue(remote.queries.any{!it.includeInactive})
        form.preselect(food);form.amount("12.34");form.submit();advanceUntilIdle()
        assertEquals(FormSaveState.QueuedOffline,form.state.value.saveState)
        assertEquals(1,ledger.pending(1).size);assertTrue(ledger.pending(2).isEmpty())
    }
    @Test fun expiredAuthCannotBeHiddenByWarmCategoryCache()=runTest(main.dispatcher){
        val cache=InMemoryCategoryCache();cache.merge(1,listOf(food))
        val remote=Remote{FinanceResult.Failure(FinanceFailure.SessionExpired)}
        val cached=CachedCategoryRepository(remote,cache,{1},{true},{"session-1"})
        val picker=own{CategoryCatalogViewModel(cached,null,InMemoryCategoryPresentationStore(),false,includeInactive=false)}
        val form=own{AddTransactionViewModel(cached,FormWorkflow.Authenticated,"PHP")}
        advanceUntilIdle()
        assertTrue(picker.state.value.invalidated);assertTrue(form.state.value.invalidated)
        assertTrue(picker.active(TransactionType.Expense).isEmpty())
    }
    @Test fun mixedOrChangingPaginationFailsInsteadOfPretendingCompleteChooserData()=runTest(main.dispatcher){
        val remote=Remote{q->
            val rows=if(q.page==1)(1..100).map{food.copy(categoryId=it)} else emptyList()
            FinanceResult.Success(CategoryPage(rows,q.page,q.pageSize,if(q.page==1)101 else 100))
        }
        val picker=own{CategoryCatalogViewModel(remote,null,InMemoryCategoryPresentationStore(),false,includeInactive=false)}
        advanceUntilIdle()
        assertEquals(FinanceFailure.InvalidResponse,picker.state.value.error)
        assertFalse(picker.state.value.loaded)
    }
}
