package com.unsupportedpastels.hermesandroid.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    secondary = Color(0xFF4F6080),
    tertiary = Color(0xFF735471),
    surface = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEDEEF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    secondary = Color(0xFFB8C7EA),
    tertiary = Color(0xFFE1BBDD),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2026),
)

@Composable
fun HermesAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
