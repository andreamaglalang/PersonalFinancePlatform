package com.elect3.mobdev.personalfinance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// OWLI Soft Sapphire. Darker accent shades keep small text legible in light mode.
private val DustySapphire = Color(0xFF456A9A)
private val MidnightNavy = Color(0xFF14243A)
private val SageGreen = Color(0xFF6F9B83)
private val MutedGold = Color(0xFFC5A66A)
private val MochaBrown = Color(0xFF927463)
private val SoftIvory = Color(0xFFF7F6F2)
private val PowderBlue = Color(0xFFDCE7F2)
private val WarmWhite = Color(0xFFFFFFFF)
private val SlateGray = Color(0xFF778494)

private val OwliLightColors = lightColorScheme(
    primary = DustySapphire,
    onPrimary = WarmWhite,
    primaryContainer = PowderBlue,
    onPrimaryContainer = MidnightNavy,
    inversePrimary = PowderBlue,
    secondary = Color(0xFF365E49),
    onSecondary = WarmWhite,
    secondaryContainer = Color(0xFFDDECE2),
    onSecondaryContainer = Color(0xFF183C2A),
    tertiary = Color(0xFF705621),
    onTertiary = WarmWhite,
    tertiaryContainer = Color(0xFFF1E5CA),
    onTertiaryContainer = Color(0xFF382B10),
    background = SoftIvory,
    onBackground = MidnightNavy,
    surface = WarmWhite,
    onSurface = MidnightNavy,
    surfaceVariant = PowderBlue,
    onSurfaceVariant = Color(0xFF465466),
    surfaceTint = DustySapphire,
    inverseSurface = MidnightNavy,
    inverseOnSurface = SoftIvory,
    outline = Color(0xFF687688),
    outlineVariant = Color(0xFFBCC8D4),
    error = Color(0xFF9C3540),
    onError = WarmWhite,
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410B16),
    scrim = Color.Black,
    surfaceBright = WarmWhite,
    surfaceDim = Color(0xFFDFE2E5),
    surfaceContainerLowest = WarmWhite,
    surfaceContainerLow = SoftIvory,
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = Color(0xFFE7ECF1),
    surfaceContainerHighest = PowderBlue,
)

private val OwliDarkColors = darkColorScheme(
    primary = PowderBlue,
    onPrimary = MidnightNavy,
    primaryContainer = Color(0xFF2C486C),
    onPrimaryContainer = PowderBlue,
    inversePrimary = DustySapphire,
    // A lighter sage provides comfortable small-text contrast on dark row surfaces.
    secondary = Color(0xFF9BC4AC),
    onSecondary = MidnightNavy,
    secondaryContainer = Color(0xFF274B39),
    onSecondaryContainer = Color(0xFFDDECE2),
    tertiary = MutedGold,
    onTertiary = MidnightNavy,
    tertiaryContainer = Color(0xFF514024),
    onTertiaryContainer = Color(0xFFF1E5CA),
    background = MidnightNavy,
    onBackground = SoftIvory,
    surface = Color(0xFF1B2C42),
    onSurface = SoftIvory,
    surfaceVariant = Color(0xFF304157),
    onSurfaceVariant = Color(0xFFC0CCDA),
    surfaceTint = PowderBlue,
    inverseSurface = SoftIvory,
    inverseOnSurface = MidnightNavy,
    outline = SlateGray,
    outlineVariant = Color(0xFF46566C),
    error = Color(0xFFFFB2BD),
    onError = Color(0xFF5F1424),
    errorContainer = Color(0xFF792534),
    onErrorContainer = Color(0xFFFFDADF),
    scrim = Color.Black,
    surfaceBright = Color(0xFF35465B),
    surfaceDim = MidnightNavy,
    surfaceContainerLowest = Color(0xFF0E1B2C),
    surfaceContainerLow = Color(0xFF18283D),
    surfaceContainer = Color(0xFF1D2E44),
    surfaceContainerHigh = Color(0xFF26374D),
    surfaceContainerHighest = Color(0xFF304157),
)

// Reserve the original Mocha for decorative accents, not small text or semantic errors.
// Typography uses the platform font, avoiding bundled assets or downloadable fonts.
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
    MaterialTheme(
        // Explicit branded schemes only: dynamic wallpaper colors are intentionally disabled.
        colorScheme = if (isSystemInDarkTheme()) OwliDarkColors else OwliLightColors,
        typography = OwliTypography,
        shapes = OwliShapes,
        content = content,
    )
}
