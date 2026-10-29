package com.ddomobile.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DdoColors {
    val Background = Color(0xFF0E0B09)
    val Panel = Color(0xFF1A1512)
    val PanelHigh = Color(0xFF241D18)
    val Line = Color(0xFF3A2E24)
    val Gold = Color(0xFFD9A441)
    val GoldDim = Color(0xFF8C6A2E)
    val Ember = Color(0xFFB4372B)
    val Text = Color(0xFFF1E6D2)
    val TextDim = Color(0xFFA8977E)
    val Good = Color(0xFF7FB069)
}

@Composable
fun DdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DdoColors.Gold,
            onPrimary = Color(0xFF1B1206),
            secondary = DdoColors.GoldDim,
            background = DdoColors.Background,
            onBackground = DdoColors.Text,
            surface = DdoColors.Panel,
            onSurface = DdoColors.Text,
            surfaceVariant = DdoColors.PanelHigh,
            onSurfaceVariant = DdoColors.TextDim,
            outline = DdoColors.Line,
            error = Color(0xFFE57368),
        ),
        content = content,
    )
}
