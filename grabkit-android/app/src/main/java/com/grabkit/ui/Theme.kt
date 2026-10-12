package com.grabkit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette from the web app. Dark only, no dynamic color, no light theme. */
object GK {
    val Bg = Color(0xFF0D0F12)
    val Panel = Color(0xFF14171C)
    val Border = Color(0xFF232831)
    val Text = Color(0xFFE7E9EC)
    val Muted = Color(0xFF8B93A0)
    val Accent = Color(0xFFFFB454)
    val OnAccent = Color(0xFF1A1205)
    val Error = Color(0xFFFF6B6B)
    val ThumbBg = Color(0xFF0A0C0F)
}

@Composable
fun GrabKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GK.Accent, onPrimary = GK.OnAccent,
            background = GK.Bg, onBackground = GK.Text,
            surface = GK.Panel, onSurface = GK.Text,
            surfaceVariant = GK.Panel, onSurfaceVariant = GK.Muted,
            outline = GK.Border, error = GK.Error,
        ),
        content = content,
    )
}
