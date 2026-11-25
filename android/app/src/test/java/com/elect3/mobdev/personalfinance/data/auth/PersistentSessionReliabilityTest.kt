package com.elect3.mobdev.personalfinance.data.auth

import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentSessionReliabilityTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private fun payload(access: String = "fixture-access", refresh: Char = 'a', user: Int = 1, expiry: Instant = now.plusSeconds(900)) =
        AuthPayload(access, refresh.toString().repeat(86), expiry, now.plusSeconds(86400), AuthUser(user,"Fixture","fixture@example.test","PHP",listOf("User")))
    private class MemoryStore : SessionStore {
        var value: StoredSession? = null
        var failRead = false
        var failWrite = false
        override fun read(): StoredSession? { check(!failRead); return value }
        override fun write(value: StoredSession) { check(!failWrite); this.value = value }
        override fun clear() { value = null }
    }
    private inner class Fixture(val store: MemoryStore = MemoryStore()) {
        val clock = MutableAuthClock(now)
        var loginPayload = payload()
        var refreshCalls = 0
        var logoutCalls = 0
        var response: suspend () -> ApiResult<AuthPayload> = { ApiResult.Success(payload("fixture-new",'b', expiry = now.plusSeconds(1800))) }
        val api = object : AuthApi {
            override suspend fun login(email: String,password: String) = ApiResult.Success(loginPayload)
            override suspend fun refresh(refreshToken: String): ApiResult<AuthPayload> { refreshCalls++; assertTrue(store.value!!.renewalUncertain); return response() }
            override suspend fun logout(refreshToken: String): ApiResult<Unit> { logoutCalls++; return ApiResult.Success(Unit) }
        }
        val repository = InMemoryAuthRepository(api,clock,store = store)
        suspend fun login() { assertEquals(AuthResult.Success,repository.login("fixture@example.test","fixture-only")) }
        fun reopened() = InMemoryAuthRepository(api,clock,store = store)
    }
    @Test fun loginIsDurableAndProcessRecreationRestoresWithoutNetwork() = runTest {
        val f=Fixture();f.login();assertNotNull(f.store.value)
        val reopened=f.reopened();assertEquals(SessionRestoration.Restored,reopened.restoreSession())
        assertTrue(reopened.hasActiveSession());assertEquals(0,f.refreshCalls)
        assertEquals("Bearer fixture-access",(reopened.financeSession() as FinanceResult.Success).value.authorization())
    }
    @Test fun uiDisposalDoesNotDeletePersistentSession() = runTest {
        val f=Fixture();f.login();f.repository.onUiDetached()
        assertEquals(SessionRestoration.Restored,f.reopened().restoreSession())
    }
    @Test fun expiredAccessRestoresAndRotatesUsingEligibleRefresh() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(901)
        val reopened=f.reopened();assertEquals(SessionRestoration.Restored,reopened.restoreSession())
        assertFalse(reopened.hasActiveSession());assertTrue(reopened.hasRecoverableSession())
        assertTrue(reopened.readyFinanceSession() is FinanceResult.Success)
        assertEquals(1,f.refreshCalls);assertFalse(f.store.value!!.renewalUncertain)
        assertEquals("Bearer fixture-new",(reopened.financeSession() as FinanceResult.Success).value.authorization())
    }
    @Test fun refreshedTokenIsStoredAndSurvivesAnotherProcessRecreation() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850);f.repository.refreshIfDue()
        val reopened=f.reopened();reopened.restoreSession();assertEquals("Bearer fixture-new",(reopened.financeSession() as FinanceResult.Success).value.authorization())
        assertEquals(1,f.refreshCalls)
    }
    @Test fun networkFailureBeforeBodyCanRecoverAfterExpiryWithoutLogout() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        f.response={ApiResult.Failure(AuthFailure.ConnectionFailed,RequestDelivery.NotSent)}
        f.repository.refreshIfDue();assertFalse(f.store.value!!.renewalUncertain)
        f.clock.now=now.plusSeconds(901)
        val result=f.repository.readyFinanceSession();assertEquals(FinanceFailure.Unavailable,(result as FinanceResult.Failure).reason)
        assertTrue(result.isConnectivityFailure)
        assertTrue(f.repository.hasRecoverableSession());assertEquals(0,f.logoutCalls)
        f.response={ApiResult.Success(payload("recovered",'c',expiry=now.plusSeconds(1800)))}
        f.clock.now=f.clock.now.plusSeconds(11)
        assertTrue(f.repository.readyFinanceSession() is FinanceResult.Success)
    }
    @Test fun safeRetryUsesBackoffAndNeverAuthorizesExpiredAccess() = runTest {
        val f=Fixture();f.login();val lease=(f.repository.financeSession() as FinanceResult.Success).value
        f.clock.now=now.plusSeconds(850);f.response={ApiResult.Failure(AuthFailure.Timeout,RequestDelivery.NotSent)}
        repeat(20){f.repository.refreshIfDue()};assertEquals(1,f.refreshCalls)
        f.clock.now=now.plusSeconds(901);assertThrows(IllegalStateException::class.java){lease.authorization()}
    }
    @Test fun rateLimitResponseIsSafeButBackedOff() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        f.response={ApiResult.Failure(AuthFailure.RateLimited,RequestDelivery.Rejected)}
        f.repository.refreshIfDue();f.clock.now=f.clock.now.plusSeconds(30);f.repository.refreshIfDue();assertEquals(1,f.refreshCalls)
        f.clock.now=f.clock.now.plusSeconds(31);f.repository.refreshIfDue();assertEquals(2,f.refreshCalls)
    }
    @Test fun uncertainDispatchNeverReusesTokenEvenAcrossRestoration() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        f.response={ApiResult.Failure(AuthFailure.Timeout,RequestDelivery.Unknown)}
        f.repository.refreshIfDue();assertTrue(f.store.value!!.renewalUncertain)
        val reopened=f.reopened();reopened.restoreSession();repeat(10){reopened.refreshIfDue()};assertEquals(1,f.refreshCalls)
        f.clock.now=now.plusSeconds(901);assertFalse(reopened.hasRecoverableSession())
        assertEquals(AuthFailure.RenewalUnconfirmed,reopened.sessionFailure())
    }
    @Test fun crashDuringRefreshLeavesDurableNonReplayableJournal() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val job=launch{f.repository.refreshIfDue()};runCurrent()
        assertTrue(f.store.value!!.renewalUncertain)
        val reopened=f.reopened();reopened.restoreSession()
        job.cancelAndJoin() // Simulates the old process disappearing before the new owner can run.
        reopened.refreshIfDue();assertEquals(1,f.refreshCalls);assertTrue(f.store.value!!.renewalUncertain)
    }
    @Test fun concurrentRefreshWaitersRotateOnlyOnce() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val requests=List(20){async{f.repository.readyFinanceSession()}};runCurrent();assertEquals(1,f.refreshCalls)
        gate.complete(ApiResult.Success(payload("shared",'d',expiry=now.plusSeconds(1800))))
        requests.awaitAll().forEach{assertTrue(it is FinanceResult.Success)};assertEquals(1,f.refreshCalls)
    }
    @Test fun actualRevocationClearsDurableSessionWithoutSendingLogout() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        f.response={ApiResult.Failure(AuthFailure.SessionExpired,RequestDelivery.Rejected)}
        f.repository.refreshIfDue();assertNull(f.store.value);assertFalse(f.repository.hasRecoverableSession())
        assertEquals(0,f.logoutCalls);assertEquals(SessionRestoration.None,f.reopened().restoreSession())
    }
    @Test fun logoutErasesPersistenceBeforeServerRequestAndCannotRestore() = runTest {
        val f=Fixture();f.login();f.repository.logout()
        assertNull(f.store.value);assertEquals(SessionRestoration.None,f.reopened().restoreSession());assertEquals(1,f.logoutCalls)
    }
    @Test fun expiredRefreshIsNotSentDuringRestoration() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(86401)
        assertTrue(f.reopened().restoreSession() is SessionRestoration.Failed);assertEquals(0,f.refreshCalls);assertNull(f.store.value)
    }
    @Test fun unreadableProtectedStorageIsNotReportedAsExpired() = runTest {
        val f=Fixture();f.store.failRead=true
        val result=f.reopened().restoreSession() as SessionRestoration.Failed;assertEquals(AuthFailure.SecureStorage,result.reason)
    }
    @Test fun journalWriteFailurePreventsRefreshDispatch() = runTest {
        val f=Fixture();f.login();f.store.failWrite=true;f.clock.now=now.plusSeconds(850)
        f.repository.refreshIfDue();assertEquals(0,f.refreshCalls);assertEquals(AuthFailure.SecureStorage,f.repository.sessionFailure())
    }
    @Test fun changedIdentityCannotReplaceStoredAccount() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        f.response={ApiResult.Success(payload("other",'z',user=2,expiry=now.plusSeconds(1800)))}
        f.repository.refreshIfDue();assertNull(f.store.value);assertFalse(f.repository.hasRecoverableSession())
    }
    @Test fun api401AfterRestartRefreshesAndRetriesGetOnce() = runTest {
        val f=Fixture();f.login();var reads=0
        val transport=AuthenticatedFinanceTransport(ApiConfiguration.https("https://localhost:7027/"),f.repository,FinanceHttpEngine {
            reads++;if(it.authorization()=="Bearer fixture-access") FinanceHttpResponse(401,"") else FinanceHttpResponse(200,"ok")
        })
        val result=transport.get(FinanceResource.Categories,""){it}
        assertEquals("ok",(result as FinanceResult.Success).value);assertEquals(1,f.refreshCalls);assertEquals(2,reads)
    }
    @Test fun writesAreNotReplayedAfterApi401ButSessionRecovers() = runTest {
        val f=Fixture();f.login();var writes=0
        val writer=FinanceWriter(f.repository,FinanceWriteEngine{writes++;FinanceHttpResponse(401,"")})
        val result=writer.send(WriteMethod.POST,java.net.URL("https://localhost:7027/api/categories"),"{}",setOf(201)){it}
        assertEquals(FinanceFailure.Unavailable,(result as FinanceResult.Failure).reason);assertEquals(1,writes);assertEquals(1,f.refreshCalls)
        assertTrue(f.repository.hasActiveSession())
    }
    @Test fun certificateFailureBeforeDispatchDoesNotBecomeSessionExpired() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(901)
        f.response={ApiResult.Failure(AuthFailure.SecureConnection,RequestDelivery.NotSent)}
        assertEquals(FinanceFailure.SecureConnection,(f.repository.readyFinanceSession() as FinanceResult.Failure).reason)
        assertTrue(f.repository.hasRecoverableSession());assertEquals(0,f.logoutCalls)
    }
    @Test fun overlappingOwnersShareOneRotationAndAdoptCommittedReplacement() = runTest {
        val f=Fixture();f.login();val other=f.reopened();other.restoreSession();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val first=async{f.repository.readyFinanceSession()};runCurrent()
        val second=async{other.readyFinanceSession()};runCurrent();assertEquals(1,f.refreshCalls)
        gate.complete(ApiResult.Success(payload("coordinated",'g',expiry=now.plusSeconds(1800))))
        assertTrue(first.await() is FinanceResult.Success);assertTrue(second.await() is FinanceResult.Success)
        assertEquals(1,f.refreshCalls);assertTrue((other.financeSession() as FinanceResult.Success).value.authorization()=="Bearer coordinated")
    }
    @Test fun staleOwnerCannotEraseAnotherAccountsStoredSession() = runTest {
        val f=Fixture();f.login();val other=f.reopened()
        f.loginPayload=payload("account-two",'q',user=2)
        assertEquals(AuthResult.Success,other.login("fixture@example.test","fixture-only"))
        f.repository.readyFinanceSession();f.repository.clearSession()
        assertEquals(2,f.store.value!!.payload.user.id)
        assertEquals(SessionRestoration.Restored,f.reopened().restoreSession())
    }
    @Test fun lateRotationCannotOverwriteNewAccountPersistence() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val first=async{f.repository.refreshIfDue()};runCurrent()
        val other=f.reopened();f.loginPayload=payload("account-two",'q',user=2,expiry=now.plusSeconds(1800))
        other.login("fixture@example.test","fixture-only")
        gate.complete(ApiResult.Success(payload("abandoned",'x',expiry=now.plusSeconds(1800))));first.await()
        assertEquals(2,f.store.value!!.payload.user.id)
        assertTrue(f.store.value!!.payload.accessToken=="account-two")
    }

    @Test fun profileNameUpdateDuringRotationDoesNotDiscardOrRevokeSession() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val rotation=async{f.repository.refreshIfDue()};runCurrent()
        f.repository.updateDisplayName(f.repository.sessionMarker(),"Updated profile")
        gate.complete(ApiResult.Success(payload("profile-safe",'p',expiry=now.plusSeconds(1800))));rotation.await()
        assertTrue((f.repository.financeSession() as FinanceResult.Success).value.authorization()=="Bearer profile-safe")
        assertEquals("Updated profile",f.store.value!!.payload.user.fullName);assertEquals(0,f.logoutCalls)
    }

    @Test fun unsentRefreshFailurePreservesConcurrentProfileMetadata() = runTest {
        val f=Fixture();f.login();f.clock.now=now.plusSeconds(850)
        val gate=CompletableDeferred<ApiResult<AuthPayload>>();f.response={gate.await()}
        val rotation=async{f.repository.refreshIfDue()};runCurrent()
        f.repository.updateDisplayName(f.repository.sessionMarker(),"Updated during outage")
        gate.complete(ApiResult.Failure(AuthFailure.ConnectionFailed,RequestDelivery.NotSent));rotation.await()
        assertEquals("Updated during outage",f.store.value!!.payload.user.fullName)
        assertFalse(f.store.value!!.renewalUncertain)
    }

}
