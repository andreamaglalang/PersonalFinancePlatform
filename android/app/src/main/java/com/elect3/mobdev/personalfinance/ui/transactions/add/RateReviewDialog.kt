package com.elect3.mobdev.personalfinance.ui.transactions.add
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.elect3.mobdev.personalfinance.R
@Composable
internal fun RateReviewDialog(state: AddTransactionUiState, model: AddTransactionViewModel) {
    if (!state.rateLoading && !state.rateError && state.rateReview == null) return
    AlertDialog(onDismissRequest = model::cancelRateReview,
        title = { Text(stringResource(R.string.fx_review_title)) },
        text = { Column {
            if (state.rateLoading) { CircularProgressIndicator(); Text(stringResource(R.string.fx_review_loading)) }
            if (state.rateError) Text(stringResource(when(state.rateFailure) {
                com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.SecureConnection -> R.string.fx_review_secure
                com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.MissingSession, com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.SessionExpired -> R.string.fx_review_session
                com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.InvalidInput -> R.string.fx_review_invalid
                else -> R.string.fx_review_error
            }), color = MaterialTheme.colorScheme.error)
            state.rateReview?.let { q ->
                Text(stringResource(R.string.fx_review_rate, q.from, q.rate.stripTrailingZeros().toPlainString(), q.to))
                Text(stringResource(R.string.fx_review_date, q.rateDate.toString()))
                Text(stringResource(R.string.fx_review_equivalent, q.convertedAmount.toPlainString() + " " + q.to))
                Text(stringResource(R.string.fx_review_preview), style = MaterialTheme.typography.bodySmall)
            }
        } }, confirmButton = {
            if (state.rateReview != null) TextButton(onClick = model::confirmRateReview) { Text(stringResource(R.string.fx_review_save)) }
        }, dismissButton = { TextButton(onClick = model::cancelRateReview) { Text(stringResource(android.R.string.cancel)) } })
}
