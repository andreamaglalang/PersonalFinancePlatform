package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/**
 * Noninteractive label identifying the data source, with no live-status semantics.
 * Defaults to the demonstration-data label; signed-in screens pass their account label.
 */
@Composable
fun OwliMockDataChip(modifier: Modifier = Modifier, text: String = stringResource(R.string.dashboard_demo_data)) {
    val colors = MaterialTheme.colorScheme
    val shapes = LocalOwliShapes.current
    val typography = LocalOwliTypography.current

    Surface(
        modifier = modifier,
        shape = shapes.chip,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = text,
            style = typography.micro,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}
