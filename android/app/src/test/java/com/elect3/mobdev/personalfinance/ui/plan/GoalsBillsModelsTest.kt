package com.elect3.mobdev.personalfinance.ui.plan

import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.auth.*
import com.elect3.mobdev.personalfinance.data.local.InMemoryPendingRequestStore
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.testutil.MutableAuthClock
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GoalsBillsModelsTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
    private val goal = """{"goalId":5,"name":"Emergency","targetAmount":10000.00,"savedAmount":2500.00,"remainingAmount":7500.00,"surplusAmount":0,
        "progressPercentage":25.00,"baseCurrency":"PHP","status":"Active","targetDate":"2030-06-30","version":"AAAAAAAAAAE="}"""
    private val bill = """{"billId":7,"name":"Internet","amount":1699.00,"currencyCode":"PHP","categoryId":3,"categoryName":"Utilities","frequency":"Monthly",
        "firstDueDate":"2030-01-05","nextDueDate":"2030-02-05","reminderDaysBefore":3,"isActive":true}"""
    private fun edit(base: String, key: String, value: String?): JsonObject = json(base).apply { if (value == null) remove(key) else add(key, JsonParser.parseString(value)) }
    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun overFundedGoalShowsSurplusNotNegativeRemaining() {
        val over = parseSavingsGoal(edit(edit(goal, "savedAmount", "10025.50").toString(), "remainingAmount", "-25.50"))
        assertEquals(R.string.goal_progress_over_target to BigDecimal("25.50"), goalProgressCaption(over))
        fails { parseSavingsGoal(edit(goal, "remainingAmount", "-25.50")) } // must equal target minus saved
        assertEquals(R.string.goal_progress_remaining to BigDecimal("7500.00"), goalProgressCaption(parseSavingsGoal(json(goal))))
        assertEquals(R.string.goal_progress_remaining to BigDecimal("0.00"), goalProgressCaption(parseSavingsGoal(edit(edit(goal, "savedAmount", "10000.00").toString(), "remainingAmount", "0.00"))))
    }

    @Test fun parsesValidGoalBillAndContribution() {
        val g = parseSavingsGoal(json(goal))
        assertEquals(SavingsGoal(5, "Emergency", BigDecimal("10000.00"), BigDecimal("2500.00"), BigDecimal("7500.00"), BigDecimal("25.00"), "PHP",
            GoalStatus.Active, LocalDate.of(2030, 6, 30), "AAAAAAAAAAE="), g)
        val b = parseRecurringBill(json(bill))
        assertEquals(BillFrequency.Monthly, b.frequency); assertEquals(LocalDate.of(2030, 2, 5), b.nextDueDate); assertEquals(3, b.reminderDaysBefore); assertTrue(b.isActive)
        val c = parseContribution(json("""{"contributionId":9,"amount":12.50,"contributedOn":"2030-01-01","note":"Bonus","baseCurrency":"PHP","version":"AAAAAAAAAAI="}"""))
        assertEquals(Contribution(9, BigDecimal("12.50"), LocalDate.of(2030, 1, 1), "Bonus", "PHP", "AAAAAAAAAAI="), c)
    }

    @Test fun optionalTargetDateAndNoteMayBeNullOrMissing() {
        assertNull(parseSavingsGoal(edit(goal, "targetDate", "null")).targetDate)
        assertNull(parseSavingsGoal(edit(goal, "targetDate", null)).targetDate)
        assertNull(parseContribution(json("""{"contributionId":9,"amount":1,"contributedOn":"2030-01-01","baseCurrency":"PHP","version":"v"}""")).note)
        assertEquals(GoalStatus.Cancelled, parseSavingsGoal(edit(goal, "status", "\"Cancelled\"")).status)
    }

    @Test fun rejectsInvalidOrMissingRequiredFields() {
        listOf("goalId" to null, "goalId" to "0", "goalId" to "\"5\"", "name" to "\" \"", "targetAmount" to "-1", "savedAmount" to null,
            "baseCurrency" to "\"php\"", "status" to "\"Paused\"", "targetDate" to "\"2030-13-01\"", "version" to null, "progressPercentage" to "\"25\"")
            .forEach { (k, v) -> fails { parseSavingsGoal(edit(goal, k, v)) } }
        listOf("billId" to null, "frequency" to "\"Daily\"", "reminderDaysBefore" to "31", "isActive" to "\"true\"", "nextDueDate" to null,
            "currencyCode" to "\"PESO\"", "categoryId" to "-3", "amount" to "-0.01", "categoryName" to null)
            .forEach { (k, v) -> fails { parseRecurringBill(edit(bill, k, v)) } }
        fails { parseContribution(json("""{"contributionId":9,"amount":1,"contributedOn":"2030-01-01","baseCurrency":"PHP"}""")) }
    }

    @Test fun pageMustMatchRequestedPageAndSize() {
        fun page(p: Int, size: Int, total: Long = 1) = json("""{"page":$p,"pageSize":$size,"totalCount":$total,"items":[$goal]}""")
        assertEquals(1, parsePlanPage(page(1, 50), 1, ::parseSavingsGoal).items.size)
        fails { parsePlanPage(page(2, 50), 1, ::parseSavingsGoal) }
        fails { parsePlanPage(page(1, 20), 1, ::parseSavingsGoal) }
        fails { parsePlanPage(page(1, 50, -1), 1, ::parseSavingsGoal) }
        fails { parsePlanPage(page(1, 50), 1, ::parseRecurringBill) } // a goal row is not a bill
    }

    @Test fun receiptsMatchOnlyTheExactRequest() {
        val id = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val payment = parsePaymentReceipt(json("""{"receipt":{"clientId":"$id","billId":7,"dueDate":"2030-02-05","amount":1699.00,"currencyCode":"PHP"}}"""))
        assertTrue(payment.matches(fields("clientId" to id.uppercase(), "dueDate" to "2030-02-05"), 7))
        assertFalse(payment.matches(fields("clientId" to id, "dueDate" to "2030-03-05"), 7))
        assertFalse(payment.matches(fields("clientId" to id, "dueDate" to "2030-02-05"), 8))
        val contribution = parseContributionReceipt(json("""{"receipt":{"clientId":"$id","goalId":5,"originalAmount":12.50,"originalContributedOn":"2030-01-01","originalNote":null},"deleted":true}"""))
        assertTrue(contribution.deleted)
        assertTrue(contribution.matches(fields("amount" to BigDecimal("12.5"), "contributedOn" to "2030-01-01", "note" to null, "clientId" to id), 5))
        assertFalse(contribution.matches(fields("amount" to BigDecimal("12.5"), "contributedOn" to "2030-01-01", "note" to "x", "clientId" to id), 5))
        fails { parsePaymentReceipt(json("""{"receipt":{"clientId":"not-a-uuid","billId":7,"dueDate":"2030-02-05","amount":1,"currencyCode":"PHP"}}""")) }
        fails { parseContributionReceipt(json("""{"receipt":{"clientId":"$id","goalId":5,"originalAmount":1,"originalContributedOn":"2030-01-01"}}""")) }
    }

    @Test fun billStatusAndPayAction() {
        val today = LocalDate.of(2030, 2, 5)
        val b = parseRecurringBill(json(bill))
        assertEquals(BillRowState(BillStatus.DueSoon, paymentPending = false, canPay = true), billRowState(b, today, emptySet()))
        assertEquals(BillStatus.Overdue, billStatus(b, today.plusDays(1)))
        assertEquals(BillStatus.DueSoon, billStatus(b, today.minusDays(3)))
        assertEquals(BillRowState(BillStatus.Upcoming, false, false), billRowState(b, today.minusDays(4), emptySet()))
        assertEquals(BillRowState(BillStatus.Inactive, false, false), billRowState(b.copy(isActive = false), today, emptySet()))
        // A stored uncertain payment is always offered for checking, never hidden or shown as paid.
        assertEquals(BillRowState(BillStatus.Inactive, true, true), billRowState(b.copy(isActive = false), today, setOf("recurring-bills/7/payments")))
        assertEquals(BillRowState(BillStatus.Upcoming, false, false), billRowState(b, today.minusDays(4), setOf("recurring-bills/8/payments")))
    }

    @Test fun goalActionsFollowStatusAndPendingContribution() {
        val g = parseSavingsGoal(json(goal))
        assertEquals(GoalRowState(contributionPending = false, canContribute = true, canEdit = true, canCancel = true), goalRowState(g, emptySet()))
        val cancelled = g.copy(status = GoalStatus.Cancelled)
        assertEquals(GoalRowState(false, false, false, false), goalRowState(cancelled, emptySet()))
        assertEquals(GoalRowState(true, true, false, false), goalRowState(cancelled, setOf("savings-goals/5/contributions")))
    }

    @Test fun listStateForEachState() {
        assertEquals(PlanListState.Loading, planListState(true, null, false, 0))
        assertEquals(PlanListState.Error, planListState(false, R.string.plan_failure_load, false, 0))
        assertEquals(PlanListState.Error, planListState(false, null, false, 0))
        assertEquals(PlanListState.Empty, planListState(false, null, true, 0))
        assertEquals(PlanListState.Content, planListState(false, null, true, 2))
    }

    @Test fun progressTextIsClampedWithOneDecimal() {
        assertEquals("25", goalProgressText(BigDecimal("25.00"), Locale.US))
        assertEquals("33.4", goalProgressText(BigDecimal("33.35"), Locale.US))
        assertEquals("100", goalProgressText(BigDecimal("140"), Locale.US))
    }

    @Test fun failuresMapToStringResources() {
        assertEquals(R.string.plan_failure_unknown_outcome, planFailureMessage(FinanceFailure.Timeout, writing = true))
        assertEquals(R.string.plan_failure_load, planFailureMessage(FinanceFailure.Timeout))
        assertEquals(R.string.plan_failure_conflict, planFailureMessage(FinanceFailure.Conflict, true))
        assertEquals(R.string.plan_failure_session, planFailureMessage(FinanceFailure.SessionExpired))
    }

    @Test fun pendingIdentitiesSurviveSimulatedProcessDeathAsPendingRows() = runTest {
        val store = InMemoryPendingRequestStore()
        val now = Instant.parse("2030-01-01T00:00:00Z")
        fun repository(respond: () -> FinanceHttpResponse): Pair<InMemoryAuthRepository, LiveAccountPlanRepository> {
            val auth = InMemoryAuthRepository(object : AuthApi {
                override suspend fun login(email: String, password: String) = ApiResult.Success(AuthPayload("access", "refresh", now.plusSeconds(900), now.plusSeconds(9000), AuthUser(42, "Fixture", "fixture@example.test", "PHP", listOf("User"))))
                override suspend fun logout(refreshToken: String) = ApiResult.Success(Unit)
            }, MutableAuthClock(now))
            return auth to LiveAccountPlanRepository(ApiConfiguration.https("https://localhost:7027/"), auth, FinanceHttpEngine { respond() }, FinanceWriteEngine { respond() }, store)
        }
        val (auth1, before) = repository { throw SocketTimeoutException() }
        auth1.login("fixture@example.test", "fixture-only")
        val payment = fields("clientId" to "3f2504e0-4f89-41d3-9a0c-0305e82c3301", "dueDate" to "2030-02-05")
        val contribution = fields("amount" to BigDecimal("12.50"), "contributedOn" to "2030-01-01", "note" to null, "clientId" to "9f2504e0-4f89-41d3-9a0c-0305e82c3309")
        assertTrue(before.save("recurring-bills/7/payments", payment) is FinanceResult.Failure)
        assertTrue(before.save("savings-goals/5/contributions", contribution) is FinanceResult.Failure)
        // New process: new session and repository over the same durable store.
        val (auth2, after) = repository { FinanceHttpResponse(500, "") }
        auth2.login("fixture@example.test", "fixture-only")
        val pending = after.pendingPaths()
        val billRow = billRowState(parseRecurringBill(json(bill)).copy(isActive = false), LocalDate.of(2030, 1, 1), pending)
        assertTrue(billRow.paymentPending && billRow.canPay)
        assertTrue(goalRowState(parseSavingsGoal(json(goal)), pending).contributionPending)
        // Retry uses the stored identity, exactly as persisted.
        assertEquals(payment, after.pendingRequest(billPaymentPath(7)))
        assertEquals(contribution, after.pendingRequest(goalContributionPath(5)))
    }

    @Test fun touchedUiFilesHaveNoHardCodedUserText() {
        val dir = File("src/main/java/com/elect3/mobdev/personalfinance/ui/plan")
        val literal = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        val words = Regex("[A-Za-z]{2,}.*\\s|\\s.*[A-Za-z]{2,}")
        listOf("LivePlanSection.kt", "GoalsBillsSections.kt", "GoalsBillsModels.kt").forEach { name ->
            val offenders = File(dir, name).readLines().map { it.substringBefore("//").trim() }
                .filter { !it.startsWith("import") && !it.startsWith("*") && !it.startsWith("/*") }
                .flatMap { line -> literal.findAll(line).map { it.value }.filter { words.containsMatchIn(it) || Regex("^\"[A-Za-z]").containsMatchIn(it) && line.contains("Text(" + it) }.toList() }
            assertTrue("$name: $offenders", offenders.isEmpty())
        }
    }
}
