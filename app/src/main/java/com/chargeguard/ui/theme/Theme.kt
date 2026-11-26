package com.chargeguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chargeguard.domain.model.AppTheme

object CgColors {
    val Background = Color(0xFF101114)
    val Surface = Color(0xFF1B1D23)
    val Elevated = Color(0xFF252831)
    val Green = Color(0xFF27D17F)
    val Red = Color(0xFFFF4242)
    val Amber = Color(0xFFF5B14C)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9297A3)
    val Outline = Color(0xFF2E323C)

    val AmoledBackground = Color(0xFF000000)
    val AmoledSurface = Color(0xFF111215)
    val AmoledElevated = Color(0xFF1B1D23)
}

@Immutable
data class CgPalette(
    val background: Color,
    val surface: Color,
    val elevated: Color,
)

val LocalCgPalette = staticCompositionLocalOf {
    CgPalette(CgColors.Background, CgColors.Surface, CgColors.Elevated)
}

private val base = FontFamily.Default

val CgTypography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
)

@Composable
fun ChargeGuardTheme(theme: AppTheme = AppTheme.DARK, content: @Composable () -> Unit) {
    val palette = when (theme) {
        AppTheme.DARK -> CgPalette(CgColors.Background, CgColors.Surface, CgColors.Elevated)
        AppTheme.AMOLED -> CgPalette(CgColors.AmoledBackground, CgColors.AmoledSurface, CgColors.AmoledElevated)
    }
    val scheme = darkColorScheme(
        primary = CgColors.Green,
        onPrimary = Color(0xFF04140B),
        primaryContainer = CgColors.Green.copy(alpha = 0.16f),
        onPrimaryContainer = CgColors.Green,
        secondary = CgColors.TextSecondary,
        onSecondary = CgColors.Background,
        error = CgColors.Red,
        onError = Color.White,
        errorContainer = CgColors.Red.copy(alpha = 0.16f),
        onErrorContainer = CgColors.Red,
        background = palette.background,
        onBackground = CgColors.TextPrimary,
        surface = palette.surface,
        onSurface = CgColors.TextPrimary,
        surfaceVariant = palette.elevated,
        onSurfaceVariant = CgColors.TextSecondary,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.elevated,
        surfaceContainerHighest = palette.elevated,
        surfaceContainerLow = palette.surface,
        surfaceContainerLowest = palette.background,
        outline = CgColors.Outline,
        outlineVariant = CgColors.Outline,
    )
    MaterialTheme(colorScheme = scheme, typography = CgTypography) {
        // Default text/icon colour for everything not given an explicit colour. Without this,
        // Text outside a Surface falls back to black on the dark background.
        androidx.compose.runtime.CompositionLocalProvider(
            LocalCgPalette provides palette,
            androidx.compose.material3.LocalContentColor provides CgColors.TextPrimary,
            content = content,
        )
    }
}
