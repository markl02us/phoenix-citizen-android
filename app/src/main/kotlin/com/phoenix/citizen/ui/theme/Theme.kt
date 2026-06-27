package com.phoenix.citizen.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Sicilian flag palette — Palermo red + Corleone gold + Trinacria black
val SicilianRed = Color(0xFFBE1E2D)
val SicilianGold = Color(0xFFFFB81C)
val SicilianDeepRed = Color(0xFF8B0000)
val TrinacriaBlack = Color(0xFF1A1A1A)
val WarmCream = Color(0xFFFFF8E7)

// Legacy aliases (kept so older code that imports PhoenixRed still compiles)
val PhoenixRed = SicilianRed
val EmberOrange = SicilianGold
val SignalBlue = Color(0xFF1971C2)
val DeepRed = SicilianDeepRed

private val LightColors = lightColorScheme(
    primary = SicilianRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = SicilianGold,
    onSecondary = TrinacriaBlack,
    secondaryContainer = Color(0xFFFFE9A8),
    onSecondaryContainer = Color(0xFF2B1F00),
    tertiary = TrinacriaBlack,
    onTertiary = SicilianGold,
    background = WarmCream,
    surface = Color(0xFFFFFCF2),
    onBackground = TrinacriaBlack,
    onSurface = TrinacriaBlack,
    error = SicilianDeepRed
)

private val DarkColors = darkColorScheme(
    primary = SicilianRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF600008),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = SicilianGold,
    onSecondary = TrinacriaBlack,
    tertiary = SicilianGold,
    onTertiary = TrinacriaBlack,
    background = Color(0xFF120808),
    surface = Color(0xFF1F1414),
    error = SicilianDeepRed
)

@Composable
fun PhoenixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // PHOENIX brand red wins; opt-out of dynamic theming
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PhoenixTypography,
        content = content
    )
}
