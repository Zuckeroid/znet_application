package com.znet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ZnetDarkScheme = darkColorScheme(
    primary = ZnetGreen,
    secondary = Color(0xFF23B6AA),
    background = ZnetBackground,
    surface = ZnetSurface,
    onPrimary = Color.Black,
    onBackground = ZnetText,
    onSurface = ZnetText
)

private val ZnetLightScheme = lightColorScheme(
    primary = Color(0xFF0E8F35),
    secondary = Color(0xFF147C73),
    background = ZnetLightBackground,
    surface = ZnetLightSurface,
    surfaceVariant = Color(0xFFE8F0EC),
    onPrimary = Color.White,
    onBackground = ZnetLightText,
    onSurface = ZnetLightText,
    onSurfaceVariant = Color(0xFF52605A)
)

@Composable
fun ZnetTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (darkTheme) ZnetDarkScheme else ZnetLightScheme,
        content = content
    )
}
