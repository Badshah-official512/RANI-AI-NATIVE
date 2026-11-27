// v1.8.0 §20.2 correction (2026-11-27): unused — the hero stays the girl-form; the dot field is only the bottom-circle overlay (DotFieldView).
package com.badshah222.rani.ai.ui.character

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * v1.8.0 §20.2 correction (2026-11-27) — UNUSED. The dots are NOT the
 * character in the hero and this field does NOT fill the hero box: the
 * hero stays the holographic girl-form (RaniCharacter), and the dot field
 * lives ONLY in the one round bottom-circle floating overlay
 * (DotFieldView, core/). This Compose renderer is kept unreferenced —
 * do not wire it into the hero.
 *
 * (Superseded pre-correction design, removed from this doc: the dense
 * centre cluster forming the girl-form, sparse dots spilling to the
 * hero's edges and corners, the §19.8 animation layer wrapping the
 * field, and the transparent hero WebView layered on top of it.)
 *
 * All dots are drawn in ONE Canvas pass per frame from the shared model;
 * the frame loop only bumps a counter (no per-dot object allocation).
 * Low-power mode draws every other dot (§20.2.6 — counts halved).
 *
 * @param audioLevel LiveState.audioLevel (0..1) — the dots vibrate with
 *   her voice only while she speaks (§20.2.5); calm drift while listening.
 * @param glowColor the emotion's glow colour (§19.8.4) — halo only, the
 *   12-colour dot palette is never recoloured.
 * @param energy the emotion's energy (§19.8.2) — scales drift + shimmer.
 */
@Composable
fun DotField(
    audioLevel: Float,
    glowColor: Color,
    energy: Float,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
) {
    val model = remember { DotFieldModel.hero() }
    // Frame clock: withFrameNanos drives the model; the counter only
    // triggers a Canvas redraw (same lightweight pattern as RaniCharacter).
    var frame by remember { mutableLongStateOf(0L) }
    var lastNanos by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                val prev = lastNanos
                lastNanos = now
                if (prev != null) {
                    model.update((now - prev) / 1_000_000L, audioLevel, energy, lowPower)
                }
                frame++
            }
        }
    }
    Canvas(modifier) {
        // The frame counter is read here so every tick redraws the Canvas.
        @Suppress("UNUSED_VARIABLE") val tick = frame
        val w = size.width
        val h = size.height
        val density = this.density
        val cx = w / 2f
        val cy = h / 2f
        // §19.8/§20.2.6: the emotion-colour halo behind the dots.
        drawCircle(
            Brush.radialGradient(
                listOf(glowColor.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(cx, cy),
                radius = minOf(w, h) * 0.62f,
            ),
            radius = minOf(w, h) * 0.62f,
            center = Offset(cx, cy),
        )
        val dots = model.dots
        val step = if (lowPower) 2 else 1
        var i = 0
        while (i < dots.size) {
            val d = dots[i]
            val bobPx = model.bobOffsetXD(d) * density
            val x = d.x * w + bobPx + d.jitterXD * density
            val y = d.y * h + d.jitterYDp * density
            val r = d.vibRadiusDp * density
            val col = Color(d.colorArgb)
            // §20.2.4: faint radial halo behind the larger dots (8% alpha).
            if (d.baseRadiusDp >= 4f) {
                drawCircle(col.copy(alpha = 0.08f), r * 2.2f, Offset(x, y))
            }
            // The dot itself, alpha breathing 0.45–0.95 (§20.2.4).
            drawCircle(col.copy(alpha = d.shimmer), r, Offset(x, y))
            // Soft white core, fading in/out — lit from inside.
            drawCircle(Color.White.copy(alpha = d.shimmer * 0.45f), r * 0.4f, Offset(x, y))
            i += step
        }
    }
}
