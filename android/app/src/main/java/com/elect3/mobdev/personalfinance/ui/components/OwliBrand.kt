package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Decorative owl tile beside the visible, accessible brand name. */
@Composable
fun OwliBrand(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shapes = LocalOwliShapes.current
    val typography = LocalOwliTypography.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = shapes.brandTile,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_owli_mark),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.onPrimaryContainer,
                )
            }
        }
        Text(
            text = stringResource(R.string.app_name),
            style = typography.wordmark,
            color = colors.onBackground,
        )
    }
}
