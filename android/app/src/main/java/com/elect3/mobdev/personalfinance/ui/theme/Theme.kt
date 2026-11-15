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

// Lavender & Midnight handoff. Owner-approved temporary fallbacks:
// tertiary/onTertiary, errorContainer/onErrorContainer, surfaceBright/surfaceDim.
// All other explicitly configured roles follow the handoff; surfaceTint follows primary.

private val OwliLightColors = lightColorScheme(
    primary = Color(0xFFC6ADF1),
    onPrimary = Color(0xFF2A1659),
    primaryContainer = Color(0xFFEAE2F8),
    onPrimaryContainer = Color(0xFF3B1F8C),
    inversePrimary = Color(0xFFAE81F8),
    secondary = Color(0xFFEFDCF8),
    onSecondary = Color(0xFF3B1F8C),
    secondaryContainer = Color(0xFFEFDCF8),
    onSecondaryContainer = Color(0xFF3B1F8C),
    tertiary = Color(0xFF705621),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCEBFA),
    onTertiaryContainer = Color(0xFF1F5A99),
    background = Color(0xFFF7F4FD),
    onBackground = Color(0xFF1B1530),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1530),
    surfaceVariant = Color(0xFFF0E9F9),
    onSurfaceVariant = Color(0xFF5E5575),
    surfaceTint = Color(0xFFC6ADF1),
    inverseSurface = Color(0xFF271B4D),
    inverseOnSurface = Color(0xFFF1EDFA),
    outline = Color(0xFF7C6F99),
    outlineVariant = Color(0xFFE6DEF3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410B16),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDFE2E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4FD),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0E9F9),
    surfaceContainerHighest = Color(0xFFEAE2F8),
)

private val OwliDarkColors = darkColorScheme(
    primary = Color(0xFFAE81F8),
    onPrimary = Color(0xFF1A0B3B),
    primaryContainer = Color(0xFF3A2670),
    onPrimaryContainer = Color(0xFFD9C6FF),
    inversePrimary = Color(0xFFC6ADF1),
    secondary = Color(0xFF2C1853),
    onSecondary = Color(0xFFD9C6FF),
    secondaryContainer = Color(0xFF2C1853),
    onSecondaryContainer = Color(0xFFD9C6FF),
    tertiary = Color(0xFFC5A66A),
    onTertiary = Color(0xFF14243A),
    tertiaryContainer = Color(0xFF1E2841),
    onTertiaryContainer = Color(0xFF8DB9F2),
    background = Color(0xFF0C0E21),
    onBackground = Color(0xFFF1EDFA),
    surface = Color(0xFF12172B),
    onSurface = Color(0xFFF1EDFA),
    surfaceVariant = Color(0xFF271B4D),
    onSurfaceVariant = Color(0xFFB9B0D6),
    surfaceTint = Color(0xFFAE81F8),
    inverseSurface = Color(0xFFF1EDFA),
    inverseOnSurface = Color(0xFF1B1530),
    outline = Color(0xFF8B82AA),
    outlineVariant = Color(0xFF2A2548),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF792534),
    onErrorContainer = Color(0xFFFFDADF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF35465B),
    surfaceDim = Color(0xFF14243A),
    surfaceContainerLowest = Color(0xFF0C0E21),
    surfaceContainerLow = Color(0xFF0C0E21),
    surfaceContainer = Color(0xFF12172B),
    surfaceContainerHigh = Color(0xFF271B4D),
    surfaceContainerHighest = Color(0xFF2C1853),
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
)

private val OwliLightExtensionColors = OwliExtensionColors(
    income = Color(0xFF156B3E),
    expense = Color(0xFFA5311F),
    heroStart = Color(0xFFE6D8F9),
    heroEnd = Color(0xFFF8E0F6),
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
)

private val OwliDarkExtensionColors = OwliExtensionColors(
    income = Color(0xFF6FD6A3),
    expense = Color(0xFFFF9E8F),
    heroStart = Color(0xFF2C1853),
    heroEnd = Color(0xFF1F1747),
    incomeCardFill = Color(0xFF1E2841),
    incomeCardLabel = Color(0xFF8DB9F2),
    expenseCardFill = Color(0xFF2C1D33),
    expenseCardLabel = Color(0xFFF29BC8),
    entriesCardFill = Color(0xFF2D2727),
    entriesCardLabel = Color(0xFFF2C46B),
    topCategoryCardFill = Color(0xFF2C1E2D),
    topCategoryCardLabel = Color(0xFFFFAE95),
    navUnselected = Color(0xFF9A91B8),
    brandAccent = null,
)

val LocalOwliColors = staticCompositionLocalOf { OwliLightExtensionColors }

/** Named styles for later adoption; does not replace MaterialTheme.typography. */
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
)

private val OwliFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
)

private fun owliTextStyle(
    size: Int,
    lineHeight: Int,
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
    heroAmount = owliTextStyle(38, 46, FontWeight.ExtraBold, tabularNumerals = true),
    screenTitle = owliTextStyle(26, 32, FontWeight.ExtraBold),
    greeting = owliTextStyle(24, 30, FontWeight.ExtraBold),
    sectionTitle = owliTextStyle(16, 22, FontWeight.ExtraBold),
    amountMedium = owliTextStyle(20, 26, FontWeight.ExtraBold, tabularNumerals = true),
    bodyStrong = owliTextStyle(14, 20, FontWeight.Bold),
    amountRow = owliTextStyle(14, 20, FontWeight.ExtraBold, tabularNumerals = true),
    body = owliTextStyle(12, 18, FontWeight.Medium),
    caption = owliTextStyle(12, 16, FontWeight.Medium),
    label = owliTextStyle(11, 16, FontWeight.Bold, tracking = 0.66f),
    micro = owliTextStyle(11, 14, FontWeight.SemiBold),
    wordmark = owliTextStyle(14, 20, FontWeight.ExtraBold, tracking = 2.24f),
)

/** Corner tokens only; does not replace MaterialTheme.shapes or size components. */
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
)

private val DefaultOwliShapeTokens = OwliShapeTokens(
    hero = RoundedCornerShape(26.dp),
    bottomBar = RoundedCornerShape(24.dp),
    summaryCard = RoundedCornerShape(20.dp),
    listContainer = RoundedCornerShape(20.dp),
    navigationIndicator = RoundedCornerShape(18.dp),
    rowTile = RoundedCornerShape(12.dp),
    brandTile = RoundedCornerShape(11.dp),
    button = RoundedCornerShape(percent = 50),
    chip = RoundedCornerShape(percent = 50),
)

val LocalOwliTypography = staticCompositionLocalOf { DefaultOwliTypographyTokens }
val LocalOwliShapes = staticCompositionLocalOf { DefaultOwliShapeTokens }

// Existing applied Material typography and shapes remain unchanged.
private val OwliTypography = Typography()
private val OwliShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PersonalFinanceTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
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
