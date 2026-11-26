package com.elect3.mobdev.personalfinance.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.data.local.room.OwliDatabase
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CreateTransactionRequest
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Room ledger on a real (in-memory) SQLite database on the device. */
@RunWith(AndroidJUnit4::class)
class RoomLedgerTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OwliDatabase::class.java).build()
    private val ledger = RoomLedger(db.transactions())
    @After fun close() = db.close()
    private val draft = TransactionDraft(TransactionType.Expense, 7, BigDecimal("12.34"), "PHP", LocalDate.of(2026, 11, 19), "Lunch")

    @Test fun pendingRowBecomesSyncedUnderTheSameClientIdAndKeepsExactAmounts() = runBlocking {
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(42, request, "Food")
        assertEquals(BigDecimal("12.34"), ledger.pending(42).single().draft.amount)
        val server = Transaction(501, request.clientId, draft.type, 7, "Food", draft.amount, "PHP", BigDecimal.ONE, draft.transactionDate,
            draft.amount, draft.transactionDate, "Lunch", Instant.EPOCH, null, null)
        ledger.recordSynced(42, listOf(server))
        assertTrue(ledger.pending(42).isEmpty())
        assertTrue(ledger.unsynced(42).first().isEmpty())
        assertEquals(1, ledger.history(42).size)
        ledger.enqueue(42, request, "Food") // re-queue after sync is ignored
        assertTrue(ledger.pending(42).isEmpty())
    }

    @Test fun accountsAreIsolatedAndRejectedRowsCanBeDiscarded() = runBlocking {
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(1, request, null)
        assertTrue(ledger.pending(2).isEmpty())
        ledger.markRejected(1, request.clientId)
        assertTrue(ledger.unsynced(1).first().single().rejected)
        ledger.discardUnsynced(1, request.clientId)
        assertTrue(ledger.unsynced(1).first().isEmpty())
    }

    @Test fun concurrentEnqueueRejectAndSyncNeverDowngradeConfirmedRows() = runBlocking {
        kotlinx.coroutines.coroutineScope {
            repeat(40) {
                val request = CreateTransactionRequest(UUID.randomUUID(), draft)
                val server = Transaction(501, request.clientId, draft.type, 7, "Food", draft.amount, "PHP", BigDecimal.ONE,
                    draft.transactionDate, draft.amount, draft.transactionDate, "Synthetic", Instant.EPOCH, null, null)
                val jobs = listOf(
                    launch(kotlinx.coroutines.Dispatchers.Default) { ledger.enqueue(42, request, "Food") },
                    launch(kotlinx.coroutines.Dispatchers.Default) { ledger.markRejected(42, request.clientId) },
                    launch(kotlinx.coroutines.Dispatchers.Default) { ledger.recordSynced(42, listOf(server)) },
                    launch(kotlinx.coroutines.Dispatchers.Default) { ledger.enqueue(42, request, "Food") }
                )
                jobs.forEach { it.join() }
                val row = db.transactions().find(42, request.clientId.toString())!!
                assertEquals("Synced", row.syncState)
                assertEquals("12.34", row.amount)
                assertEquals(501, row.transactionId)
                assertNull(db.transactions().find(43, request.clientId.toString()))
            }
        }
    }

    @Test fun replayDoesNotEraseRejectedStateOrOriginalDraft() = runBlocking {
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(42, request, "Food")
        ledger.markRejected(42, request.clientId)
        ledger.enqueue(42, CreateTransactionRequest(request.clientId, TransactionDraft(draft.type,draft.categoryId,BigDecimal("99.99"),draft.currencyCode,draft.transactionDate,draft.description)), "Other")
        val row = db.transactions().find(42, request.clientId.toString())!!
        assertEquals("Rejected", row.syncState)
        assertEquals("12.34", row.amount)
    }
}
