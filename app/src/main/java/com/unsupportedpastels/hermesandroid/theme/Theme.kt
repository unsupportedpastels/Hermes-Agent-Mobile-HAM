package com.unsupportedpastels.hermesandroid.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Immutable
data class HermesSemanticColors(
    val active: Color,
    val onActive: Color,
    val completed: Color,
    val onCompleted: Color,
)

/** Semantic roles that are not represented by Material's standard status roles. */
val LocalHermesSemanticColors = staticCompositionLocalOf {
    HermesSemanticColors(
        active = Color(0xFFC68A16),
        onActive = Color(0xFF241A00),
        completed = Color(0xFF2D6A43),
        onCompleted = Color.White,
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2EC),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4C6361),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE8E5),
    onSecondaryContainer = Color(0xFF071F1D),
    tertiary = Color(0xFF765A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF92),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFAFCFB),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFCFB),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainer = Color(0xFFECF2EF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5CF),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF9CF2EC),
    secondary = Color(0xFFB3CCCA),
    onSecondary = Color(0xFF1D3533),
    secondaryContainer = Color(0xFF344B49),
    onSecondaryContainer = Color(0xFFCFE8E5),
    tertiary = Color(0xFFF2C64D),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4400),
    onTertiaryContainer = Color(0xFFFFDF92),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E5E2),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE0E5E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainer = Color(0xFF1C2422),
)

private val LightSemanticColors = HermesSemanticColors(
    active = Color(0xFFC68A16),
    onActive = Color(0xFF241A00),
    completed = Color(0xFF2D6A43),
    onCompleted = Color.White,
)

private val DarkSemanticColors = HermesSemanticColors(
    active = Color(0xFFF2C64D),
    onActive = Color(0xFF241A00),
    completed = Color(0xFF8ED6A5),
    onCompleted = Color(0xFF0C3A1E),
)

@Composable
fun HermesAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    CompositionLocalProvider(LocalHermesSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}
