package com.linkan.bitlotto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BtcOrange = Color(0xFFF7931A)
val Positive = Color(0xFF4CD787)
val Negative = Color(0xFFFF6B6B)
val Muted = Color(0xFF9AA0AC)

private val Scheme = darkColorScheme(
    primary = BtcOrange,
    onPrimary = Color(0xFF1A1100),
    primaryContainer = Color(0xFF3A2608),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFFFB866),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE8E9EC),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE8E9EC),
    surfaceVariant = Color(0xFF1E2128),
    onSurfaceVariant = Color(0xFFB4B9C4),
    surfaceContainer = Color(0xFF1A1C22),
    surfaceContainerHigh = Color(0xFF20232A),
    outline = Color(0xFF3A3F4A),
    error = Negative,
)

val Mono = FontFamily.Monospace

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun BitLottoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
