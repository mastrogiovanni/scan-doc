package com.scandoc.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF1B7F5F)
val Accent = Color(0xFFFFC107)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFC8EFDF),
            onPrimaryContainer = Color(0xFF00382A),
            secondary = Color(0xFF4C6358),
            background = Color(0xFFF6F8F7),
            surface = Color.White,
        ),
        content = content
    )
}
