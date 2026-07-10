package com.hermes.mobile.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HermesYellow = Color(0xFFFFD54A)
val HermesBlack = Color(0xFF080808)
val HermesSurface = Color(0xFF121214)
val HermesSurfaceHigh = Color(0xFF1C1C1F)

private val HermesColors = darkColorScheme(
    primary = HermesYellow,
    onPrimary = Color(0xFF201A00),
    primaryContainer = Color(0xFF3B3100),
    onPrimaryContainer = Color(0xFFFFE16B),
    secondary = Color(0xFFC9C6C0),
    onSecondary = Color(0xFF30302D),
    background = HermesBlack,
    onBackground = Color(0xFFF4F1EC),
    surface = HermesSurface,
    onSurface = Color(0xFFF4F1EC),
    surfaceVariant = HermesSurfaceHigh,
    onSurfaceVariant = Color(0xFFB9B7B3),
    outline = Color(0xFF48484D),
    outlineVariant = Color(0xFF2D2D31),
    error = Color(0xFFFFB4AB),
)

private val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val HermesTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HermesColors,
        shapes = HermesShapes,
        typography = HermesTypography,
        content = content,
    )
}
