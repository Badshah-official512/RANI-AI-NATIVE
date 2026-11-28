package com.badshah222.rani.ai.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.badshah222.rani.ai.MainActivity
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.features.home.RaniLiveActivity
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating orb (MYRA's signature): a small glowing RANI bubble that floats
 * over other apps once the user grants the overlay permission. Tap = open the app
 * straight into the voice call; drag = move it; long-press = remove it. It never
 * records or listens by itself — it is only a shortcut the user can dismiss.
 *
 * v7.5: agentic look — the orb gets a rotating neon arc-ring, a breathing halo
 * and a live dot; the edge glow becomes two energy comets travelling around the
 * screen border over a soft base glow.
 *
 * v3.0.0 §23.2 Fix 3: the floating CALL CARD is DELETED — no Mute/End
 * surface over other apps, no card, no number. The ONLY overlay during a
 * call is the bottom-centre dot-field orb (§20.2) + a MYRA-style speech
 * bubble showing the latest caption (no buttons, no End). Tap the orb =
 * back to the app (MainActivity + EXTRA_WAKE). Mute/Stop live only in the
 * Home hero, reached by tapping the orb.
 */
class FloatingOrbService : Service() {
    companion object {
        @Volatile var running = false
        fun start(ctx: Context) { ctx.startService(Intent(ctx, FloatingOrbService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatingOrbService::class.java)) }
    }

    private var wm: WindowManager? = null
    // v1.8.0 §20.2.6 + correction (2026-11-27): the floating avatar is
    // ONE round dot-field circle at the BOTTOM of the screen
    // (DotFieldView, diameter = orbFloatSize) — NOT full-screen, NOT the
    // old AgenticOrbView medallion. AgenticOrbView stays in this file,
    // unused; only the service stops using it.
    private var dotField: DotFieldView? = null
    private var glow: EdgeGlowView? = null
    // v3.0.0 §23.2 Fix 3: MYRA-style speech bubble under the dot-field orb
    // (latest caption only — no buttons, no End) + the guardian dot that
    // shows while screen watch is ON (§23.8).
    private var orbBubble: TextView? = null
    private var guardianDot: View? = null
    private var store: SecureStore? = null
    private val visHandler = Handler(Looper.getMainLooper())
    private val visLoop = object : Runnable {
        override fun run() {
            // v1.7.0 (§19.3.3): state comes from the shared LiveState fed by
            // the RaniBridge — HttpLiveSession is dead in v1.6.1 and never
            // becomes active, so the card showed stale state.
            val inCall = LiveState.active.value
            // B-1: "in front" means EITHER Activity — MainActivity's flag
            // alone is wrong while a call is up, because MainActivity stops
            // the moment RaniLiveActivity resumes (its onStop would say the
            // app is in the background and the card would cover the call
            // page itself). The call Activity reports its own foreground
            // state, so the card/avatar appear only once the user truly
            // leaves the call screen.
            val fg = MainActivity.appInForeground || RaniLiveActivity.callInForeground
            val liveVoiceOn = store?.autoVoiceEnabled ?: true
            // v3.0.0 §23.2 Fix 3: the dot-field orb is the ONLY overlay —
            // it shows whenever the app is not in front and Live Voice is
            // ON, INCLUDING during a call (the old condition hid it in-call
            // in favour of the deleted call card). Only the circle itself
            // is touchable — everything outside it is transparent and
            // pass-through.
            dotField?.visibility = if (!fg && liveVoiceOn) View.VISIBLE else View.GONE
            glow?.visibility = if (!fg && liveVoiceOn) View.VISIBLE else View.GONE
            updateOrbBubble(inCall && !fg)
            // v3.0.0 §23.8: guardian dot while screen watch is running.
            guardianDot?.visibility = if (!fg && ScreenShareService.running.value) View.VISIBLE else View.GONE
            visHandler.postDelayed(this, 600)
        }
    }

    /** v1.7.0 §19.3: the FULL CHARACTER image as a head/shoulders medallion
     *  crop (§18 — top-centre of the full figure, never the launcher bitmap,
     *  never a plain ball). Drawn at 82% inside the circle by AgenticOrbView. */
    private fun medallionBitmap(): Bitmap? {
        val src = runCatching { BitmapFactory.decodeResource(resources, R.drawable.rani_character) }.getOrNull()
            ?: return null
        return runCatching {
            val x = (src.width * 0.20f).toInt()
            val w = (src.width * 0.60f).toInt()
            val h = (src.height * 0.40f).toInt()
            Bitmap.createBitmap(src, x, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))
        }.getOrNull() ?: src
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        store = (application as RaniApp).container.store
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // v1.8.0 §20.2.6 + correction (2026-11-27): the floating avatar is
        // ONE round dot-field circle at the BOTTOM of the screen —
        // diameter × diameter (diameter = SecureStore.orbFloatSize,
        // default 96 dp, range 56–200, the existing Settings slider),
        // TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE |
        // FLAG_LAYOUT_NO_LIMITS, TRANSLUCENT, gravity BOTTOM |
        // CENTER_HORIZONTAL, bottom margin ~24 dp (just above the
        // navigation bar). The circle keeps the OLD AVATAR's touch
        // behaviour: drag moves it, tap (<12 px) opens the app straight
        // into the hero call (MainActivity + EXTRA_WAKE), long-press
        // (>600 ms, <12 px move) dismisses it (stopSelf). Everything
        // outside the circle is transparent and pass-through.
        val d = resources.displayMetrics.density
        val diaPx = ((store?.orbFloatSize ?: 96).coerceIn(56, 200) * d).toInt()
        val field = DotFieldView(this)
        val fParams = WindowManager.LayoutParams(
            diaPx, diaPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; x = 0; y = (24 * d).toInt() }
        dotField = field
        field.visibility = View.GONE
        var fDownX = 0; var fDownY = 0; var fStartX = 0; var fStartY = 0; var fDownAt = 0L
        field.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // F10 (v1.8.0 fix): the window is a dia x dia SQUARE but
                    // the circle is inscribed in it — the corner patches
                    // outside the circle (~21% of the square) must NOT
                    // swallow touches meant for the app underneath. A
                    // touch farther than dia/2 from the view centre passes
                    // through (return false); drag/tap/long-press inside
                    // the circle are unchanged.
                    val cx = v.width / 2f; val cy = v.height / 2f
                    val r = minOf(v.width, v.height) / 2f
                    val dx = e.x - cx; val dy = e.y - cy
                    if (dx * dx + dy * dy > r * r) return@setOnTouchListener false
                    fDownX = e.rawX.toInt(); fDownY = e.rawY.toInt(); fStartX = fParams.x; fStartY = fParams.y; fDownAt = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    // BOTTOM gravity: y is measured up from the bottom edge,
                    // so a downward drag (rawY grows) DECREASES y.
                    fParams.x = fStartX + (e.rawX.toInt() - fDownX); fParams.y = fStartY - (e.rawY.toInt() - fDownY)
                    runCatching { wm?.updateViewLayout(v, fParams) }; true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(e.rawX.toInt() - fDownX) + Math.abs(e.rawY.toInt() - fDownY)
                    val heldMs = System.currentTimeMillis() - fDownAt
                    if (moved < 12 && heldMs > 600) {
                        // Long-press: dismiss the circle (stopSelf — the
                        // service restarts on the next cold start).
                        stopSelf()
                    } else if (moved < 12) {
                        val i = Intent(this, MainActivity::class.java)
                            .putExtra("EXTRA_WAKE", "1")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm?.addView(field, fParams) }
        // v7.3: RGB edge glow — call ke doran poori screen ke kinaron par roshni,
        // app background mein ho tab bhi rehti hai (service sirf call ke doran chalti hai)
        val gv = EdgeGlowView(this)
        val gParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        glow = gv
        runCatching { wm?.addView(gv, gParams) }
        // v3.0.0 §23.2 Fix 3: MYRA-style speech bubble (caption only) above
        // the dot-field orb, and the §23.8 guardian dot (green, top-right of
        // the orb) while screen watch is running. No buttons anywhere.
        val bubble = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(0xE6171130.toInt())
                setStroke((1.5f * d).toInt(), 0xFF8B3DFF.toInt())
            }
            visibility = View.GONE
        }
        orbBubble = bubble
        val bParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; x = 0; y = (24 * d).toInt() + diaPx + (10 * d).toInt() }
        runCatching { wm?.addView(bubble, bParams) }
        val gDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(34, 255, 170))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
            visibility = View.GONE
        }
        guardianDot = gDot
        // FIX (CI run #50): was `val gParams` — duplicate of the edge-glow
        // params above in the same scope (2 conflicting-declaration errors).
        val gdParams = WindowManager.LayoutParams(
            (14 * d).toInt(), (14 * d).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; x = (diaPx * 0.35).toInt(); y = (24 * d).toInt() + (diaPx * 0.82).toInt() }
        runCatching { wm?.addView(gDot, gdParams) }
        running = true
        visHandler.post(visLoop)
    }

    override fun onDestroy() {
        visHandler.removeCallbacks(visLoop)
        glow?.stopAnim()
        dotField?.stop()
        glow?.let { runCatching { wm?.removeView(it) } }
        dotField?.let { runCatching { wm?.removeView(it) } }
        orbBubble?.let { runCatching { wm?.removeView(it) } }
        guardianDot?.let { runCatching { wm?.removeView(it) } }
        glow = null
        dotField = null; orbBubble = null; guardianDot = null
        running = false
        super.onDestroy()
    }

    /** v3.0.0 §23.2 Fix 3 (MYRA OrbBubble): the speech bubble next to the
     *  orb shows the latest caption (max ~260 chars, masked by LiveState —
     *  never a phone number), with no buttons and no status line. Visible
     *  only while a call is live and the app is not in front. */
    private fun updateOrbBubble(show: Boolean) {
        val b = orbBubble ?: return
        if (!show) { if (b.visibility != View.GONE) b.visibility = View.GONE; return }
        val cap = LiveState.caption.value
        val who = LiveState.captionWho.value
        val text = when {
            cap.isEmpty() -> ""
            who.isEmpty() -> cap
            else -> "$who: $cap"
        }.take(260)
        if (text.isEmpty()) { b.visibility = View.GONE; return }
        if (b.text.toString() != text) b.text = text
        if (b.visibility != View.VISIBLE) b.visibility = View.VISIBLE
    }
}

/**
 * v7.5 agentic orb: the RANI orb image inside a rotating neon arc-ring
 * (purple / blue / green), a breathing halo behind it, and a pulsing
 * green LIVE dot — the same visual language as the call screen.
 */
class AgenticOrbView(ctx: Context, private val bmp: Bitmap?) : View(ctx) {
    /** v1.7.0 §19.8.4: the ring/halo colour follows the hero's emotion glow
     *  (set by FloatingOrbService from LiveState.glowColor). */
    @Volatile var moodColor: Int = Color.rgb(139, 61, 255)
    private val d = ctx.resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.argb(80, 160, 170, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.4f * d; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF171130.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val rect = RectF()
    private val clip = Path()
    private var startAt = System.currentTimeMillis()
    private var animOn = false
    private val tick = object : Runnable {
        override fun run() { invalidate(); if (animOn) handler.postDelayed(this, 40) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animOn = true; handler.post(tick) }
    override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }
    fun stopAnim() { animOn = false; handler.removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val now = System.currentTimeMillis()
        val pulse = (sin((now % 2400) / 2400.0 * 2 * Math.PI).toFloat() + 1f) / 2f // 0..1 breathing
        val faceR = min(w, h) / 2f - 9f * d

        // breathing halo, in the emotion colour (§19.8.4)
        haloPaint.color = Color.argb((36 + pulse * 46).toInt(), Color.red(moodColor), Color.green(moodColor), Color.blue(moodColor))
        canvas.drawCircle(cx, cy, faceR + 5f * d + pulse * 3.5f * d, haloPaint)

        // face: character medallion (§18) scaled to 82% inside the circle,
        // or a dark disc when the bitmap is missing.
        if (bmp != null) {
            clip.reset(); clip.addCircle(cx, cy, faceR, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            val target = faceR * 2 * 0.82f
            val scale = target / maxOf(bmp.width, bmp.height).toFloat()
            val dw = bmp.width * scale; val dh = bmp.height * scale
            canvas.drawBitmap(bmp, cx - dw / 2f, cy - dh / 2f, null)
            canvas.restore()
        } else {
            canvas.drawCircle(cx, cy, faceR, fillPaint)
        }

        // rotating neon arcs
        val ringR = faceR + 4.5f * d
        rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        canvas.drawCircle(cx, cy, ringR, ringBase)
        val rot = ((now - startAt) % 6000) / 6000f * 360f
        arcPaint.color = moodColor
        canvas.drawArc(rect, rot, 105f, false, arcPaint)
        arcPaint.color = Color.rgb(59, 130, 246)
        canvas.drawArc(rect, rot + 140f, 60f, false, arcPaint)
        arcPaint.color = Color.rgb(34, 255, 170)
        canvas.drawArc(rect, rot + 235f, 42f, false, arcPaint)

        // live dot (bottom-right, on the ring)
        val ang = Math.toRadians(45.0)
        val dx = cx + (ringR * cos(ang)).toFloat(); val dy = cy + (ringR * sin(ang)).toFloat()
        val dr = (4.6f + pulse * 1.1f) * d
        canvas.drawCircle(dx, dy, dr + 1.6f * d, dotRing)
        dotPaint.color = Color.rgb(34, 255, 170)
        canvas.drawCircle(dx, dy, dr, dotPaint)
    }
}

/**
 * Screen ke kinaron par "energy border": halki base roshni ke upar do
 * chamakti dum-daar dharaein (comets) mukhalif simton mein safar karti
 * hain — purple/blue aur green — bilkul agentic system wali harkat.
 */
class EdgeGlowView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val colors = intArrayOf(
        Color.rgb(59, 130, 246), Color.rgb(239, 68, 68),
        Color.rgb(163, 230, 53), Color.rgb(139, 61, 255),
    )
    private var startAt = System.currentTimeMillis()
    private val tick = object : Runnable {
        override fun run() { invalidate(); if (animOn) handler.postDelayed(this, 50) }
    }
    private var animOn = false
    init { alpha = 0.95f }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animOn = true; handler.post(tick) }
    override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }
    fun stopAnim() { animOn = false; handler.removeCallbacks(tick) }

    private fun lerpColor(c1: Int, c2: Int, f: Float): Int = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt(),
    )

    /** Rounded-rect perimeter par distance d (px) par point (x, y) deta hai. */
    private fun pointAt(dTotal: Float, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): FloatArray {
        val wt = (x1 - x0) - 2 * r
        val ht = (y1 - y0) - 2 * r
        val arc = (Math.PI * r / 2).toFloat()
        var d = dTotal
        if (d < wt) return floatArrayOf(x0 + r + d, y0)
        d -= wt
        if (d < arc) { val a = -Math.PI / 2 + (d / arc) * (Math.PI / 2); return floatArrayOf(x1 - r + (r * cos(a)).toFloat(), y0 + r + (r * sin(a)).toFloat()) }
        d -= arc
        if (d < ht) return floatArrayOf(x1, y0 + r + d)
        d -= ht
        if (d < arc) { val a = 0.0 + (d / arc) * (Math.PI / 2); return floatArrayOf(x1 - r + (r * cos(a)).toFloat(), y1 - r + (r * sin(a)).toFloat()) }
        d -= arc
        if (d < wt) return floatArrayOf(x1 - r - d, y1)
        d -= wt
        if (d < arc) { val a = Math.PI / 2 + (d / arc) * (Math.PI / 2); return floatArrayOf(x0 + r + (r * cos(a)).toFloat(), y1 - r + (r * sin(a)).toFloat()) }
        d -= arc
        if (d < ht) return floatArrayOf(x0, y1 - r - d)
        d -= ht
        val a = Math.PI + (d / arc) * (Math.PI / 2)
        return floatArrayOf(x0 + r + (r * cos(a)).toFloat(), y0 + r + (r * sin(a)).toFloat())
    }

    private fun drawComet(canvas: Canvas, head: Float, perim: Float, inset: Float, r: Float, colA: Int, colB: Int) {
        val trail = 26
        val stepPx = 9f * density
        for (i in trail - 1 downTo 0) {
            val f = i / trail.toFloat() // 0 = head, 1 = tail
            var dd = (head - i * stepPx) % perim; if (dd < 0) dd += perim
            val p = pointAt(dd, inset, inset, width - inset, height - inset, r)
            dotPaint.color = lerpColor(colA, colB, f)
            dotPaint.alpha = ((1f - f) * 235).toInt()
            val rad = (3.6f - f * 2.3f) * density
            canvas.drawCircle(p[0], p[1], rad, dotPaint)
        }
        dotPaint.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val t = ((now - startAt) % 4800) / 4800f
        val seg = (t * colors.size).toInt() % colors.size
        val frac = (t * colors.size) - (t * colors.size).toInt()
        val baseCol = lerpColor(colors[seg], colors[(seg + 1) % colors.size], frac)
        val inset = 2.2f * density
        val r = 26f * density
        rect.set(inset, inset, width - inset, height - inset)

        // soft outer glow
        glowPaint.color = baseCol; glowPaint.alpha = 26; glowPaint.strokeWidth = 8f * density
        canvas.drawRoundRect(rect, r, r, glowPaint)
        // faint full border (purani pehchan, halki)
        basePaint.color = baseCol; basePaint.alpha = 64; basePaint.strokeWidth = 2.2f * density
        canvas.drawRoundRect(rect, r, r, basePaint)

        // two energy comets, opposite directions
        val wt = (width - 2 * inset) - 2 * r
        val ht = (height - 2 * inset) - 2 * r
        val perim = 2 * wt + 2 * ht + 2 * Math.PI.toFloat() * r
        if (perim <= 0) return
        val headA = ((now - startAt) % 9000) / 9000f * perim
        drawComet(canvas, headA, perim, inset, r, Color.rgb(139, 61, 255), Color.rgb(59, 130, 246))
        val headB = (headA + perim / 2f) % perim
        drawComet(canvas, headB, perim, inset, r, Color.rgb(34, 255, 170), Color.rgb(163, 230, 53))
    }
}
