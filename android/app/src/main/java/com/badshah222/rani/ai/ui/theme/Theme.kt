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
val Ink = Color(0xFF0C0918)
val Panel = Color(0xFF171130)
val Panel2 = Color(0xFF211947)
val Mute = Color(0xFF9A93B8)
val Ok = Color(0xFF35D49A)
val Bad = Color(0xFFFF5D6C)
val Blue = Color(0xFF5AA2FF)

private val dark = darkColorScheme(
    primary = Violet, onPrimary = Color.White, secondary = CyanAcc, background = Ink, onBackground = Color(0xFFEDEAF7),
    surface = Panel, onSurface = Color(0xFFEDEAF7), surfaceVariant = Panel2, onSurfaceVariant = Mute, error = Bad, outline = Color(0xFF2E2360),
)

/** RANI AI is a dark, violet-accent brand in both system modes. */
@Composable
fun RaniTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dark, content = content)
}
