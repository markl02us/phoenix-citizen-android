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

val PhoenixRed = Color(0xFFE03131)
val EmberOrange = Color(0xFFFD7E14)
val SignalBlue = Color(0xFF1971C2)
val DeepRed = Color(0xFFB02525)

private val LightColors = lightColorScheme(
    primary = PhoenixRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = EmberOrange,
    onSecondary = Color.White,
    tertiary = SignalBlue,
    onTertiary = Color.White,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    error = DeepRed
)

private val DarkColors = darkColorScheme(
    primary = PhoenixRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF820008),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = EmberOrange,
    onSecondary = Color.Black,
    tertiary = SignalBlue,
    onTertiary = Color.White,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    error = DeepRed
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
