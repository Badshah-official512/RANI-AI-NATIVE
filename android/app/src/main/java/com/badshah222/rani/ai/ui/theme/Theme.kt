package com.badshah222.rani.ai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFF5B942)
val GoldLight = Color(0xFFFFDF8A)
val Violet = Color(0xFF8B5CF6)
val VioletDeep = Color(0xFF6D28D9)
val VioletSoft = Color(0xFFB79CFF)
val CyanAcc = Color(0xFF22D3EE)
val OrbOrange = Color(0xFFFF9E4F)
val Ink = Color(0xFF1D1738)
val Panel = Color(0xFF2A2154)
val Panel2 = Color(0xFF352A68)
val Mute = Color(0xFFB3ABC9)
val Ok = Color(0xFF35D49A)
val Bad = Color(0xFFFF5D6C)
val Blue = Color(0xFF5AA2FF)
/** Energetic lighting colours — lime, red, green, blue — for the ambient light wash. */
val Lime = Color(0xFFA3E635)
val EnergyRed = Color(0xFFEF4444)
val EnergyGreen = Color(0xFF22C55E)
val EnergyBlue = Color(0xFF3B82F6)

private val dark = darkColorScheme(
    primary = Violet, onPrimary = Color.White, secondary = CyanAcc, background = Ink, onBackground = Color(0xFFEDEAF7),
    surface = Panel, onSurface = Color(0xFFEDEAF7), surfaceVariant = Panel2, onSurfaceVariant = Mute, error = Bad, outline = Color(0xFF453A7D),
)

/** RANI AI is a lighter, energetic dark theme — bright violet background with lime / red / green / blue ambient lighting. */
@Composable
fun RaniTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dark, content = content)
}
