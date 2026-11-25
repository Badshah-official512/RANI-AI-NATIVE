package com.badshah222.rani.ai.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/** A colour that slowly travels the whole rainbow — the RGB light of the hero and the orb. */
@Composable
fun rememberRgbColor(durationMs: Int = 6000): Color {
    val transition = rememberInfiniteTransition(label = "rgb")
    val hue by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "hue",
    )
    return Color.hsv(hue, 0.72f, 1f)
}

/**
 * A soft blue / red / green light wash across a whole screen surface, breathing slowly.
 * Draw this behind a screen's content (inside a Box) for the ambient RGB lighting.
 */
@Composable
fun AmbientLights(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val pulse by transition.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0xFF2563EB).copy(alpha = 0.20f * pulse), Color.Transparent), center = Offset(w * 0.08f, h * 0.05f), radius = w * 0.75f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFDC2626).copy(alpha = 0.15f * pulse), Color.Transparent), center = Offset(w * 0.95f, h * 0.30f), radius = w * 0.70f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF16A34A).copy(alpha = 0.14f * pulse), Color.Transparent), center = Offset(w * 0.15f, h * 0.95f), radius = w * 0.80f))
        drawRect(Brush.radialGradient(listOf(Violet.copy(alpha = 0.13f * pulse), Color.Transparent), center = Offset(w * 0.90f, h * 0.92f), radius = w * 0.65f))
    }
}

/**
 * One expanding ring of light, fired every time [trigger] changes — the "touch it and
 * it lights up" burst for the hero portrait and the call-screen orb.
 */
@Composable
fun TapLightBurst(trigger: Int, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    if (progress.value < 1f) {
        Canvas(modifier) {
            val p = progress.value
            val r = (size.minDimension / 2f) * (0.35f + p * 0.85f)
            drawCircle(color.copy(alpha = (1f - p) * 0.8f), radius = r, style = Stroke(width = 3f + (1f - p) * 9f))
            drawCircle(color.copy(alpha = (1f - p) * 0.22f), radius = r * 0.8f)
        }
    }
}
