package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.DashboardCategoryTotal
import com.elect3.mobdev.personalfinance.ui.categories.CategoryVisuals
import com.elect3.mobdev.personalfinance.ui.categories.categoryColor
import com.elect3.mobdev.personalfinance.ui.categories.isDarkSurface
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Neutral grey for the grouped remainder, distinct from every palette colour. */
internal const val OTHER_SLICE_HEX = "#8A84A3"

/**
 * One donut slice. Built from the SAME ranked totals and period expense total as the category list:
 * the first [WHERE_IT_WENT_LIMIT] categories individually, the rest as one "Other categories" slice.
 */
data class WhereSlice(
    val label: String,
    val total: BigDecimal,
    /** Exact share of the period's expenses (drawing only); the displayed text uses [share]. */
    val fraction: Float,
    val share: String,
    /** 1-based rank for listed categories; null for the grouped remainder. */
    val rank: Int?,
    val otherCount: Int,
    val hex: String,
)

/** Pure: slices for the donut. Empty when there is nothing to show (same rule as the list). */
internal fun whereSlices(ranked: List<DashboardCategoryTotal>, periodExpenses: BigDecimal): List<WhereSlice> {
    if (ranked.isEmpty() || periodExpenses.signum() <= 0) return emptyList()
    val shown = ranked.take(WHERE_IT_WENT_LIMIT)
    val rest = ranked.drop(WHERE_IT_WENT_LIMIT)
    val used = HashSet<String>()
    fun fraction(v: BigDecimal) = v.divide(periodExpenses, 6, RoundingMode.HALF_UP).toFloat()
    val slices = shown.mapIndexed { i, c ->
        WhereSlice(c.categoryName, c.total, fraction(c.total), categoryShareLabel(c.total, periodExpenses), i + 1, 0,
            distinctColor(c.categoryId, used))
    }
    if (rest.isEmpty()) return slices
    val restTotal = rest.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.total) }
    return slices + WhereSlice("", restTotal, fraction(restTotal), categoryShareLabel(restTotal, periodExpenses), null, rest.size, OTHER_SLICE_HEX)
}

/** The category's palette colour (same rule as Activity); the next free colour if an earlier slice already uses it. */
private fun distinctColor(categoryId: Int, used: MutableSet<String>): String {
    val palette = CategoryVisuals.palette
    val start = Math.floorMod(categoryId, palette.size)
    val hex = (palette.indices).map { palette[(start + it) % palette.size].hex }.first { it !in used }
    used += hex
    return hex
}

/** Slice colour for the current theme: lighter in dark mode, as in the mockup. */
@Composable
internal fun sliceColor(hex: String): Color {
    val base = categoryColor(hex)
    return if (isDarkSurface()) lerp(base, Color.White, 0.4f) else base
}

/** Start angle (degrees from 12 o'clock) and sweep of each slice, from the exact fractions. */
internal fun sliceAngles(slices: List<WhereSlice>): List<Pair<Float, Float>> {
    var acc = 0f
    return slices.map { s -> val start = acc * 360f; acc += s.fraction; start to s.fraction * 360f }
}

@Composable
internal fun WhereDonut(
    slices: List<WhereSlice>,
    periodTotal: BigDecimal,
    listedCount: Int,
    moreCount: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    periodName: String,
) {
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    val typography = LocalOwliTypography.current
    val colors = slices.map { sliceColor(it.hex) }
    val angles = sliceAngles(slices)
    val measurer = rememberTextMeasurer()
    val track = owli.hairline
    val labelFill = scheme.surface
    val labelText = scheme.onSurface
    val ink = scheme.primary
    val onInk = scheme.onPrimary
    val otherName = stringResource(R.string.dashboard_where_other_short)
    val sliceNames = slices.map { if (it.rank == null) stringResource(R.string.dashboard_where_other, it.otherCount) else it.label }
    val summary = stringResource(R.string.dashboard_where_chart_description, periodName, formatPhpAmount(periodTotal),
        slices.indices.joinToString("; ") { "${sliceNames[it]} ${formatPhpAmount(slices[it].total)}, ${slices[it].share}" })
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp, start = 14.dp, end = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(196.dp)
                // The chart is summarised as one description; the rows below are the accessible selection controls.
                .clearAndSetSemantics { contentDescription = summary }
                .pointerInput(slices) {
                    detectTapGestures { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = hypot(p.x - c.x, p.y - c.y) / (size.width / 200f)
                        if (r < 44f || r > 102f) return@detectTapGestures
                        // Angle clockwise from 12 o'clock, 0..360.
                        val deg = ((Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble())) + 360.0) % 360.0).toFloat()
                        val hit = angles.indexOfFirst { (s, w) -> deg >= s && deg < s + w }
                        if (hit >= 0) onSelect(if (selected == hit) null else hit)
                    }
                }) {
                val unit = size.width / 200f
                val radius = 60f * unit
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                drawCircle(track, radius = radius, style = Stroke(width = 22f * unit))
                val gap = if (slices.size > 1) 1.6f else 0f
                slices.forEachIndexed { i, _ ->
                    val (start, sweep) = angles[i]
                    // Tiny shares keep a small visible arc; positions still follow the exact cumulative shares.
                    val drawn = (sweep - gap).coerceAtLeast(1.4f)
                    val dim = selected != null && selected != i
                    drawArc(colors[i].copy(alpha = if (dim) 0.28f else 1f), startAngle = start - 90f + gap / 2f, sweepAngle = drawn,
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(width = (if (selected == i) 27f else 22f) * unit, cap = StrokeCap.Butt))
                }
                slices.forEachIndexed { i, s ->
                    val (start, sweep) = angles[i]
                    val mid = Math.toRadians((start + sweep / 2f).toDouble())
                    val at = Offset(center.x + (sin(mid) * 88f * unit).toFloat(), center.y - (cos(mid) * 88f * unit).toFloat())
                    val on = selected == i
                    val alpha = if (selected != null && !on) 0.4f else 1f
                    drawCircle(if (on) ink else labelFill, radius = 11f * unit, center = at, alpha = alpha)
                    drawCircle(if (on) ink else track, radius = 11f * unit, center = at, style = Stroke(1f * unit), alpha = alpha)
                    // Fixed size inside the fixed ring: does not grow with the font scale, so it never overflows its disc.
                    drawLabel(measurer, if (s.rank == null) "+" else String.format(java.util.Locale.ROOT, "%02d", s.rank), at,
                        (if (on) onInk else labelText).copy(alpha = alpha), (10f * unit).toSp())
                }
            }
            val sel = selected?.let { slices.getOrNull(it) }
            Column(Modifier.padding(horizontal = 52.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text((if (sel == null) stringResource(R.string.dashboard_where_center_expenses) else if (sel.rank == null) otherName else sel.label).uppercase(),
                    style = typography.overline, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
                Text(formatPhpAmount(sel?.total ?: periodTotal), style = typography.amountMedium, textAlign = TextAlign.Center)
                Text(if (sel != null) stringResource(R.string.dashboard_where_center_share, sel.share)
                    else if (moreCount > 0) stringResource(R.string.dashboard_where_center_count_more, listedCount, moreCount)
                    else androidx.compose.ui.res.pluralStringResource(R.plurals.dashboard_where_center_count, listedCount, listedCount),
                    style = typography.caption, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (selected != null) TextButton(onClick = { onSelect(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.dashboard_where_show_all), style = typography.label, color = owli.accent)
        } else Text(stringResource(R.string.dashboard_where_hint), style = typography.caption, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(measurer: TextMeasurer, text: String, at: Offset, color: Color,
                                                                      size: androidx.compose.ui.unit.TextUnit) {
    val layout = measurer.measure(text, TextStyle(fontSize = size, fontWeight = FontWeight.ExtraBold, color = color))
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
