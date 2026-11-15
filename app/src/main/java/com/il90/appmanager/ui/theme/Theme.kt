package com.il90.appmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.il90.appmanager.data.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB9FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1C3C9C),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFBEC6DD),
    onSecondary = Color(0xFF283043),
    secondaryContainer = Color(0xFF242C3F),
    onSecondaryContainer = Color(0xFFDAE2FA),
    tertiary = Color(0xFF6EE7C4),
    onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF0C3B30),
    onTertiaryContainer = Color(0xFF8FF5D7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4E1512),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE4E6EF),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFE4E6EF),
    surfaceVariant = Color(0xFF232836),
    onSurfaceVariant = Color(0xFFA7AEC2),
    outline = Color(0xFF6F7689),
    outlineVariant = Color(0xFF2A3041),
    inverseSurface = Color(0xFFE4E6EF),
    inverseOnSurface = Color(0xFF1B1F29),
    inversePrimary = Color(0xFF2B55D9),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF11151E),
    surfaceContainer = Color(0xFF151A25),
    surfaceContainerHigh = Color(0xFF1C2231),
    surfaceContainerHighest = Color(0xFF252C3D),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2B55D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondary = Color(0xFF575E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E9F6),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF00796A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCBF4E9),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F5FA),
    onBackground = Color(0xFF171A21),
    surface = Color(0xFFF4F5FA),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFE2E5F0),
    onSurfaceVariant = Color(0xFF4A5062),
    outline = Color(0xFF7A8092),
    outlineVariant = Color(0xFFD9DCE8),
    inverseSurface = Color(0xFF2C3039),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFF9DB9FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDEFF6),
    surfaceContainerHigh = Color(0xFFE7E9F1),
    surfaceContainerHighest = Color(0xFFE1E3EC),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun IamTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
