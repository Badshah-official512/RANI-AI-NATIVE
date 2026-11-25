package com.badshah222.rani.ai.ui.character

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
 * The owner's portrait as an interactive 3D-style character.
 *  - Parallax tilt: finger drag or phone motion (accelerometer) rotates the portrait in 3D.
 *  - Idle: slow breathing + soft golden glow.   Listening: rings that follow the microphone level.
 *  - Thinking: rotating light arc.   Speaking: radial sound-wave bars.   Working: orbiting lights.   Error: red flash + shake.
 *  Low-power mode drops motion sensing, glow and per-frame bars so old phones stay smooth.
 */
@Composable
fun RaniCharacter(state: CharState, micLevel: Float, lowPower: Boolean, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "char")
    val duration = if (lowPower) 3200 else 2400
    val breathe by inf.animateFloat(1f, 1.03f, infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val phase by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (lowPower) 2600 else 1600, easing = LinearEasing)), label = "phase")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val glow by inf.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(duration), RepeatMode.Reverse), label = "glow")

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
    val scale = when (state) { CharState.Speaking -> breathe + 0.02f * abs(sin(phase * 2)); CharState.Listening -> 1f + micLevel * 0.06f; else -> breathe }

    Box(modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = { dragX = 0f; dragY = 0f }) { _, d -> dragX = (dragX + d.x * 0.05f).coerceIn(-14f, 14f); dragY = (dragY - d.y * 0.05f).coerceIn(-10f, 10f) }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.34f
            if (!lowPower) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = glow * 0.55f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
            when (state) {
                CharState.Listening -> for (i in 0..2) {
                    val t = ((phase / (2 * PI).toFloat()) + i / 3f) % 1f
                    drawCircle(accent.copy(alpha = (1f - t) * (0.25f + micLevel * 0.6f)), r * (1.05f + t * (0.45f + micLevel * 0.5f)), c, style = Stroke(3.dp.toPx()))
                }
                CharState.Speaking -> { val bars = if (lowPower) 24 else 56; for (i in 0 until bars) {
                    val a = (i / bars.toFloat()) * 2 * PI.toFloat(); val len = r * (0.06f + 0.22f * abs(sin(phase + i * 0.55f)) * (0.6f + 0.4f * abs(cos(phase * 0.7f + i))))
                    val from = Offset(c.x + cos(a) * r * 1.08f, c.y + sin(a) * r * 1.08f); val to = Offset(c.x + cos(a) * (r * 1.08f + len), c.y + sin(a) * (r * 1.08f + len))
                    drawLine(accent, from, to, 3.dp.toPx(), StrokeCap.Round)
                } }
                CharState.Thinking -> drawArc(Brush.sweepGradient(listOf(Color.Transparent, accent, Color.Transparent), c), spin, 120f, false, topLeft = Offset(c.x - r * 1.1f, c.y - r * 1.1f), size = androidx.compose.ui.geometry.Size(r * 2.2f, r * 2.2f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                CharState.Working -> for (i in 0 until 3) {
                    val a = Math.toRadians((spin + i * 120).toDouble()); drawCircle(accent, 7.dp.toPx(), Offset(c.x + (cos(a) * r * 1.15f).toFloat(), c.y + (sin(a) * r * 1.15f).toFloat()))
                }
                CharState.Error -> drawCircle(accent.copy(alpha = 0.7f), r * 1.06f, c, style = Stroke(4.dp.toPx()))
                CharState.Idle -> drawCircle(accent.copy(alpha = glow), r * 1.04f, c, style = Stroke(2.dp.toPx()))
            }
        }
        Image(
            painterResource(R.drawable.rani_face), contentDescription = "RANI AI assistant",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(0.68f).padding(2.dp).clip(CircleShape)
                .graphicsLayer {
                    rotationX = tiltX; rotationY = tiltY; cameraDistance = 14f * density
                    scaleX = scale; scaleY = scale; translationX = sin(phase * 14) * 10f * shake
                },
        )
    }
}
