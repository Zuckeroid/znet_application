package com.znet.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

@Composable
fun ZnetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZnetDarkScheme,
        content = content
    )
}
