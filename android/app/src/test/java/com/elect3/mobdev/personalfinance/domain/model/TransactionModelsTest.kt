package com.elect3.mobdev.personalfinance.domain.model

import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TransactionModelsTest {
    @Test
    fun representativeTransactionPreservesExactValuesAndNulls() {
        val row = TransactionFixtures.items.first()
        assertEquals(45, row.transactionId)
        assertEquals(UUID.fromString("00000000-0000-4000-8000-000000000045"), row.clientId)
        assertEquals(TransactionType.Expense, row.type)
        assertEquals(3, row.categoryId)
        assertEquals("Food", row.categoryName)
        assertEquals(BigDecimal("185.50"), row.amount)
        assertEquals("PHP", row.currencyCode)
        assertEquals(BigDecimal("1.00000000"), row.fxRate)
        assertEquals(row.amount, row.amountBase)
        assertEquals(LocalDate.of(2026, 11, 7), row.transactionDate)
        assertEquals(row.transactionDate, row.fxRateDate)
        assertEquals(Instant.parse("2026-11-07T12:00:00Z"), row.createdAtUtc)
        assertEquals("Lunch at the campus canteen", row.description)
        assertNull(row.billId)
        assertNull(row.billDueDate)
        assertNull(TransactionFixtures.items.single { it.transactionId == 44 }.description)
        val bill = TransactionFixtures.items.single { it.transactionId == 41 }
        assertEquals(101, bill.billId)
        assertEquals(LocalDate.of(2026, 11, 5), bill.billDueDate)
    }

    @Test
    fun typeWireMappingIsExactAndCaseSensitive() {
        for (type in TransactionType.entries) {
            assertEquals(type.name, type.wireValue)
            assertEquals(type, TransactionType.fromWireValue(type.wireValue))
        }
        assertThrows(IllegalArgumentException::class.java) { TransactionType.fromWireValue("income") }
        assertThrows(IllegalArgumentException::class.java) { TransactionType.fromWireValue("Transfer") }
    }

    @Test
    fun pageDefensivelyCopiesItemsAndPreservesLongCount() {
        val input = mutableListOf(TransactionFixtures.items.first())
        val page = TransactionPage(input, 1, 20, 3_000_000_000L)
        input.clear()
        assertEquals(1, page.items.size)
        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
        assertEquals(3_000_000_000L, page.totalCount)
        assertThrows(UnsupportedOperationException::class.java) {
            (page.items as MutableList<Transaction>).clear()
        }
    }

    @Test
    fun pageValidatesOnlyItsDeclaredInvariants() {
        assertThrows(IllegalArgumentException::class.java) { TransactionPage(emptyList(), 0, 20, 0) }
        for (size in listOf(0, 101)) {
            assertThrows(IllegalArgumentException::class.java) { TransactionPage(emptyList(), 1, size, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { TransactionPage(emptyList(), 1, 20, -1) }
        assertThrows(IllegalArgumentException::class.java) { TransactionPage(TransactionFixtures.items.take(2), 1, 1, 2) }
        assertTrue(TransactionPage(emptyList(), 4, 20, 45).items.isEmpty())
    }
}
