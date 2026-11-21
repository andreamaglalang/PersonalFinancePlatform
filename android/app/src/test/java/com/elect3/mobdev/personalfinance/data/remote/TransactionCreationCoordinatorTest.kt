package com.elect3.mobdev.personalfinance.data.remote

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionCreationCoordinatorTest {
    private fun draft(amount: String = "12.34", description: String? = null) =
        TransactionDraft(TransactionType.Expense, 7, BigDecimal(amount), "PHP", LocalDate.parse("2026-11-15"), description)
    @Test fun unchangedUnknownRetryReusesIdAndCanonicalPayload() = runTest {
        val requests = mutableListOf<CreateTransactionRequest>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            requests += it; CreationResult.UnknownOutcome(UnknownCreationReason.Timeout)
        }, { true })
        coordinator.submit(draft("12.34")); coordinator.submit(draft("12.340"))
        assertEquals(2, requests.size); assertEquals(requests.first().clientId, requests.last().clientId)
        assertEquals(1, coordinator.unresolvedClientIds.size)
    }
    @Test fun materiallyChangedPayloadGetsNewIdAfterKnownRejection() = runTest {
        val requests = mutableListOf<CreateTransactionRequest>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            requests += it; CreationResult.Failure(FinanceFailure.InvalidInput)
        }, { true })
        coordinator.submit(draft()); coordinator.submit(draft("99"))
        assertNotEquals(requests.first().clientId, requests.last().clientId)
    }
    @Test fun changedPayloadDuringUnknownRequiresAcknowledgementAndRetainsOldRecoveryId() = runTest {
        val requests = mutableListOf<CreateTransactionRequest>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            requests += it; CreationResult.UnknownOutcome(UnknownCreationReason.Timeout)
        }, { true })
        coordinator.submit(draft())
        assertEquals(CreationResult.UnresolvedPrevious, coordinator.submit(draft("99")))
        assertEquals(1, requests.size)
        coordinator.submit(draft("99"), acknowledgeDifferentUnresolvedPayload = true)
        assertNotEquals(requests.first().clientId, requests.last().clientId)
        assertEquals(2, coordinator.unresolvedClientIds.size)
        coordinator.submit(draft())
        assertEquals(requests.first().clientId, requests.last().clientId)
    }
    @Test fun simultaneousDuplicateSubmitDoesNotDispatchTwice() = runTest {
        var calls = 0
        val response = CompletableDeferred<CreationResult>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository { calls++; response.await() }, { true })
        val first = async { coordinator.submit(draft()) }; runCurrent()
        assertEquals(CreationState.Submitting, coordinator.state.value)
        assertEquals(CreationResult.Busy, coordinator.submit(draft()))
        assertEquals(1, calls)
        response.complete(CreationResult.Failure(FinanceFailure.InvalidInput)); first.await()
    }
    @Test fun cancellationRecordsUnknownAndRetryUsesSameId() = runTest {
        val requests = mutableListOf<CreateTransactionRequest>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            requests += it
            if (requests.size == 1) awaitCancellation() else CreationResult.UnknownOutcome(UnknownCreationReason.Timeout)
        }, { true })
        val call = async { coordinator.submit(draft()) }; runCurrent(); call.cancelAndJoin()
        assertEquals(CreationState.Settled(CreationResult.UnknownOutcome(UnknownCreationReason.Interrupted)), coordinator.state.value)
        coordinator.submit(draft())
        assertEquals(requests.first().clientId, requests.last().clientId)
    }
    @Test fun explicitScopeInvalidationCancelsAndDropsDraftsAndIds() = runTest {
        var cancelled = false
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            try { awaitCancellation() } finally { cancelled = true }
        }, { true })
        val call = async { coordinator.submit(draft()) }; runCurrent()
        coordinator.invalidate()
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true), call.await())
        assertTrue(cancelled); assertEquals(CreationState.SessionEnded, coordinator.state.value)
        assertTrue(coordinator.unresolvedClientIds.isEmpty())
        assertEquals(CreationResult.Failure(FinanceFailure.SessionExpired), coordinator.submit(draft()))
    }
    @Test fun stale401CannotInvalidateReplacementAccount() = runTest {
        var current = true
        var callbacks = 0
        val response = CompletableDeferred<CreationResult>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            withContext(NonCancellable) { response.await() }
        }, { current }, { callbacks++ })
        val call = async { coordinator.submit(draft()) }; runCurrent()
        current = false
        response.complete(CreationResult.Failure(FinanceFailure.SessionExpired))
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.SessionChanged, true), call.await())
        assertEquals(0, callbacks); assertEquals(CreationState.SessionEnded, coordinator.state.value)
    }
    @Test fun current401DelegatesToExistingSessionOwnerAndClearsAttemptState() = runTest {
        var callbacks = 0
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            CreationResult.Failure(FinanceFailure.SessionExpired)
        }, { true }, { callbacks++ })
        assertEquals(CreationResult.Failure(FinanceFailure.SessionExpired), coordinator.submit(draft()))
        assertEquals(1, callbacks); assertEquals(CreationState.SessionEnded, coordinator.state.value)
        assertTrue(coordinator.unresolvedClientIds.isEmpty())
    }
    @Test fun boundedAttemptHistoryDoesNotEvictUnresolvedIdsSilently() = runTest {
        var calls = 0
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            calls++; CreationResult.UnknownOutcome(UnknownCreationReason.Timeout)
        }, { true })
        repeat(8) { coordinator.submit(draft((it + 1).toString()), true) }
        assertEquals(CreationResult.AttemptLimit, coordinator.submit(draft("99"), true))
        assertEquals(8, coordinator.unresolvedClientIds.size); assertEquals(8, calls)
    }
    @Test fun freshCoordinatorRepresentsExplicitNewFormIntentAndUsesNewId() = runTest {
        val ids = mutableListOf<UUID>()
        val fake = TransactionCreationRepository { ids += it.clientId; CreationResult.Failure(FinanceFailure.InvalidInput) }
        TransactionCreationCoordinator(fake, { true }).submit(draft())
        TransactionCreationCoordinator(fake, { true }).submit(draft())
        assertNotEquals(ids.first(), ids.last())
    }
    @Test fun completedSubmitIsReturnedFromMemoryAndNeverDispatchedTwice() = runTest {
        var calls = 0
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            calls++
            val d = it.draft
            CreationResult.Success(com.elect3.mobdev.personalfinance.domain.model.Transaction(42, it.clientId,
                d.type, d.categoryId, "Offline", d.amount, d.currencyCode, BigDecimal.ONE,
                d.transactionDate, d.amount, d.transactionDate, d.description, java.time.Instant.parse("2026-11-15T00:00:00Z"), null, null), false)
        }, { true })
        val first = coordinator.submit(draft())
        assertSame(first, coordinator.submit(draft()))
        assertEquals(1, calls)
        coordinator.submit(draft("99")); assertEquals(2, calls)
    }
    @Test fun everyMaterialFieldChangeRequiresANewId() = runTest {
        val ids = mutableSetOf<UUID>()
        val coordinator = TransactionCreationCoordinator(TransactionCreationRepository {
            ids += it.clientId; CreationResult.Failure(FinanceFailure.InvalidInput)
        }, { true })
        val base = draft()
        val variants = listOf(base,
            TransactionDraft(TransactionType.Income, 7, base.amount, "PHP", base.transactionDate),
            TransactionDraft(base.type, 8, base.amount, "PHP", base.transactionDate),
            TransactionDraft(base.type, 7, base.amount, "USD", base.transactionDate),
            TransactionDraft(base.type, 7, base.amount, "PHP", base.transactionDate.plusDays(1)),
            draft(description = "Changed"), draft("99"))
        variants.forEach { coordinator.submit(it) }
        assertEquals(variants.size, ids.size)
    }
}
