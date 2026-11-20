package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CachedCategoryRepositoryTest {
    private fun cat(id: Int, active: Boolean = true, type: TransactionType = TransactionType.Expense) =
        Category(id, "Synthetic $id", type, null, active, true)
    private fun CategoryRepository(block: suspend (CategoryQuery) -> FinanceResult<CategoryPage>) =
        object : CategoryRepository { override suspend fun list(query: CategoryQuery) = block(query) }
    private val query = CategoryQuery(type = TransactionType.Expense)
    private val offline = FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true)
    private fun page(rows: List<Category>, q: CategoryQuery = query, total: Long = rows.size.toLong()) =
        FinanceResult.Success(CategoryPage(rows, q.page, q.pageSize, total))
    private class State { var account: Int? = 1; var session: String? = "session-1"; var active = true }
    private fun repo(remote: CategoryRepository, cache: LocalCategoryCache, state: State = State()) =
        CachedCategoryRepository(remote, cache, { state.account }, { state.active }, { state.session })
    private fun rows(result: FinanceResult<CategoryPage>) = (result as FinanceResult.Success).value.items

    @Test fun onlineSuccessIsReturnedUnchangedAndCached() = runTest {
        val cache = InMemoryCategoryCache(); val result = page(listOf(cat(1)))
        assertSame(result, repo(CategoryRepository { result }, cache).list(query))
        assertEquals(listOf(cat(1)), cache.read(1, query.type, false))
    }
    @Test fun networkFailureUsesOnlyThisAccountsActiveCategories() = runTest {
        val cache = InMemoryCategoryCache()
        cache.merge(1, listOf(cat(1), cat(2, false), cat(3, type=TransactionType.Income)))
        cache.merge(2, listOf(cat(4)))
        val result = repo(CategoryRepository { offline }, cache).list(query)
        assertEquals(listOf(cat(1)), rows(result))
    }
    @Test fun serverFailuresNeverFallBackToWarmCache() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(1,listOf(cat(1)))
        val failure = FinanceResult.Failure(FinanceFailure.Unavailable)
        assertSame(failure, repo(CategoryRepository { failure },cache).list(query))
    }
    @Test fun authAndOtherFailuresAreNeverHidden() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(1,listOf(cat(1)))
        for (reason in listOf(FinanceFailure.MissingSession,FinanceFailure.SessionExpired,FinanceFailure.AccessDenied,
            FinanceFailure.InvalidResponse,FinanceFailure.RateLimited,FinanceFailure.NotFound,FinanceFailure.Conflict)) {
            val failure = FinanceResult.Failure(reason)
            assertSame(failure,repo(CategoryRepository { failure },cache).list(query))
        }
    }
    @Test fun coldCachePreservesOriginalConnectivityFailure() = runTest {
        assertSame(offline,repo(CategoryRepository { offline },InMemoryCategoryCache()).list(query))
    }
    @Test fun timeoutAndTlsFailuresUseCacheWithoutChangingTrustPolicy() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(1,listOf(cat(1)))
        for(reason in listOf(FinanceFailure.Timeout,FinanceFailure.SecureConnection))
            assertEquals(listOf(cat(1)),rows(repo(CategoryRepository { FinanceResult.Failure(reason) },cache).list(query)))
    }
    @Test fun inactiveQueryIsHonoredAndDoesNotReconcilePartialActiveData() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(1,listOf(cat(1),cat(2,false)))
        val r = repo(CategoryRepository { offline },cache)
        assertEquals(2,rows(r.list(query.copy(includeInactive=true))).size)
        assertEquals(1,rows(r.list(query)).size)
    }
    @Test fun completeActiveResponseReconcilesStaleRowsAndRetainsInactiveMetadata() = runTest {
        val cache = InMemoryCategoryCache(); cache.merge(1,listOf(cat(1),cat(3,false)))
        repo(CategoryRepository { page(listOf(cat(2))) },cache).list(query)
        assertEquals(listOf(cat(2)),cache.read(1,query.type,false))
        assertEquals(3,cache.read(1,query.type,true).size)
        assertFalse(cache.read(1,query.type,true).single{it.categoryId==1}.isActive)
    }
    @Test fun partialPageNeverDeletesOrDeactivatesUnseenRows() = runTest {
        val cache=InMemoryCategoryCache();cache.merge(1,listOf(cat(1),cat(2)))
        repo(CategoryRepository { page(listOf(cat(1)),total=5) },cache).list(query)
        assertEquals(listOf(1,2),cache.read(1,query.type,false).map{it.categoryId})
    }
    @Test fun secondPageDoesNotAuthorizeReplacement() = runTest {
        val cache=InMemoryCategoryCache();cache.merge(1,listOf(cat(1)))
        val q=query.copy(page=2,pageSize=1)
        repo(CategoryRepository { page(listOf(cat(2)),q,total=2) },cache).list(q)
        assertEquals(2,cache.read(1,query.type,false).size)
    }
    @Test fun completeExpenseSnapshotPreservesIncomeAndOtherAccounts() = runTest {
        val cache=InMemoryCategoryCache();cache.merge(1,listOf(cat(1),cat(2,type=TransactionType.Income)));cache.merge(2,listOf(cat(3)))
        repo(CategoryRepository { page(emptyList()) },cache).list(query)
        assertEquals(listOf(cat(2,type=TransactionType.Income)),cache.read(1,null,false))
        assertEquals(listOf(cat(3)),cache.read(2,null,false))
    }
    @Test fun cachedPaginationMatchesRequestedPageAndSize() = runTest {
        val cache=InMemoryCategoryCache();cache.merge(1,(1..5).map{cat(it)})
        val r=repo(CategoryRepository { offline },cache)
        val q=query.copy(page=2,pageSize=2)
        val result=(r.list(q) as FinanceResult.Success).value
        assertEquals(2,result.page);assertEquals(2,result.pageSize);assertEquals(5L,result.totalCount)
        assertEquals(listOf(3,4),result.items.map{it.categoryId})
    }
    @Test fun accountSwitchDuringNetworkCannotPopulateEitherAccountsCache() = runTest {
        val cache=InMemoryCategoryCache();val state=State()
        val result=repo(CategoryRepository { state.account=2;page(listOf(cat(1))) },cache,state).list(query)
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired),result)
        assertTrue(cache.read(1,null,true).isEmpty());assertTrue(cache.read(2,null,true).isEmpty())
    }
    @Test fun replacementSessionOnSameAccountRejectsOldNetworkResult() = runTest {
        val state=State();val cache=InMemoryCategoryCache()
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired),
            repo(CategoryRepository { state.session="session-2";page(listOf(cat(1))) },cache,state).list(query))
        assertTrue(cache.read(1,null,true).isEmpty())
    }
    @Test fun expiredSessionNeverUsesCache() = runTest {
        val state=State();val cache=InMemoryCategoryCache();cache.merge(1,listOf(cat(1)))
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired),
            repo(CategoryRepository { state.active=false;offline },cache,state).list(query))
    }
    @Test fun missingSessionDoesNotCallNetwork() = runTest {
        val state=State();state.session=null
        val remote=CategoryRepository { error("Must not call") }
        assertEquals(FinanceResult.Failure(FinanceFailure.MissingSession),repo(remote,InMemoryCategoryCache(),state).list(query))
    }
    @Test fun sessionChangeDuringCacheReadCannotReturnCachedRows() = runTest {
        val state=State();val base=InMemoryCategoryCache();base.merge(1,listOf(cat(1)))
        val cache=object:LocalCategoryCache by base {
            override suspend fun read(accountId:Int,type:TransactionType?,includeInactive:Boolean):List<Category>{
                state.session="replacement";return base.read(accountId,type,includeInactive)
            }
        }
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired),repo(CategoryRepository { offline },cache,state).list(query))
    }
    @Test fun sessionChangeDuringCacheWriteRejectsOnlineResult() = runTest {
        val state=State();val base=InMemoryCategoryCache()
        val cache=object:LocalCategoryCache by base {
            override suspend fun replaceType(accountId:Int,type:TransactionType,categories:List<Category>){
                base.replaceType(accountId,type,categories);state.active=false
            }
        }
        assertEquals(FinanceResult.Failure(FinanceFailure.SessionExpired),
            repo(CategoryRepository { page(listOf(cat(1))) },cache,state).list(query))
    }
    @Test fun cancellationDuringNetworkPropagates() = runTest {
        try{repo(CategoryRepository { throw CancellationException() },InMemoryCategoryCache()).list(query);fail("Cancelled result returned")}
        catch(_:CancellationException){}
    }
    @Test fun cancellationDuringCacheReadPropagates() = runTest {
        val cache=object:LocalCategoryCache by InMemoryCategoryCache(){
            override suspend fun read(accountId:Int,type:TransactionType?,includeInactive:Boolean):List<Category> = throw CancellationException()
        }
        try{repo(CategoryRepository { offline },cache).list(query);fail("Cancelled result returned")}catch(_:CancellationException){}
    }
    @Test fun cancellationDuringCacheWritePropagates() = runTest {
        val cache=object:LocalCategoryCache by InMemoryCategoryCache(){
            override suspend fun replaceType(accountId:Int,type:TransactionType,categories:List<Category>):Unit = throw CancellationException()
        }
        try{repo(CategoryRepository { page(listOf(cat(1))) },cache).list(query);fail("Cancelled result returned")}catch(_:CancellationException){}
    }
    @Test fun storageFailureDoesNotHideSuccessfulOnlineResponse() = runTest {
        val cache=object:LocalCategoryCache by InMemoryCategoryCache(){
            override suspend fun replaceType(accountId:Int,type:TransactionType,categories:List<Category>):Unit = error("Synthetic disk failure")
        }
        val result=page(listOf(cat(1)))
        assertSame(result,repo(CategoryRepository { result },cache).list(query))
    }
    @Test fun mismatchedOrDuplicatePagesCannotPoisonCache() = runTest {
        for(result in listOf(page(listOf(cat(1),cat(1))),page(listOf(cat(1)),query.copy(page=2)),
            page(listOf(cat(1,false))),page(listOf(cat(1,type=TransactionType.Income))))){
            val cache=InMemoryCategoryCache()
            assertEquals(FinanceResult.Failure(FinanceFailure.InvalidResponse),repo(CategoryRepository { result },cache).list(query))
            assertTrue(cache.read(1,null,true).isEmpty())
        }
    }
}
