package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = NeumorphicTeal,
    onPrimary = SurfaceCard,
    primaryContainer = NeumorphicTealLight.copy(alpha = 0.15f),
    onPrimaryContainer = NeumorphicTeal,
    secondary = NeumorphicTealLight,
    onSecondary = SurfaceCard,
    secondaryContainer = NeumorphicTealLight.copy(alpha = 0.1f),
    onSecondaryContainer = NeumorphicTeal,
    background = NeumorphicBg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = NeumorphicBg,
    onSurfaceVariant = NeumorphicTextSecondary,
    outline = SurfaceBorder
)

private val DarkColorScheme = darkColorScheme(
    primary = NeumorphicTealLight,
    onPrimary = SurfaceCard,
    primaryContainer = NeumorphicTeal.copy(alpha = 0.3f),
    onPrimaryContainer = SurfaceCard,
    secondary = NeumorphicTealLight,
    onSecondary = SurfaceCard,
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
