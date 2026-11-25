package com.badshah222.rani.ai.ui.orb

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.badshah222.rani.ai.ui.character.CharState
import com.badshah222.rani.ai.ui.theme.Bad
import com.badshah222.rani.ai.ui.theme.CyanAcc
import com.badshah222.rani.ai.ui.theme.OrbOrange
import com.badshah222.rani.ai.ui.theme.Violet

/**
 * The RANI voice orb: a glowing blue energy ball (Stonic-style), drawn
 * entirely on Canvas — bright white-cyan core, deep-blue rim, rotating
 * energy ring and sparkle particles. No face, no body. It breathes when
 * idle, pulses with the live mic level while listening, and changes colour
 * with the assistant's state.
 */
@Composable
fun VoiceOrb(state: CharState, level: Float, lowPower: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (lowPower) 20000 else 9000, easing = LinearEasing)),
        label = "spin",
    )
    val breathe by transition.animateFloat(
        0.97f, 1.03f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val pulse = (if (lowPower) 1f else breathe) * (1f + level.coerceIn(0f, 1f) * 0.10f)
    val ringColor = when (state) {
        CharState.Error -> Bad
        CharState.Speaking -> Violet
        CharState.Listening -> OrbOrange
        CharState.Thinking, CharState.Working -> CyanAcc
        CharState.Idle -> Violet
    }
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * pulse
        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(listOf(ringColor.copy(alpha = 0.32f), Color.Transparent), center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy),
        )
        // Energy ring (tilted ellipse, rotating)
        rotate(spin, pivot = Offset(cx, cy)) {
            drawOval(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, ringColor.copy(alpha = 0.9f), Color(0xFF7DD3FC), ringColor.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(cx, cy),
                ),
                topLeft = Offset(cx - r * 0.98f, cy - r * 0.36f),
                size = Size(r * 1.96f, r * 0.72f),
                style = Stroke(width = r * 0.15f),
            )
        }
        // Faint orbit ring
        rotate(-spin * 0.6f, pivot = Offset(cx, cy)) {
            drawOval(
                color = CyanAcc.copy(alpha = 0.30f),
                topLeft = Offset(cx - r * 0.9f, cy - r * 0.55f),
                size = Size(r * 1.8f, r * 1.1f),
                style = Stroke(width = r * 0.03f),
            )
        }
        // Glowing blue energy core: bright white-cyan heart, deep-blue rim
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White, Color(0xFFBAE6FD), Color(0xFF38BDF8), Color(0xFF1D4ED8)),
                center = Offset(cx, cy), radius = r * 0.52f
            ),
            radius = r * 0.52f, center = Offset(cx, cy),
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = Offset(cx - r * 0.18f, cy - r * 0.2f), radius = r * 0.5f),
            radius = r * 0.5f, center = Offset(cx, cy),
        )
        drawCircle(color = ringColor.copy(alpha = 0.85f), radius = r * 0.52f, center = Offset(cx, cy), style = Stroke(width = r * 0.035f))
        // Sparkle particles around the core
        for (i in 0 until 8) {
            val a = Math.toRadians((spin * 1.7 + i * 45).toDouble()).toFloat()
            val pr = r * (0.62f + 0.12f * kotlin.math.sin(spin * 0.05f + i * 1.3f))
            drawCircle(
                Color.White.copy(alpha = 0.75f), r * 0.02f,
                Offset(cx + kotlin.math.cos(a) * pr, cy + kotlin.math.sin(a) * pr),
            )
        }
    }
}
