package com.elect3.mobdev.personalfinance.data.remote

import androidx.test.platform.app.InstrumentationRegistry
import com.elect3.mobdev.personalfinance.data.auth.AuthResult
import com.elect3.mobdev.personalfinance.data.auth.InMemoryAuthRepository
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CreateTransactionRequest
import com.elect3.mobdev.personalfinance.domain.repository.CreationResult
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Opt-in cross-client runtime steps (roadmap R1/R5 gate). Each step runs only when the instrumentation argument
 * `owliStep` names it, so a shell script can interleave real Android data-layer calls with real MVC form actions
 * against one local Development API and a disposable runtime database. Synthetic accounts only.
 */
class CrossClientRuntimeTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(name: String): String = requireNotNull(args.getString(name)) { "missing instrumentation argument $name" }
    private fun step(name: String) = assumeTrue("not selected", args.getString("owliStep") == name)

    private val configuration = ApiConfiguration.https("https://localhost:7027/")

    private fun signedIn(): Pair<InMemoryAuthRepository, FinanceRepositories> = runBlocking {
        val auth = InMemoryAuthRepository(configuration)
        val result = auth.login(arg("owliEmail"), arg("owliPassword"))
        assertEquals("Android login with the shared account", AuthResult.Success, result)
        auth to FinanceRepositories(configuration, auth)
    }

    /** Android creates a transaction through the app's real creation repository. */
    @Test fun createTransaction() = runBlocking {
        step("create")
        val (_, finance) = signedIn()
        val draft = TransactionDraft(TransactionType.Expense, arg("owliCategoryId").toInt(), BigDecimal("123.45"), "PHP",
            LocalDate.parse(arg("owliDate")), "android-created")
        val result = finance.creation.create(CreateTransactionRequest(UUID.randomUUID(), draft))
        assertTrue("create result: $result", result is CreationResult.Success)
    }

    /** After MVC edited the transaction: Android sees MVC's edit, a stale version is refused, then a reloaded edit wins. */
    @Test fun seeMvcEditThenConflictThenReloadedEdit() = runBlocking {
        step("edit")
        val (_, finance) = signedIn()
        val id = arg("owliTransactionId").toInt()
        val current = (finance.transactionUpdates.get(id) as FinanceResult.Success).value
        assertEquals("Android reads MVC's edit", BigDecimal(arg("owliExpectAmount")).setScale(2), current.amount.setScale(2))
        val stale = finance.transactionUpdates.update(id, draftOf(current.categoryId, "300.00", current.transactionDate), arg("owliStaleVersion"))
        assertTrue("stale version must conflict: $stale", stale is FinanceResult.Failure && stale.reason == FinanceFailure.Conflict)
        val reloaded = (finance.transactionUpdates.get(id) as FinanceResult.Success).value
        val saved = finance.transactionUpdates.update(id, draftOf(current.categoryId, "300.00", current.transactionDate), reloaded.version!!)
        assertTrue("reloaded edit: $saved", saved is FinanceResult.Success && saved.value.amount.compareTo(BigDecimal("300.00")) == 0)
    }

    /** Android edits while an MVC edit form is open (the shell then submits that stale MVC form). */
    @Test fun editWhileMvcFormOpen() = runBlocking {
        step("edit-again")
        val (_, finance) = signedIn()
        val id = arg("owliTransactionId").toInt()
        val current = (finance.transactionUpdates.get(id) as FinanceResult.Success).value
        val saved = finance.transactionUpdates.update(id, draftOf(current.categoryId, "310.00", current.transactionDate), current.version!!)
        assertTrue("android edit: $saved", saved is FinanceResult.Success)
    }

    /** Android deletes; MVC must stop listing it. */
    @Test fun deleteTransaction() = runBlocking {
        step("delete")
        val (_, finance) = signedIn()
        val id = arg("owliTransactionId").toInt()
        val current = (finance.transactionUpdates.get(id) as FinanceResult.Success).value
        assertTrue(finance.transactionUpdates.delete(current) is FinanceResult.Success)
        val gone = finance.transactionUpdates.get(id)
        assertTrue("deleted row must be gone: $gone", gone is FinanceResult.Failure && gone.reason == FinanceFailure.NotFound)
    }

    private fun draftOf(categoryId: Int, amount: String, date: LocalDate) =
        TransactionDraft(TransactionType.Expense, categoryId, BigDecimal(amount), "PHP", date, "edited-on-android")
}
