package com.badshah222.rani.ai.ui.character

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.ui.theme.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class CharState { Idle, Listening, Thinking, Speaking, Working, Error }

/**
 * The RANI character — the faceless glowing holographic girl-form (approved
 * Stonic video frame, RANI's own clean version). She is ALWAYS shown
 * full-figure, figure-shaped, never circular: a round crop hides the figure
 * and leaves only a blue ball. Small avatar spots use the head/shoulders
 * crop in a rounded rectangle (see ui.stonic.RaniAvatar).
 *  - Parallax tilt: finger drag or phone motion (accelerometer) rotates the portrait in 3D.
 *  - Idle: slow breathing + soft glow.   Listening: rings that follow the microphone level.
 *  - Thinking: rotating light arc.   Speaking: radial sound-wave bars.   Working: orbiting lights.   Error: red flash + shake.
 *  Low-power mode drops motion sensing, glow and per-frame bars so old phones stay smooth.
 *
 * v1.7.0 §19.8 — ALIVE, not a picture: the animation layer is driven by the
 * shared LiveState (liveState: "connecting" | "listening" | "speaking" |
 * "thinking" | "idle" | "error"), never by a blind timer. Speaking = HIGH
 * energy (fast bright halo pulse, quicker breathing, shimmer every ~3 s);
 * listening = LOW calm (slow breathe ~4 s, shimmer ~8 s); idle = slowest.
 * glowColor is the auto-detected emotion's colour (§19.8.4) — it cross-fades
 * over ~600 ms when the feeling changes. The layer sits behind + around the
 * image and never covers her form.
 *
 * @param fullFigure when true (home hero, live call) the figure fills the
 *  whole box height; otherwise she is drawn smaller inside the state rings.
 */
@Composable
fun RaniCharacter(
    state: CharState, micLevel: Float, lowPower: Boolean, modifier: Modifier = Modifier,
    fullFigure: Boolean = false,
    liveState: String? = null, glowColor: Color? = null, energy: Float? = null,
) {
    // ---- §19.8.2 energy + halo ranges, from the Live state (or the CharState fallback) ----
    val live = liveState ?: when (state) {
        CharState.Speaking -> "speaking"; CharState.Listening -> "listening"
        CharState.Thinking -> "thinking"; CharState.Error -> "error"
        CharState.Working -> "thinking"; CharState.Idle -> "idle"
    }
    val energyEff = energy ?: when (live) {
        "speaking" -> 1.0f; "listening" -> 0.35f; "thinking" -> 0.6f
        "error" -> 0.8f; else -> 0.25f
    }
    // Breathe cycle: ~1.5 s speaking, ~4 s listening, ~5 s idle (§19.8.2).
    val breatheMs = when (live) { "speaking" -> 1500; "listening" -> 4000; "thinking" -> 2500; else -> 5000 }
    val scaleMax = 1.03f + energyEff * 0.02f // 1.03 idle → 1.05 speaking
    val (glowLow, glowHigh) = when (live) {
        "speaking" -> 0.55f to 0.95f
        "listening" -> 0.25f to 0.45f
        "thinking" -> 0.35f to 0.65f
        "idle" -> 0.20f to 0.40f
        else -> 0.30f to 0.60f
    }
    // Shimmer sweep period: ~3 s speaking, ~8 s listening, ~6 s idle.
    val shimmerMs = when (live) { "speaking" -> 3000; "listening" -> 8000; "thinking" -> 4000; else -> 6000 }

    val inf = rememberInfiniteTransition(label = "char")
    val breathe by inf.animateFloat(1f, scaleMax, infiniteRepeatable(tween(breatheMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val phase by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (lowPower) 2600 else 1600, easing = LinearEasing)), label = "phase")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val glow by inf.animateFloat(glowLow, glowHigh, infiniteRepeatable(tween(breatheMs), RepeatMode.Reverse), label = "glow")
    // Flowing shimmer: a soft diagonal light sweep across the figure.
    val shimmer by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(shimmerMs, easing = LinearEasing)), label = "shimmer")

    // 3D tilt from drag + device motion
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var motionX by remember { mutableFloatStateOf(0f) }
    var motionY by remember { mutableFloatStateOf(0f) }
    val ctx = LocalContext.current
    DisposableEffect(lowPower) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { motionX = (-e.values[0] / 9.8f).coerceIn(-1f, 1f) * 7f; motionY = (e.values[1] / 9.8f - 0.5f).coerceIn(-1f, 1f) * 5f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (!lowPower && sm != null && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l) }
    }
    val tiltX by animateFloatAsState(dragY + motionY, spring(dampingRatio = 0.6f, stiffness = 120f), label = "tx")
    val tiltY by animateFloatAsState(dragX + motionX, spring(dampingRatio = 0.6f, stiffness = 120f), label = "ty")
    val shake by animateFloatAsState(if (state == CharState.Error) 1f else 0f, tween(500), label = "shake")

    val accent = when (state) { CharState.Error -> Bad; CharState.Listening -> Ok; CharState.Thinking -> Blue; else -> Gold }
    // §19.8.4: the emotion's glow colour wins for the halo; it cross-fades
    // over ~600 ms when the feeling changes (no abrupt jump).
    val haloColor by animateColorAsState(glowColor ?: accent, animationSpec = tween(600), label = "haloColor")
    val scale = when (state) { CharState.Speaking -> breathe + 0.02f * abs(sin(phase * 2)); CharState.Listening -> 1f + micLevel * 0.06f; else -> breathe }

    Box(modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = { dragX = 0f; dragY = 0f }) { _, d -> dragX = (dragX + d.x * 0.05f).coerceIn(-14f, 14f); dragY = (dragY - d.y * 0.05f).coerceIn(-10f, 10f) }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.34f
            // §19.8.1 breathing glow halo, in the emotion colour — behind the figure.
            if (!lowPower) drawCircle(Brush.radialGradient(listOf(haloColor.copy(alpha = glow * 0.55f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
            // §19.8.1 flowing shimmer: a soft diagonal light sweep, low alpha,
            // never covering her form. Skipped in low-power mode.
            if (!lowPower) {
                val bandW = size.width * 0.22f
                val x0 = -bandW + shimmer * (size.width + bandW * 2)
                rotate(18f, c) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.05f + energyEff * 0.06f), Color.Transparent),
                            startX = x0 - bandW, endX = x0 + bandW,
                        ),
                        topLeft = Offset(x0 - bandW, -size.height * 0.2f),
                        size = androidx.compose.ui.geometry.Size(bandW * 2, size.height * 1.4f),
                    )
                }
            }
            // N-11: the speaking bars, listening rings, thinking arc,
            // working dots and idle ring all use haloColor — the emotion's
            // own glow colour (§19.8.4/§19.9.1) — so halo, bars and rings
            // show ONE mood. (When no glowColor is passed, haloColor IS
            // the old accent, so nothing changes for those callers.) The
            // Error ring alone keeps the red accent: red is the error
            // signal (§2), not a mood.
            when (state) {
                CharState.Listening -> for (i in 0..2) {
                    val t = ((phase / (2 * PI).toFloat()) + i / 3f) % 1f
                    drawCircle(haloColor.copy(alpha = (1f - t) * (0.25f + micLevel * 0.6f)), r * (1.05f + t * (0.45f + micLevel * 0.5f)), c, style = Stroke(3.dp.toPx()))
                }
                CharState.Speaking -> { val bars = if (lowPower) 24 else 56; for (i in 0 until bars) {
                    val a = (i / bars.toFloat()) * 2 * PI.toFloat(); val len = r * (0.06f + 0.22f * abs(sin(phase + i * 0.55f)) * (0.6f + 0.4f * abs(cos(phase * 0.7f + i))))
                    val from = Offset(c.x + cos(a) * r * 1.08f, c.y + sin(a) * r * 1.08f); val to = Offset(c.x + cos(a) * (r * 1.08f + len), c.y + sin(a) * (r * 1.08f + len))
                    drawLine(haloColor, from, to, 3.dp.toPx(), StrokeCap.Round)
                } }
                CharState.Thinking -> drawArc(Brush.sweepGradient(listOf(Color.Transparent, haloColor, Color.Transparent), c), spin, 120f, false, topLeft = Offset(c.x - r * 1.1f, c.y - r * 1.1f), size = androidx.compose.ui.geometry.Size(r * 2.2f, r * 2.2f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                CharState.Working -> for (i in 0 until 3) {
                    val a = Math.toRadians((spin + i * 120).toDouble()); drawCircle(haloColor, 7.dp.toPx(), Offset(c.x + (cos(a) * r * 1.15f).toFloat(), c.y + (sin(a) * r * 1.15f).toFloat()))
                }
                CharState.Error -> drawCircle(accent.copy(alpha = 0.7f), r * 1.06f, c, style = Stroke(4.dp.toPx()))
                CharState.Idle -> drawCircle(haloColor.copy(alpha = glow), r * 1.04f, c, style = Stroke(2.dp.toPx()))
            }
        }
        Image(
            painterResource(R.drawable.rani_character), contentDescription = "RANI AI — your AI girlfriend",
            contentScale = ContentScale.Fit,
            modifier = (if (fullFigure) Modifier.fillMaxHeight() else Modifier.fillMaxSize(0.68f)).padding(2.dp)
                .graphicsLayer {
                    rotationX = tiltX; rotationY = tiltY; cameraDistance = 14f * density
                    scaleX = scale; scaleY = scale; translationX = sin(phase * 14) * 10f * shake
                },
        )
    }
}
