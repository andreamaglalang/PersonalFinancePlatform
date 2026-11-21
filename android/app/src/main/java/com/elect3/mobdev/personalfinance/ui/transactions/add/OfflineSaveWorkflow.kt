package com.elect3.mobdev.personalfinance.ui.transactions.add

import com.elect3.mobdev.personalfinance.data.remote.TransactionCreationCoordinator
import com.elect3.mobdev.personalfinance.domain.repository.*

/** Harness-only capability. The production POST repository does not implement this type. */
internal class ControlledCreationRepository(
    private val respond: suspend (CreateTransactionRequest) -> CreationResult,
) : TransactionCreationRepository {
    override suspend fun create(request: CreateTransactionRequest) = respond(request)
}
internal class OfflineSaveWorkflow(private val controlled: ControlledCreationRepository) {
    fun coordinator(current: () -> Boolean, expired: () -> Unit) =
        TransactionCreationCoordinator(controlled, current, expired)
}
sealed interface FormSaveState {
    data object Idle : FormSaveState
    data object Submitting : FormSaveState
    data class Succeeded(val replayed: Boolean) : FormSaveState
    data class Failed(val reason: FinanceFailure) : FormSaveState
    data class OutcomeUnknown(val reason: UnknownCreationReason) : FormSaveState
    /** Saved on this device only, waiting to sync. Never presented as saved to the account. */
    data object QueuedOffline : FormSaveState
}
