package com.elect3.mobdev.personalfinance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elect3.mobdev.personalfinance.R

// Owlight (approved concept 4) on the Lavender & Midnight foundation.
// Light primary is the Owlight "ink" (#2A1659, white text 15.5:1); dark primary stays #AE81F8.
// Owner-approved fallbacks retained: tertiary/onTertiary, errorContainer/onErrorContainer, surfaceBright/surfaceDim.

private val OwliLightColors = lightColorScheme(
    primary = Color(0xFF2A1659),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEE7FB),
    onPrimaryContainer = Color(0xFF3B1F8C),
    inversePrimary = Color(0xFFC6ADF1),
    secondary = Color(0xFF6747C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1EBFA),
    onSecondaryContainer = Color(0xFF3B1F8C),
    tertiary = Color(0xFF705621),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEBFA),
    onTertiaryContainer = Color(0xFF1F5A99),
    background = Color(0xFFF7F4FC),
    onBackground = Color(0xFF1B1530),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1530),
    surfaceVariant = Color(0xFFF6F2FC),
    onSurfaceVariant = Color(0xFF6B6285),
    surfaceTint = Color(0xFF2A1659),
    inverseSurface = Color(0xFF271B4D),
    inverseOnSurface = Color(0xFFF1EDFA),
    outline = Color(0xFF7C6F99),
    outlineVariant = Color(0xFFE6DFF1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E7),
    onErrorContainer = Color(0xFF5C1018),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDFE2E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4FC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1EBFA),
    surfaceContainerHighest = Color(0xFFEEE7FB),
)

private val OwliDarkColors = darkColorScheme(
    primary = Color(0xFFAE81F8),
    onPrimary = Color(0xFF1A0B3B),
    primaryContainer = Color(0xFF241D3E),
    onPrimaryContainer = Color(0xFFD9C6FF),
    inversePrimary = Color(0xFF2A1659),
    secondary = Color(0xFFC6ADF1),
    onSecondary = Color(0xFF1A0B3B),
    secondaryContainer = Color(0xFF221A44),
    onSecondaryContainer = Color(0xFFD9C6FF),
    tertiary = Color(0xFFC5A66A),
    onTertiary = Color(0xFF14243A),
    tertiaryContainer = Color(0xFF1E2841),
    onTertiaryContainer = Color(0xFF8DB9F2),
    background = Color(0xFF0B0D1F),
    onBackground = Color(0xFFF1EDFA),
    surface = Color(0xFF14182E),
    onSurface = Color(0xFFF1EDFA),
    surfaceVariant = Color(0xFF1A1F38),
    onSurfaceVariant = Color(0xFFA79FC6),
    surfaceTint = Color(0xFFAE81F8),
    inverseSurface = Color(0xFFF1EDFA),
    inverseOnSurface = Color(0xFF1B1530),
    outline = Color(0xFF8B82AA),
    outlineVariant = Color(0xFF262B47),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1C26),
    onErrorContainer = Color(0xFFFFDADF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF35465B),
    surfaceDim = Color(0xFF14243A),
    surfaceContainerLowest = Color(0xFF0B0D1F),
    surfaceContainerLow = Color(0xFF0B0D1F),
    surfaceContainer = Color(0xFF14182E),
    surfaceContainerHigh = Color(0xFF1A1F38),
    surfaceContainerHighest = Color(0xFF221A44),
)

/** Presentation tokens only; no financial calculations or screen behavior. */
@Immutable
data class OwliExtensionColors(
    val income: Color,
    val expense: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val incomeCardFill: Color,
    val incomeCardLabel: Color,
    val expenseCardFill: Color,
    val expenseCardLabel: Color,
    val entriesCardFill: Color,
    val entriesCardLabel: Color,
    val topCategoryCardFill: Color,
    val topCategoryCardLabel: Color,
    val navUnselected: Color,
    // The handoff provides a light-only decorative accent, never a text color.
    val brandAccent: Color?,
    // ---- Owlight additions ----
    /** Midnight Panel gradient; the panel is dark in both themes. */
    val panelStart: Color,
    val panelEnd: Color,
    /** Transparent in light; a lavender hairline in dark. */
    val panelBorder: Color,
    val onPanel: Color,
    val onPanelMuted: Color,
    val panelIncome: Color,
    val panelExpense: Color,
    val panelTrack: Color,
    val panelRule: Color,
    val panelChip: Color,
    /** Links, badges and emphasis (#6747C7 light, #C6ADF1 dark). */
    val accent: Color,
    val accentSoft: Color,
    /** Neutral icon tile behind category and quick-action icons. */
    val tile: Color,
    val tileContent: Color,
    /** Filled input background. */
    val field: Color,
    val hairline: Color,
    val navBackground: Color,
)

private val OwliLightExtensionColors = OwliExtensionColors(
    income = Color(0xFF156B3E),
    expense = Color(0xFFA5311F),
    heroStart = Color(0xFF2A1A5E),
    heroEnd = Color(0xFF140E33),
    incomeCardFill = Color(0xFFDCEBFA),
    incomeCardLabel = Color(0xFF1F5A99),
    expenseCardFill = Color(0xFFFBE1EE),
    expenseCardLabel = Color(0xFFA32C6B),
    entriesCardFill = Color(0xFFFDEFD5),
    entriesCardLabel = Color(0xFF8A5A00),
    topCategoryCardFill = Color(0xFFFEE3E1),
    topCategoryCardLabel = Color(0xFFA8401E),
    navUnselected = Color(0xFF6B6285),
    brandAccent = Color(0xFFAA83F8),
    panelStart = Color(0xFF2A1A5E),
    panelEnd = Color(0xFF140E33),
    panelBorder = Color.Transparent,
    onPanel = Color(0xFFF4EFFF),
    onPanelMuted = Color(0xFFBDB1E3),
    panelIncome = Color(0xFF8FE3B8),
    panelExpense = Color(0xFFFFB0A3),
    panelTrack = Color(0x21FFFFFF),
    panelRule = Color(0x1FFFFFFF),
    panelChip = Color(0x1AFFFFFF),
    accent = Color(0xFF6747C7),
    accentSoft = Color(0xFFEEE7FB),
    tile = Color(0xFFF1EBFA),
    tileContent = Color(0xFF3B1F8C),
    field = Color(0xFFF6F2FC),
    hairline = Color(0xFFE6DFF1),
    navBackground = Color(0xFFFFFFFF),
)

private val OwliDarkExtensionColors = OwliExtensionColors(
    income = Color(0xFF6FD6A3),
    expense = Color(0xFFFF9E8F),
    heroStart = Color(0xFF2E1D66),
    heroEnd = Color(0xFF17113A),
    incomeCardFill = Color(0xFF1E2841),
    incomeCardLabel = Color(0xFF8DB9F2),
    expenseCardFill = Color(0xFF2C1D33),
    expenseCardLabel = Color(0xFFF29BC8),
    entriesCardFill = Color(0xFF2D2727),
    entriesCardLabel = Color(0xFFF2C46B),
    topCategoryCardFill = Color(0xFF2C1E2D),
    topCategoryCardLabel = Color(0xFFFFAE95),
    navUnselected = Color(0xFFA79FC6),
    brandAccent = null,
    panelStart = Color(0xFF2E1D66),
    panelEnd = Color(0xFF17113A),
    panelBorder = Color(0xFF3A2C7A),
    onPanel = Color(0xFFF4EFFF),
    onPanelMuted = Color(0xFFBDB1E3),
    panelIncome = Color(0xFF8FE3B8),
    panelExpense = Color(0xFFFFB0A3),
    panelTrack = Color(0x21FFFFFF),
    panelRule = Color(0x1FFFFFFF),
    panelChip = Color(0x1AFFFFFF),
    accent = Color(0xFFC6ADF1),
    accentSoft = Color(0xFF241D3E),
    tile = Color(0xFF221A44),
    tileContent = Color(0xFFD9C6FF),
    field = Color(0xFF1A1F38),
    hairline = Color(0xFF262B47),
    navBackground = Color(0xFF171C35),
)

val LocalOwliColors = staticCompositionLocalOf { OwliLightExtensionColors }

/** Owlight named styles. Material typography also uses Plus Jakarta Sans (owner-approved). */
@Immutable
data class OwliTypographyTokens(
    val heroAmount: TextStyle,
    val screenTitle: TextStyle,
    val greeting: TextStyle,
    val sectionTitle: TextStyle,
    val amountMedium: TextStyle,
    val bodyStrong: TextStyle,
    val amountRow: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val micro: TextStyle,
    val wordmark: TextStyle,
    /** Uppercase, tracked overline (panel and ribbon labels). */
    val overline: TextStyle,
    /** Spaced "O W L I" crest wordmark on Login. */
    val crestWordmark: TextStyle,
    val button: TextStyle,
)

private val OwliFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
)

private fun owliTextStyle(
    size: Float,
    lineHeight: Float,
    weight: FontWeight,
    tracking: Float = 0f,
    tabularNumerals: Boolean = false,
) = TextStyle(
    fontFamily = OwliFontFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    // Requests font support only; does not format or alter financial values.
    fontFeatureSettings = if (tabularNumerals) "tnum" else null,
)

private val DefaultOwliTypographyTokens = OwliTypographyTokens(
    heroAmount = owliTextStyle(34f, 40f, FontWeight.SemiBold, tracking = -0.8f, tabularNumerals = true),
    screenTitle = owliTextStyle(22f, 28f, FontWeight.Bold, tracking = -0.3f),
    greeting = owliTextStyle(12f, 16f, FontWeight.Medium),
    sectionTitle = owliTextStyle(15f, 20f, FontWeight.Bold, tracking = -0.1f),
    amountMedium = owliTextStyle(15f, 20f, FontWeight.SemiBold, tabularNumerals = true),
    bodyStrong = owliTextStyle(14f, 20f, FontWeight.SemiBold),
    amountRow = owliTextStyle(14f, 20f, FontWeight.SemiBold, tabularNumerals = true),
    body = owliTextStyle(13f, 19f, FontWeight.Normal),
    caption = owliTextStyle(12f, 17f, FontWeight.Normal),
    label = owliTextStyle(12f, 16f, FontWeight.SemiBold),
    micro = owliTextStyle(11f, 14f, FontWeight.SemiBold),
    wordmark = owliTextStyle(14f, 20f, FontWeight.ExtraBold, tracking = 2.24f),
    overline = owliTextStyle(10.5f, 14f, FontWeight.ExtraBold, tracking = 1.5f),
    crestWordmark = owliTextStyle(20f, 26f, FontWeight.ExtraBold, tracking = 9f),
    button = owliTextStyle(14.5f, 20f, FontWeight.Bold),
)

/** Corner tokens only; does not size components. */
@Immutable
data class OwliShapeTokens(
    val hero: RoundedCornerShape,
    val bottomBar: RoundedCornerShape,
    val summaryCard: RoundedCornerShape,
    val listContainer: RoundedCornerShape,
    val navigationIndicator: RoundedCornerShape,
    val rowTile: RoundedCornerShape,
    val brandTile: RoundedCornerShape,
    val button: RoundedCornerShape,
    val chip: RoundedCornerShape,
    // ---- Owlight additions ----
    val panel: RoundedCornerShape,
    val group: RoundedCornerShape,
    val field: RoundedCornerShape,
    val quickAction: RoundedCornerShape,
    val sheetTop: RoundedCornerShape,
    val alert: RoundedCornerShape,
)

private val DefaultOwliShapeTokens = OwliShapeTokens(
    hero = RoundedCornerShape(28.dp),
    bottomBar = RoundedCornerShape(24.dp),
    summaryCard = RoundedCornerShape(20.dp),
    listContainer = RoundedCornerShape(20.dp),
    navigationIndicator = RoundedCornerShape(18.dp),
    rowTile = RoundedCornerShape(12.dp),
    brandTile = RoundedCornerShape(11.dp),
    button = RoundedCornerShape(16.dp),
    chip = RoundedCornerShape(percent = 50),
    panel = RoundedCornerShape(28.dp),
    group = RoundedCornerShape(20.dp),
    field = RoundedCornerShape(14.dp),
    quickAction = RoundedCornerShape(14.dp),
    sheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    alert = RoundedCornerShape(12.dp),
)

val LocalOwliTypography = staticCompositionLocalOf { DefaultOwliTypographyTokens }
val LocalOwliShapes = staticCompositionLocalOf { DefaultOwliShapeTokens }

/** Material roles keep their default sizes but use Plus Jakarta Sans (owner-approved). */
private val OwliTypography = Typography().let { t ->
    fun TextStyle.owli() = copy(fontFamily = OwliFontFamily)
    Typography(
        displayLarge = t.displayLarge.owli(), displayMedium = t.displayMedium.owli(), displaySmall = t.displaySmall.owli(),
        headlineLarge = t.headlineLarge.owli(), headlineMedium = t.headlineMedium.owli(), headlineSmall = t.headlineSmall.owli(),
        titleLarge = t.titleLarge.owli(), titleMedium = t.titleMedium.owli(), titleSmall = t.titleSmall.owli(),
        bodyLarge = t.bodyLarge.owli(), bodyMedium = t.bodyMedium.owli(), bodySmall = t.bodySmall.owli(),
        labelLarge = t.labelLarge.owli(), labelMedium = t.labelMedium.owli(), labelSmall = t.labelSmall.owli(),
    )
}
private val OwliShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PersonalFinanceTheme(
    /** Defaults to the phone setting; the app passes the Account > Appearance choice. */
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOwliColors provides if (darkTheme) OwliDarkExtensionColors else OwliLightExtensionColors,
        LocalOwliTypography provides DefaultOwliTypographyTokens,
        LocalOwliShapes provides DefaultOwliShapeTokens,
    ) {
        MaterialTheme(
            // Explicit branded schemes only: dynamic wallpaper colors remain disabled.
            colorScheme = if (darkTheme) OwliDarkColors else OwliLightColors,
            typography = OwliTypography,
            shapes = OwliShapes,
            content = content,
        )
    }
}
