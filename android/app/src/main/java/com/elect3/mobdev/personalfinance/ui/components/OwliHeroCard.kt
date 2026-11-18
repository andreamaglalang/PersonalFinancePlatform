package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes

/** Lavender & Midnight gradient container for a screen's focal figure. Presentation only. */
@Composable
fun OwliHeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val owliColors = LocalOwliColors.current
    val shape = LocalOwliShapes.current.hero
    Surface(
        modifier = modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(owliColors.heroStart, owliColors.heroEnd)), shape),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
