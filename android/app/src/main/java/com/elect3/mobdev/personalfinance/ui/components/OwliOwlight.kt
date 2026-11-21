package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/*
 * Owlight building blocks (approved concept 4). Presentation only: callers supply every value,
 * label and action; nothing here formats money or decides financial meaning.
 */

/** Decorative, deterministic starfield for the Midnight Panel and Login header. Hidden from accessibility. */
@Composable
fun OwliStarfield(modifier: Modifier = Modifier, seed: Int = 7, count: Int = 24) {
    val stars = remember(seed, count) {
        var x = seed.toLong()
        fun next(): Float { x = (x * 9301 + 49297) % 233280; return x / 233280f }
        List(count) { Triple(next(), next(), next() > 0.88f) }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        stars.forEach { (fx, fy, big) ->
            drawCircle(Color.White.copy(alpha = if (big) 0.5f else 0.22f),
                radius = (if (big) 1.2f else 0.7f) * density, center = Offset(fx * size.width, fy * size.height))
        }
    }
}

/** The one dark "Midnight Panel" per screen: same dark gradient in both themes, light content. */
@Composable
fun OwliMidnightPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalOwliColors.current
    val shape = LocalOwliShapes.current.panel
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        contentColor = colors.onPanel,
        border = if (colors.panelBorder == Color.Transparent) null else BorderStroke(1.dp, colors.panelBorder),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(colors.panelStart, colors.panelEnd)))) {
            OwliStarfield(Modifier.matchParentSize(), seed = 131)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), content = content)
        }
    }
}

/**
 * Owl-eye ring: a share-of-flow ring with a centered label. [fraction] is display-only (0..1);
 * the caller computes it from exact values and supplies the rounded [label].
 */
@Composable
fun OwliEyeRing(fraction: Float, color: Color, label: String, modifier: Modifier = Modifier, size: Dp = 64.dp,
                labelColor: Color? = null, trackColor: Color? = null) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(trackColor ?: colors.panelTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(label, style = typography.label.copy(fontFeatureSettings = "tnum"), color = labelColor ?: colors.onPanel)
    }
}

/** Editorial "SAMPLE DATA" ribbon: strong top rule, hairline bottom, label and detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwliRibbon(title: String, detail: String?, modifier: Modifier = Modifier) {
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onBackground)
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = typography.overline, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(end = 10.dp))
            if (detail != null) Text(detail, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/** Hairline-grouped card holding list rows; use [OwliGroupDivider] between rows. */
@Composable
fun OwliGroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalOwliColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LocalOwliShapes.current.group,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, colors.hairline),
    ) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable
fun OwliGroupDivider() = HorizontalDivider(thickness = 1.dp, color = LocalOwliColors.current.hairline)

/** Uppercase month header inside a group card (e.g. "NOVEMBER 2026"). */
@Composable
fun OwliGroupHeader(text: String) {
    Text(text, style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp))
    OwliGroupDivider()
}

/** Section title (semantic heading) with optional trailing supporting text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwliSectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val typography = LocalOwliTypography.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = typography.sectionTitle, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(end = 8.dp).semantics { heading() })
        if (trailing != null) Text(trailing, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Round owl monogram (ink background). Decorative unless the caller adds semantics. */
@Composable
fun OwliOwlAvatar(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_owli_mark), contentDescription = null, tint = scheme.onPrimary,
            modifier = Modifier.size(size * 0.55f))
    }
}

/** Login crest: owl inside a softly glowing double ring, on the dark header. */
@Composable
fun OwliCrest(modifier: Modifier = Modifier) {
    val colors = LocalOwliColors.current
    Box(
        modifier.size(92.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0x47AE81F8), Color(0x00AE81F8))))
            .border(1.dp, colors.onPanel.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(78.dp).border(1.dp, colors.onPanel.copy(alpha = 0.08f), CircleShape))
        Icon(painterResource(R.drawable.ic_owli_mark), contentDescription = null, tint = colors.onPanel,
            modifier = Modifier.size(50.dp))
    }
}

/** Crescent moon drawn with two circles (decorative). */
@Composable
fun OwliMoon(modifier: Modifier = Modifier, size: Dp = 34.dp, backdrop: Color) {
    val colors = LocalOwliColors.current
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val r = this.size.minDimension * 0.38f
        drawCircle(colors.onPanel.copy(alpha = 0.85f), r, Offset(this.size.width * 0.47f, this.size.height * 0.5f))
        drawCircle(backdrop, r * 0.92f, Offset(this.size.width * 0.65f, this.size.height * 0.35f))
    }
}

/** Small rounded icon tile used in rows and quick actions. */
@Composable
fun OwliIconTile(icon: Int, modifier: Modifier = Modifier, size: Dp = 38.dp, iconSize: Dp = 19.dp, tint: Color? = null) {
    val colors = LocalOwliColors.current
    Box(modifier.size(size).clip(LocalOwliShapes.current.rowTile).background(colors.tile), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint ?: colors.tileContent, modifier = Modifier.size(iconSize))
    }
}

/** Panel-scoped content box: keeps children above the starfield. */
@Composable
fun OwliPanelFooter(content: @Composable BoxScope.() -> Unit) {
    val colors = LocalOwliColors.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(colors.panelRule))
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart, content = content)
    }
}

/** Row helper with Owlight list padding. */
@Composable
fun OwliListRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
