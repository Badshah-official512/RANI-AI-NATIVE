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
import androidx.compose.ui.graphics.toArgb
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
 * v1.5.0: floating CALL CARD — while a Live call is active and the app is NOT
 * in front, this card (character, status, captions, Mute + End) floats over
 * other apps so the boss can look at something else and keep talking to RANI.
 * The orb hides while the card is up; tap the card body = back to the full
 * call screen; Mute/End act on the live call directly. FLAG_NOT_FOCUSABLE, so
 * the app underneath stays fully usable. The card never touches the mic or
 * the audio — it only reads the session state and calls setMuted()/stop().
 */
class FloatingOrbService : Service() {
    companion object {
        @Volatile var running = false
        fun start(ctx: Context) { ctx.startService(Intent(ctx, FloatingOrbService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatingOrbService::class.java)) }
    }

    private var wm: WindowManager? = null
    private var orb: AgenticOrbView? = null
    private var glow: EdgeGlowView? = null
    // v1.5.0 — floating call window (see buildCallCard): shown only while a
    // Live call is active AND the app is not in front.
    private var callCard: LinearLayout? = null
    private var callStatus: TextView? = null
    private var callCaption: TextView? = null
    private var callMuteBtn: Button? = null
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
            callCard?.visibility = if (inCall && !fg) View.VISIBLE else View.GONE
            orb?.visibility = if (!fg && !inCall && liveVoiceOn) View.VISIBLE else View.GONE
            glow?.visibility = if (!fg && liveVoiceOn) View.VISIBLE else View.GONE
            // §19.8.4: the avatar's glow ring shows the same emotion colour
            // as the hero, so the avatar's mood matches.
            (orb as? AgenticOrbView)?.moodColor = LiveState.glowColor.value.toArgb()
            if (inCall) updateCallCard()
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
        // v1.7.0 §19.3: size honours the Settings slider orbFloatSize
        // (default 96, range 56–200) instead of the old hardcoded 76 dp.
        val orbDp = (store?.orbFloatSize ?: 96).coerceIn(56, 200)
        val sizePx = (orbDp * resources.displayMetrics.density).toInt()
        val bmp = medallionBitmap()
        val iv = AgenticOrbView(this, bmp).apply { contentDescription = "RANI AI" }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            sizePx, sizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = (54 * resources.displayMetrics.density).toInt() }

        var downX = 0; var downY = 0; var startX = 0; var startY = 0; var downAt = 0L
        iv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX.toInt(); downY = e.rawY.toInt(); startX = params.x; startY = params.y; downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX.toInt() - downX); params.y = startY + (e.rawY.toInt() - downY)
                    runCatching { wm?.updateViewLayout(v, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(e.rawX.toInt() - downX) + Math.abs(e.rawY.toInt() - downY)
                    if (moved < 12) {
                        if (System.currentTimeMillis() - downAt > 600) {
                            stopSelf() // long-press removes the orb
                        } else {
                            val i = Intent(this, MainActivity::class.java)
                                .putExtra("EXTRA_WAKE", "1")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            startActivity(i)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        orb = iv
        runCatching { wm?.addView(iv, params) }
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
        // v1.5.0: floating CALL CARD — built once here, only shown by the
        // visLoop while a Live call is active (see buildCallCard).
        buildCallCard(bmp, type)
        running = true
        visHandler.post(visLoop)
    }

    override fun onDestroy() {
        visHandler.removeCallbacks(visLoop)
        glow?.stopAnim()
        orb?.stopAnim()
        glow?.let { runCatching { wm?.removeView(it) } }
        orb?.let { runCatching { wm?.removeView(it) } }
        callCard?.let { runCatching { wm?.removeView(it) } }
        glow = null
        orb = null; callCard = null; callStatus = null; callCaption = null; callMuteBtn = null
        running = false
        super.onDestroy()
    }

    /**
     * The floating call window (v1.5.0): a small dark card over other apps —
     * character thumb + call status, the latest caption line, and Mute + End.
     * FLAG_NOT_FOCUSABLE so the app underneath stays fully usable; drag the
     * card body to move it, tap the body (no drag) to reopen the full call
     * screen. The buttons act on the live HTTP session directly.
     */
    private fun buildCallCard(bmp: Bitmap?, type: Int) {
        val d = resources.displayMetrics.density
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * d
            setColor(0xE6171130.toInt()) // dark panel, mostly opaque
            setStroke((1.5f * d).toInt(), 0xFF8B3DFF.toInt())
        }
        val pad = (12 * d).toInt()
        card.setPadding(pad, pad, pad, pad)

        // Row 1: character thumb + status lines
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thumb = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (10 * d).toInt()
            }
        }
        callStatus = TextView(this).apply {
            text = "RANI is listening…"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "RANI AI · Real voice · Gemini Live"
            setTextColor(Color.argb(200, 190, 180, 255))
            textSize = 11f
        }
        texts.addView(callStatus); texts.addView(sub)
        row1.addView(thumb); row1.addView(texts)
        card.addView(row1)

        // Caption line — what RANI just said (or what the user said).
        callCaption = TextView(this).apply {
            setTextColor(Color.argb(220, 220, 214, 255))
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * d).toInt()
            }
            visibility = View.GONE
        }
        card.addView(callCaption)

        // Row 2: Mute + End (act on the live session directly).
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * d).toInt()
            }
        }
        callMuteBtn = Button(this).apply {
            text = "Mute"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * d
                setColor(0xFF2A2145.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // v1.7.0: Mute goes through LiveState + the page (window.setRaniMuted).
                val next = !LiveState.muted.value
                LiveState.setMuted(next)
                sendBroadcast(Intent(RaniLiveActivity.ACTION_SET_LIVE_MUTED).putExtra(RaniLiveActivity.EXTRA_MUTED, next))
            }
        }
        val endBtn = Button(this).apply {
            text = "End"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * d
                setColor(0xFFD92D20.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * d).toInt()
            }
            setOnClickListener {
                LiveState.onEnded()
                sendBroadcast(Intent(RaniLiveActivity.ACTION_END_LIVE))
            }
        }
        row2.addView(callMuteBtn); row2.addView(endBtn)
        card.addView(row2)

        // Drag anywhere on the card body; tap (no drag) reopens the full call screen.
        val params = WindowManager.LayoutParams(
            (300 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = (54 * d).toInt() }
        var downX = 0; var downY = 0; var startX = 0; var startY = 0; var downAt = 0L
        card.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX.toInt(); downY = e.rawY.toInt(); startX = params.x; startY = params.y; downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX.toInt() - downX); params.y = startY + (e.rawY.toInt() - downY)
                    runCatching { wm?.updateViewLayout(v, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(e.rawX.toInt() - downX) + Math.abs(e.rawY.toInt() - downY)
                    if (moved < 12) {
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
        callCard = card
        card.visibility = View.GONE
        runCatching { wm?.addView(card, params) }
    }

    /** Refresh the call card's status, caption and Mute label from LiveState (§19.3.3). */
    private fun updateCallCard() {
        callStatus?.text = when (LiveState.state.value) {
            "connecting" -> "Connecting…"
            "listening" -> "RANI is listening…"
            "thinking" -> "RANI is thinking…"
            "speaking" -> "RANI is speaking…"
            "error", "ended" -> "Call ended"
            else -> "RANI is ready"
        }
        val cap = LiveState.caption.value
        val who = LiveState.captionWho.value
        val text = when {
            cap.isEmpty() -> ""
            who.isEmpty() -> cap
            else -> "$who: $cap"
        }
        callCaption?.let {
            it.text = text
            it.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        callMuteBtn?.text = if (LiveState.muted.value) "Unmute" else "Mute"
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
