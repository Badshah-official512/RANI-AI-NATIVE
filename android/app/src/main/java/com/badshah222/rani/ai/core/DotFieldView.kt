package com.badshah222.rani.ai.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.ui.character.DotFieldModel

/**
 * v1.8.0 §20.2.6 + correction (2026-11-27) — the floating dot field
 * (android.view.View). The VIEW itself is ONE round circle at the BOTTOM
 * of the screen (width = height = the circle diameter, set by
 * FloatingOrbService from SecureStore.orbFloatSize); all drawing is
 * clipped to that circle and everything outside it is transparent, so
 * the app underneath shows through and stays usable. The view is
 * touchable (drag / tap / long-press handled by FloatingOrbService) —
 * it REPLACES the old AgenticOrbView medallion avatar.
 *
 * Same shared DotFieldModel (floating variant, ~90 dots in the circle);
 * the halo behind the field is the emotion colour from LiveState, and the
 * dots vibrate with LiveState.audioLevel while RANI speaks (§20.2.5).
 * One Canvas pass per frame, pre-allocated paints, Handler tick (~30 fps).
 * Low-power mode draws every other dot (§20.2.6 — counts halved).
 * AgenticOrbView is NOT deleted (kept in FloatingOrbService.kt, unused).
 */
class DotFieldView(ctx: Context) : View(ctx) {
    private val model = DotFieldModel.floating()
    // F1 (v1.8.0 fix): named tickHandler — a `handler` property here would
    // collide with View.getHandler() (Kotlin accidental-override, same JVM
    // signature) and fail compilation. AgenticOrbView / EdgeGlowView
    // correctly use the inherited View.handler and are untouched.
    private val tickHandler = Handler(Looper.getMainLooper())
    private var lastTickMs = 0L
    private var running = false

    // Pre-allocated paints — no per-dot object allocation per frame.
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var haloColorArgb: Int = 0
    private var haloW = 0
    private var haloH = 0

    // §20.2.6 correction: the clip circle — the view IS the circle, so all
    // drawing is clipped to it (rebuilt whenever the view size changes).
    private val circlePath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        circlePath.reset()
        circlePath.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastTickMs == 0L) 33L else (now - lastTickMs).coerceIn(1L, 100L)
            lastTickMs = now
            model.update(dt, LiveState.audioLevel.value, 1f, lowPower())
            invalidate()
            if (running) tickHandler.postDelayed(this, 33)
        }
    }

    private fun lowPower(): Boolean = runCatching {
        (context.applicationContext as RaniApp).container.store.lowPower
    }.getOrDefault(false)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastTickMs = 0L
        tickHandler.post(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun stop() {
        running = false
        tickHandler.removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // §20.2.6 correction: clip ALL drawing to the circle — nothing is
        // ever drawn outside it, and the area outside stays transparent.
        canvas.save()
        canvas.clipPath(circlePath)
        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f

        // §19.8/§20.2.6: the emotion-colour halo behind the dots (rebuilt
        // only when the colour or the size changes).
        val glowArgb = LiveState.glowColor.value.toArgb()
        if (glowArgb != haloColorArgb || width != haloW || height != haloH) {
            haloColorArgb = glowArgb
            haloW = width
            haloH = height
            val radius = minOf(w, h) * 0.62f
            haloPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb((0.30f * 255).toInt(), Color.red(glowArgb), Color.green(glowArgb), Color.blue(glowArgb)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, minOf(w, h) * 0.62f, haloPaint)

        val dots = model.dots
        val step = if (lowPower()) 2 else 1
        var i = 0
        while (i < dots.size) {
            val d = dots[i]
            val x = d.x * w + model.bobOffsetXD(d) * density + d.jitterXD * density
            val y = d.y * h + d.jitterYDp * density
            val r = d.vibRadiusDp * density
            val alpha = (d.shimmer * 255f).toInt().coerceIn(0, 255)
            // §20.2.4: faint radial halo behind the larger dots (8% alpha).
            if (d.baseRadiusDp >= 4f) {
                haloDotPaint.color = d.colorArgb
                haloDotPaint.alpha = (0.08f * 255f).toInt()
                canvas.drawCircle(x, y, r * 2.2f, haloDotPaint)
            }
            // The dot itself, alpha breathing 0.45–0.95.
            dotPaint.color = d.colorArgb
            dotPaint.alpha = alpha
            canvas.drawCircle(x, y, r, dotPaint)
            // Soft white core, fading in/out — lit from inside.
            corePaint.color = Color.WHITE
            corePaint.alpha = (d.shimmer * 0.45f * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * 0.4f, corePaint)
            i += step
        }
        canvas.restore()
    }
}
