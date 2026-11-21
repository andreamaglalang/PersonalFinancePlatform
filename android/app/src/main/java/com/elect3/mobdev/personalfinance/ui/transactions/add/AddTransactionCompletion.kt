package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One receipt per controlled test form; confirmed save never becomes a failed save on GET error. */
internal class OfflineSaveReceipt(private val listing: TransactionListViewModel) {
    private val mutable = MutableStateFlow(false)
    val confirmed = mutable.asStateFlow()
    fun confirmAndRefresh() {
        if (mutable.value) return
        mutable.value = true
        listing.refreshAfterConfirmedCreation()
    }
}
@Composable internal fun OfflineSaveReceiptBanner(confirmed: Boolean) {
    if (confirmed) Text(stringResource(R.string.form_offline_success))
}
@Composable internal fun AddTransactionCompletionEffects(
    model: AddTransactionViewModel, state: AddTransactionUiState,
    onClose: () -> Unit, onSaved: () -> Unit,
) {
    LaunchedEffect(model, state.closeRequested) {
        if (state.closeRequested) {
            if (model.consumeSaveCompletion()) onSaved()
            onClose()
        }
    }
}
