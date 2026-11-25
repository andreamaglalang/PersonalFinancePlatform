package com.elect3.mobdev.personalfinance.data.mock
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class DemoPlanStoreTest {
    private val day = LocalDate.of(2026, 11, 23)
    @Test fun progressAndRemainingAreCalculatedExactly() {
        val goal = DemoGoal(1, "Test", BigDecimal("100"), BigDecimal("40"))
        assertEquals(0.4f, goal.progress, 0.0001f)
        assertEquals(0, BigDecimal("60").compareTo(goal.remaining))
        val complete = goal.contribute(BigDecimal("90"))
        assertEquals(1f, complete.progress, 0f)
        assertEquals(0, complete.remaining.signum())
    }
    @Test fun invalidAmountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DemoGoal(1, "Test", BigDecimal.ZERO, BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { DemoGoal(1, "Test", BigDecimal.TEN, BigDecimal.ZERO).contribute(BigDecimal.ZERO) }
    }
    @Test fun dueStatusBoundariesAndPaidOverride() {
        fun bill(offset: Long) = DemoBill(1, "Test", BigDecimal.TEN, day.plusDays(offset))
        assertEquals(DemoBillStatus.Overdue, bill(-1).status(day))
        assertEquals(DemoBillStatus.DueSoon, bill(0).status(day))
        assertEquals(DemoBillStatus.DueSoon, bill(3).status(day))
        assertEquals(DemoBillStatus.Upcoming, bill(4).status(day))
        assertEquals(DemoBillStatus.Paid, bill(-1).copy(paid = true).status(day))
    }
    @Test fun demoActionsPersistUntilResetAndPaymentIsIdempotent() {
        val store = DemoPlanStore { day }
        val original = store.goals.value.first()
        store.contribute(original.id)
        assertEquals(0, original.saved.add(BigDecimal("500")).compareTo(store.goals.value.first().saved))
        store.markPaid(1); store.markPaid(1)
        assertTrue(store.bills.value.first().paid)
        assertEquals(day.plusDays(2), store.bills.value.first().nextDue)
        store.reset()
        assertEquals(original, store.goals.value.first())
        assertFalse(store.bills.value.first().paid)
    }
    @Test fun leavingDemoResetsPlanAndSeparateSessionsDoNotShareData() {
        val session = DemoSession()
        session.plan.markPaid(1); session.plan.contribute(1)
        assertFalse(DemoSession().plan.bills.value.first().paid)
        session.reset()
        assertFalse(session.plan.bills.value.first().paid)
        assertEquals(0, BigDecimal("24000").compareTo(session.plan.goals.value.first().saved))
    }
}
