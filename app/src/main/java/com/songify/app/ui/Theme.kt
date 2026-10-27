package com.songify.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class AccentPreset(val name: String, val argb: Int)

val AccentPresets = listOf(
    AccentPreset("Green", 0xFF1ED760.toInt()),
    AccentPreset("Blue", 0xFF4C8DFF.toInt()),
    AccentPreset("Purple", 0xFF9D85FF.toInt()),
    AccentPreset("Pink", 0xFFFF5CA8.toInt()),
    AccentPreset("Red", 0xFFFF4D5E.toInt()),
    AccentPreset("Orange", 0xFFFF8A3D.toInt()),
    AccentPreset("Yellow", 0xFFFFD43B.toInt()),
    AccentPreset("Teal", 0xFF2DD4BF.toInt()),
)

val TextSecondary = Color(0xFFB3B3B3)

@Composable
fun SongifyTheme(accent: Color, amoled: Boolean, content: @Composable () -> Unit) {
    val background = if (amoled) Color.Black else Color(0xFF121212)
    val colors = darkColorScheme(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
        background = background,
        onBackground = Color.White,
        surface = background,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF282828),
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = Color(0xFF181818),
        surfaceContainer = Color(0xFF1F1F1F),
        surfaceContainerHigh = Color(0xFF282828),
        surfaceContainerHighest = Color(0xFF333333),
        secondaryContainer = Color(0xFF333333),
        onSecondaryContainer = Color.White,
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3E3E3E),
    )
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
