package com.badshah222.rani.ai.ui.character

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.8.0 §20.2 correction (2026-11-27) — the dot-field model for the ONE
 * round circle at the BOTTOM of the screen (the floating overlay). Pure
 * Kotlin, no Android UI: the dot list (x, y, radius, colour index,
 * drift/shimmer phases) + update(dtMs, audioLevel) (drift + bob +
 * shimmer + speaking vibration). The model generates positions inside a
 * unit circle (dense centre, sparse rim); the renderer (DotFieldView.kt)
 * scales it to the circle's diameter. The hero does NOT use this model —
 * the hero stays the holographic figure (RaniCharacter).
 *
 * Reference: the boss's image (blue ink dots on white paper) — dots of
 * VARYING sizes, a DENSE cluster in the middle, SPARSE dots spreading to
 * the edges, no grid, no symmetry.
 */

/** §20.2.2 — the 12-colour palette, MAX 12, exactly these. No other colour
 *  appears anywhere in the dot field; the emotion glow tints the halo
 *  BEHIND the field, it never recolours the dots. */
object DotPalette {
    val COLORS: IntArray = intArrayOf(
        0xFF00E5FF.toInt(), // 0 Cyan
        0xFF4FC3F7.toInt(), // 1 Sky
        0xFF2979FF.toInt(), // 2 Deep Blue
        0xFF7C4DFF.toInt(), // 3 Violet
        0xFFB388FF.toInt(), // 4 Purple
        0xFFFF80AB.toInt(), // 5 Pink
        0xFFE040FB.toInt(), // 6 Magenta
        0xFFFFD54F.toInt(), // 7 Gold
        0xFF1DE9B6.toInt(), // 8 Teal
        0xFFFF8A80.toInt(), // 9 Rose
        0xFFFFFFFF.toInt(), // 10 White
        0xFFB0BEC5.toInt(), // 11 Silver
    )
}

/** One dot. x/y are fractions of the bounding square (0..1), inside the
 *  unit circle centred (0.5, 0.5) with radius 0.5; radii and offsets are dp. */
data class Dot(
    var x: Float,
    var y: Float,
    val baseRadiusDp: Float,
    val colorIdx: Int,
    /** Fraction of the field height drifted per second (crosses in ~25–40 s). */
    val driftSpeed: Float,
    val bobPhase: Float,
    /** Side-to-side bob period, 4–9 s (§20.2.3). */
    val bobPeriodMs: Float,
    val shimmerPhase: Float,
    /** Alpha-breath cycle, 2–5 s (§20.2.4). */
    val shimmerPeriodMs: Float,
    /** Current alpha, 0.45–0.95 — recomputed every update. */
    var shimmer: Float = 0.7f,
    /** Current radius incl. speaking pulse — recomputed every update. */
    var vibRadiusDp: Float = baseRadiusDp,
    /** Current speaking jitter, dp — recomputed every update. */
    var jitterXD: Float = 0f,
    var jitterYDp: Float = 0f,
) {
    val colorArgb: Int get() = DotPalette.COLORS[colorIdx.coerceIn(0, DotPalette.COLORS.size - 1)]
}

class DotFieldModel(val dots: List<Dot>) {
    /** Milliseconds since the model was created — drives bob + shimmer. */
    var elapsedMs: Long = 0L
        private set
    /** Shimmer clock — advances slower in low-power mode and faster with
     *  the emotion energy (§20.2.6: energy scales drift speed + shimmer rate). */
    private var shimmerMs: Long = 0L

    /**
     * Advance the field by dtMs (§20.2.3–§20.2.5):
     * - anti-gravity: slow upward drift INSIDE the unit circle; a dot
     *   that reaches the rim bounces back inward (reflected across the
     *   rim + a small random inward nudge) — it never leaves the circle
     *   and never wraps top → bottom; low-power slows to a near-still
     *   sway, never fully frozen;
     * - shimmer: per-dot alpha breathes 0.45–0.95, unsynchronised;
     * - speaking vibration: radius * (1 + level * 0.9) + jitter ±level * 5 dp.
     *   level = LiveState.audioLevel (0 when idle/listening — calm drift).
     */
    fun update(dtMs: Long, audioLevel: Float, energy: Float = 1f, lowPower: Boolean = false) {
        val dt = dtMs.coerceIn(0L, 250L)
        elapsedMs += dt
        val speedScale = (if (lowPower) 0.35f else 1f) * (0.7f + energy.coerceIn(0f, 2f) * 0.6f)
        shimmerMs += (dt * speedScale).toLong()
        val level = audioLevel.coerceIn(0f, 1f)
        for (d in dots) {
            d.y -= d.driftSpeed * speedScale * (dt / 1000f)
            // §20.2.3 correction: bounce back inward at the rim — reflect
            // the dot across the circle rim and nudge it further inward,
            // so it re-enters the field instead of hovering on the rim.
            val dx = d.x - 0.5f
            val dy = d.y - 0.5f
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 0.48f && dist > 0f) {
                val nx = dx / dist
                val ny = dy / dist
                val inside = (0.96f - dist - (0.02f + Random.nextFloat() * 0.04f)).coerceAtLeast(0.05f)
                d.x = 0.5f + nx * inside
                d.y = 0.5f + ny * inside
            }
            val s = 0.7f + 0.25f * sin(2.0 * PI * (shimmerMs.toDouble() / d.shimmerPeriodMs) + d.shimmerPhase).toFloat()
            d.shimmer = s.coerceIn(0.45f, 0.95f)
            d.vibRadiusDp = d.baseRadiusDp * (1f + level * 0.9f)
            if (level > 0.01f) {
                val j = level * 5f
                d.jitterXD = (Random.nextFloat() * 2f - 1f) * j
                d.jitterYDp = (Random.nextFloat() * 2f - 1f) * j
            } else {
                d.jitterXD = 0f
                d.jitterYDp = 0f
            }
        }
    }

    /** Side-to-side bob, ±6 dp (§20.2.3) — each dot its own phase/period. */
    fun bobOffsetXD(dot: Dot): Float =
        (sin(2.0 * PI * (elapsedMs.toDouble() / dot.bobPeriodMs) + dot.bobPhase) * 6.0).toFloat()

    companion object {
        /** Circle field (§20.2.6): ~90 dots — the circle is small, mostly
         *  small dots. hero() is kept only for the unused Compose renderer
         *  (DotField.kt); the hero itself never uses the dot field. */
        fun hero(seed: Long = 20261127L): DotFieldModel = DotFieldModel(generate(90, seed))

        /** Floating overlay field (§20.2.6): ~90 dots in the bottom circle. */
        fun floating(seed: Long = 7L): DotFieldModel = DotFieldModel(generate(90, seed))

        /**
         * Generate the field INSIDE a unit circle (§20.2.1, correction):
         * dense in the centre, sparse toward the rim — the reference-image
         * pattern cropped into a round shape. 70% of the dots form the
         * dense core (radius sampled so the middle is densest); the rest
         * are sparse dots spread out to the rim. Centre dots are mostly
         * Deep Blue / Violet / Cyan (the reference's blue core); outer
         * dots use the full palette as accents. No figure shape, no grid,
         * no symmetry. x/y are fractions of the bounding square
         * (centre 0.5, 0.5; circle radius 0.5); the renderer scales the
         * unit circle to the circle's diameter.
         */
        private fun generate(count: Int, seed: Long): List<Dot> {
            val rnd = Random(seed)
            val dots = ArrayList<Dot>(count)
            val coreCount = (count * 0.7f).toInt()
            repeat(count) { i ->
                val ang = rnd.nextFloat() * (2f * PI.toFloat())
                // Dense core (70%): radius biased hard toward the centre;
                // sparse rim (30%): radius spread uniformly out to the rim.
                val frac = if (i < coreCount) {
                    val u = rnd.nextFloat()
                    0.5f * u * u // core: densest in the middle
                } else {
                    0.5f * sqrt(rnd.nextFloat()) // rim: uniform out to the edge
                }
                val x = (0.5f + cos(ang) * frac).coerceIn(0.02f, 0.98f)
                val y = (0.5f + sin(ang) * frac).coerceIn(0.02f, 0.98f)
                val core = frac < 0.15f
                val radius = if (core) 2.5f + rnd.nextFloat() * 5.5f else 1.5f + rnd.nextFloat() * 3.5f
                val colorIdx = if (core) when (rnd.nextFloat()) {
                    in 0f..0.4f -> 2 // Deep Blue
                    in 0.4f..0.7f -> 3 // Violet
                    in 0.7f..0.9f -> 0 // Cyan
                    else -> listOf(1, 4, 6)[rnd.nextInt(3)] // Sky / Purple / Magenta accent
                } else rnd.nextInt(DotPalette.COLORS.size)
                dots.add(mkDot(x, y, radius, colorIdx, rnd))
            }
            return dots
        }

        private fun mkDot(x: Float, y: Float, radiusDp: Float, colorIdx: Int, rnd: Random): Dot =
            Dot(
                x = x,
                y = y,
                baseRadiusDp = radiusDp,
                colorIdx = colorIdx,
                driftSpeed = (1f / (25f + rnd.nextFloat() * 15f)), // crosses in ~25–40 s
                bobPhase = rnd.nextFloat() * (2f * PI.toFloat()),
                bobPeriodMs = 4000f + rnd.nextFloat() * 5000f, // 4–9 s
                shimmerPhase = rnd.nextFloat() * (2f * PI.toFloat()),
                shimmerPeriodMs = 2000f + rnd.nextFloat() * 3000f, // 2–5 s
            )
    }
}
