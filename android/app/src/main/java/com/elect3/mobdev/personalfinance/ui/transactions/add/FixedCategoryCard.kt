package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.ui.categories.CategoryDisc
import com.elect3.mobdev.personalfinance.ui.categories.CategoryTypePill
import com.elect3.mobdev.personalfinance.ui.categories.CategoryVisuals
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Category chosen on the picker screen; the form shows it with a Change action instead of a type toggle. */
data class FixedCategory(val category: Category, val iconKey: String, val onChange: () -> Unit, val onBack: () -> Unit)

@Composable
internal fun FixedCategoryCard(fixed: FixedCategory, type: TransactionType, enabled: Boolean) {
    Surface(shape = LocalOwliShapes.current.group, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CategoryDisc(fixed.iconKey, CategoryVisuals.displayHex(fixed.category))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(fixed.category.name, style = LocalOwliTypography.current.bodyStrong)
                CategoryTypePill(type)
            }
            val description = stringResource(R.string.cat_change_action, fixed.category.name)
            TextButton(onClick = fixed.onChange, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = description }) {
                Text(stringResource(R.string.cat_change), color = LocalOwliColors.current.accent, style = LocalOwliTypography.current.label)
            }
        }
    }
}
