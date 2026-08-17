package com.msnguard.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Orbit dial: the one control that matters on the main screen.
 *
 * Layers, outermost first:
 *  1. aura — a breathing bloom outside the ring, active states only
 *  2. two ripple rings that expand and fade, active states only
 *  3. the bezel: a static hairline plus a slowly rotating dashed ring
 *  4. the gauge — 72 ticks, every sixth longer, filling with the live accent
 *  5. the progress arc, with a lit head that orbits along it
 *  6. the glass core — body, wide specular, corner hotspot, occlusion, rim,
 *     and a sheen band that crosses every few seconds
 *  7. contents — a state glyph and a caption, or the session timer when up
 *
 * TWO THINGS IN HERE ARE LOAD-BEARING. Do not simplify either.
 *
 * GEOMETRY. The aura and the ripples deliberately paint OUTSIDE the ring. This
 * view runs with LAYER_TYPE_SOFTWARE, so Android allocates an offscreen bitmap
 * exactly the size of the VIEW and discards every pixel outside it before any
 * parent gets a say — clipChildren=false on the ancestors cannot save it. So the
 * measured box is always ring + [BLEED_DP], and BLEED_DP is DERIVED from the two
 * things that reach past the ring (a ripple at RIPPLE_GROWTH, the aura at
 * HALO_OUTSET + HALO_PULSE) rather than hand-tuned. Changing [RING_DP] alone can
 * therefore never crop the dial again.
 *
 * MOTION. One animator drives every ambient effect. Each effect used to own its
 * own ValueAnimator, which meant four independent phases beating against each
 * other and four invalidate() calls per frame on a software-rendered view. The
 * single loop below is both cheaper and, because the halo breath and the sheen
 * are now phase-locked, visibly calmer.
 */
class OrbitDialView(
    context: Context,
    private var palette: AppAppearance.Palette,
) : View(context) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, FAILED }

    var state: State = State.DISCONNECTED
        set(value) {
            val previous = field
            field = value
            contentDescription = when (value) {
                State.DISCONNECTED -> "Connect"
                State.FAILED -> "Connection failed, tap to retry"
                State.CONNECTING -> "Connecting"
                State.CONNECTED -> "Connected, tap to disconnect"
                State.DEGRADED -> "Connected but no traffic is passing, tap to disconnect"
            }
            if (previous != value) animateAccentTo(accentFor(value))
            if (value == State.CONNECTED || value == State.DEGRADED) {
                if (previous != State.CONNECTED && previous != State.DEGRADED) gaugeReveal = 0f
                animateGaugeReveal()
            } else {
                gaugeReveal = 0f
            }
            if (value == State.CONNECTING || value == State.CONNECTED || value == State.DEGRADED) {
                startLoop()
            } else {
                stopLoop()
            }
            invalidate()
        }

    /** Session uptime text drawn inside the core. Empty hides it. */
    var timerText: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val corePath = Path()
    private val glyphPath = Path()

    private var loopFraction = 0f
    private var pulse = 0f
    private var gaugeReveal = 0f
    private var loopAnimator: ValueAnimator? = null
    private var gaugeAnimator: ValueAnimator? = null
    private var accentAnimator: ValueAnimator? = null

    /**
     * The colour actually painted, as opposed to the colour the state implies.
     *
     * Kept separate so a state change can be crossfaded. Every draw call reads
     * this; nothing reads accentFor(state) directly except the animation setup.
     */
    private var liveAccent: Int = palette.muted

    private val monoTypeface: Typeface = Orbit.Type.MONO
    private val labelTypeface: Typeface = Orbit.Type.MEDIUM

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = false
        contentDescription = "Connect"
        // Shadow layers and sweep gradients need software rendering to be exact
        // on older GPUs; the view is small and repaints at most 20fps.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        liveAccent = accentFor(state)
    }

    fun applyPalette(next: AppAppearance.Palette) {
        palette = next
        liveAccent = accentFor(state)
        invalidate()
    }

    private fun accentFor(state: State): Int = when (state) {
        State.DISCONNECTED -> palette.muted
        State.CONNECTING -> palette.primary
        State.CONNECTED -> palette.connected
        State.DEGRADED -> palette.warning
        State.FAILED -> palette.danger
    }

    /**
     * Uniform shrink factor for the whole dial, bleed included.
     *
     * The console asks for this when its natural height would overflow the
     * viewport: shrinking the dial is how the screen stops scrolling. Because
     * the factor scales the measured box AND the ring together, the ratio
     * between them is untouched, so the aura and ripples keep exactly the
     * proportional room they have at 1.0 and cannot be cropped by shrinking.
     */
    var sizeScale: Float = 1f
        set(value) {
            val clamped = value.coerceIn(MIN_SIZE_SCALE, 1f)
            if (field != clamped) {
                field = clamped
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(((RING_DP + BLEED_DP) * 2 * sizeScale).roundToInt())
        val size = resolveSize(desired, widthMeasureSpec)
            .coerceAtMost(resolveSize(desired, heightMeasureSpec))
        // Always square: a non-square canvas would put the ring off-centre.
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) / 2f
        // The ring is RING_DP scaled by [sizeScale]. The second term is the
        // safety net: if a parent hands this view LESS than it asked for (narrow
        // screen, exact-size spec), the ring shrinks to the largest value that
        // still leaves the bleed intact rather than letting the outer layers get
        // shaved. Never remove it — it is the difference between a smaller dial
        // and a cropped one.
        val ring = minOf(
            dp(RING_DP) * sizeScale,
            half * RING_DP / (RING_DP + BLEED_DP).toFloat(),
        )
        // Every inner offset below is authored against RING_DP, so they follow
        // the ring by this factor instead of staying at a fixed dp and throwing
        // the proportions off whenever the dial shrinks.
        val geo = ring / dp(RING_DP)
        val accent = liveAccent
        val active = state == State.CONNECTED || state == State.DEGRADED

        if (active) {
            drawAura(canvas, cx, cy, ring, accent, geo)
            drawRipples(canvas, cx, cy, ring, accent)
        }
        drawBezel(canvas, cx, cy, ring, geo)
        drawGauge(canvas, cx, cy, ring, accent, geo)
        drawArc(canvas, cx, cy, ring, accent, geo)
        drawCore(canvas, cx, cy, ring, accent, active)
        drawContents(canvas, cx, cy, accent, active, geo)
    }

    /**
     * Soft breathing bloom just outside the ring.
     *
     * Not clamped to the view: the bloom is MEANT to spill past the ring, and
     * clamping it is what used to flatten the glow against the bottom edge.
     */
    private fun drawAura(canvas: Canvas, cx: Float, cy: Float, ring: Float, accent: Int, geo: Float) {
        val radius = ring + dp(HALO_OUTSET_DP) * geo + pulse * dp(HALO_PULSE_DP) * geo
        paint.style = Paint.Style.FILL
        // Four stops rather than three: the extra inner stop keeps the middle of
        // the dial clear so the bloom hugs the ring instead of washing the core.
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Sculpt.withAlpha(accent, 0f),
                Sculpt.withAlpha(accent, 0.04f),
                Sculpt.withAlpha(accent, 0.16f + pulse * 0.08f),
                Sculpt.withAlpha(accent, 0f),
            ),
            floatArrayOf(0.52f, 0.70f, 0.87f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    /** Two rings, half a cycle apart, expanding and fading out. */
    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, ring: Float, accent: Int) {
        paint.style = Paint.Style.STROKE
        for (offset in RIPPLE_PHASES) {
            val phase = (loopFraction + offset) % 1f
            // Fade with the square of the remaining life, not linearly: a linear
            // fade is still clearly visible at 70% expansion, which makes the
            // two rings read as a target rather than a pulse.
            val life = 1f - phase
            paint.strokeWidth = (1.6f * life + 0.4f) * density
            paint.color = Sculpt.withAlpha(accent, 0.42f * life * life)
            canvas.drawCircle(cx, cy, ring * (1f + phase * RIPPLE_GROWTH), paint)
        }
    }

    private fun drawBezel(canvas: Canvas, cx: Float, cy: Float, ring: Float, geo: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = Sculpt.withAlpha(palette.ink, 0.05f)
        canvas.drawCircle(cx, cy, ring, paint)

        // Slowly rotating dashed ring, drawn as short arcs rather than a
        // DashPathEffect so the rotation is exact and cheap.
        val inner = ring - dp(21) * geo
        paint.color = Sculpt.withAlpha(palette.ink, 0.06f)
        bounds.set(cx - inner, cy - inner, cx + inner, cy + inner)
        val spin = loopFraction * 12f
        var angle = spin
        while (angle < 360f + spin) {
            canvas.drawArc(bounds, angle, 4.5f, false, paint)
            angle += 11f
        }
    }

    /**
     * The instrument gauge.
     *
     * [TICK_COUNT] ticks with every [TICK_MAJOR_EVERY]th drawn longer and
     * brighter. That grouping is the whole point: with 60 identical ticks a
     * partially filled gauge is just "some green", because the eye has no
     * landmarks to count against. With majors it reads as a quantity.
     *
     * The lit run also carries a gradient from dim to full, so the fill has a
     * visible head and tail rather than being a uniform green block.
     */
    private fun drawGauge(canvas: Canvas, cx: Float, cy: Float, ring: Float, accent: Int, geo: Float) {
        val litCount = when (state) {
            State.CONNECTED, State.DEGRADED -> (TICK_LIT * gaugeReveal).roundToInt()
            else -> 0
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val minorLen = dp(6) * geo
        val majorLen = dp(11) * geo
        for (i in 0 until TICK_COUNT) {
            // -90 degrees so tick 0 sits at the top and the gauge fills clockwise.
            val rad = Math.toRadians((i * (360.0 / TICK_COUNT)) - 90.0)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val major = i % TICK_MAJOR_EVERY == 0
            val length = if (major) majorLen else minorLen
            val startR = ring - length
            val lit = i < litCount
            // Every third tick breathes while connecting: the gauge is warming
            // up, not measuring anything yet.
            val sweeping = state == State.CONNECTING && i % 3 == 0
            paint.strokeWidth = (if (major) 2f else 1.4f) * density
            when {
                lit -> {
                    // Dim at the tail, full at the head of the lit run.
                    val along = if (litCount <= 1) 1f else i / (litCount - 1f)
                    paint.color = Sculpt.withAlpha(accent, 0.45f + 0.55f * along)
                    paint.setShadowLayer(3.5f * density, 0f, 0f, Sculpt.withAlpha(accent, 0.75f))
                }
                sweeping -> {
                    paint.color = Sculpt.withAlpha(accent, 0.35f + pulse * 0.55f)
                    paint.setShadowLayer(3f * density, 0f, 0f, Sculpt.withAlpha(accent, 0.65f))
                }
                else -> {
                    paint.color = Sculpt.withAlpha(palette.ink, if (major) 0.16f else 0.09f)
                    paint.clearShadowLayer()
                }
            }
            canvas.drawLine(
                cx + cosA * startR, cy + sinA * startR,
                cx + cosA * ring, cy + sinA * ring,
                paint,
            )
        }
        paint.clearShadowLayer()
        paint.strokeCap = Paint.Cap.BUTT
    }

    /**
     * The progress arc and its orbiting head.
     *
     * The head is the piece that was missing. A pulsing arc communicates "busy";
     * a dot travelling along it communicates "busy at a rate", which is the
     * difference between a user waiting patiently and a user tapping again.
     */
    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, ring: Float, accent: Int, geo: Float) {
        val r = ring - dp(17) * geo
        bounds.set(cx - r, cy - r, cx + r, cy + r)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.strokeCap = Paint.Cap.ROUND

        paint.color = Sculpt.withAlpha(palette.ink, 0.055f)
        canvas.drawArc(bounds, 0f, 360f, false, paint)

        val sweep = when (state) {
            State.CONNECTED, State.DEGRADED -> 308f * gaugeReveal
            State.CONNECTING -> 70f + pulse * 150f
            else -> 0f
        }
        if (sweep <= 0.5f) {
            paint.strokeCap = Paint.Cap.BUTT
            return
        }
        val start = if (state == State.CONNECTING) loopFraction * 360f - 90f else -90f
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Sculpt.withAlpha(accent, 0.35f),
                accent,
                Sculpt.lighten(accent, 0.35f),
                Sculpt.withAlpha(accent, 0.35f),
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
        )
        canvas.drawArc(bounds, start, sweep, false, paint)
        paint.shader = null

        // The head: a bloom plus a solid core, sitting at the leading end.
        val headDeg = start + sweep
        val headRad = Math.toRadians(headDeg.toDouble())
        val hx = cx + cos(headRad).toFloat() * r
        val hy = cy + sin(headRad).toFloat() * r
        paint.style = Paint.Style.FILL
        paint.color = Sculpt.withAlpha(accent, 0.22f)
        canvas.drawCircle(hx, hy, 6f * density * geo, paint)
        paint.color = Sculpt.lighten(accent, 0.4f)
        paint.setShadowLayer(6f * density, 0f, 0f, Sculpt.withAlpha(accent, 0.9f))
        canvas.drawCircle(hx, hy, 2.4f * density * geo, paint)
        paint.clearShadowLayer()
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** The glass core — the same six-layer material as every other surface. */
    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, ring: Float, accent: Int, active: Boolean) {
        val r = ring * CORE_RATIO
        val base = Sculpt.blend(palette.surface, palette.ink, 0.035f)

        // Contact shadow, accent-tinted when the tunnel is up: a lit control
        // should light what is under it, otherwise it looks pasted on.
        paint.style = Paint.Style.FILL
        paint.color = base
        val shadowColor =
            if (active) Sculpt.withAlpha(accent, 0.40f) else Sculpt.withAlpha(Color.BLACK, 0.68f)
        paint.setShadowLayer(dp(if (active) 24 else 16).toFloat(), 0f, dp(7).toFloat(), shadowColor)
        canvas.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()

        // 1. body
        paint.shader = LinearGradient(
            cx - r, cy - r, cx + r * 0.6f, cy + r,
            intArrayOf(Sculpt.lighten(base, 0.12f), base, Sculpt.darken(base, 0.18f)),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)

        // 2. wide specular — the light source, from above and outside.
        paint.shader = RadialGradient(
            cx, cy - r * 1.15f, r * 1.9f,
            intArrayOf(
                Sculpt.withAlpha(Color.WHITE, 0.13f),
                Sculpt.withAlpha(Color.WHITE, 0.035f),
                Sculpt.withAlpha(Color.WHITE, 0f),
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)

        // 3. tight hotspot — the curvature cue.
        paint.shader = RadialGradient(
            cx - r * 0.40f, cy - r * 0.46f, r * 0.62f,
            intArrayOf(Sculpt.withAlpha(Color.WHITE, 0.11f), Sculpt.withAlpha(Color.WHITE, 0f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Sheen band crossing the glass, active only. Clipped to the circle.
        if (active) {
            val save = canvas.save()
            corePath.reset()
            corePath.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.clipPath(corePath)
            val travel = -1.4f + 2.8f * ((loopFraction * 0.6f) % 1f)
            val bandX = cx + travel * r
            paint.shader = LinearGradient(
                bandX - r * 0.30f, cy - r, bandX + r * 0.30f, cy + r,
                intArrayOf(
                    Sculpt.withAlpha(Color.WHITE, 0f),
                    Sculpt.withAlpha(Color.WHITE, 0.08f),
                    Sculpt.withAlpha(Color.WHITE, 0f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            canvas.restoreToCount(save)
        }

        // 4. occlusion, inside the glass along the bottom.
        paint.shader = RadialGradient(
            cx, cy + r * 0.66f, r,
            intArrayOf(Sculpt.withAlpha(Color.BLACK, 0.32f), Sculpt.withAlpha(Color.BLACK, 0f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // 5. bevel: brighter at the top, fading around.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            intArrayOf(
                Sculpt.withAlpha(Color.WHITE, 0.26f),
                Sculpt.withAlpha(Color.WHITE, 0.07f),
                Sculpt.withAlpha(Color.WHITE, 0.03f),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // 6. rim light: an accent ring just inside the edge when lit.
        if (active) {
            paint.strokeWidth = 1.2f * density
            paint.color = Sculpt.withAlpha(accent, 0.38f)
            canvas.drawCircle(cx, cy, r - dp(1), paint)
        }
        if (isFocused) {
            paint.strokeWidth = 2f * density
            paint.color = accent
            canvas.drawCircle(cx, cy, r + dp(6), paint)
        }
    }

    private fun drawContents(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        accent: Int,
        active: Boolean,
        geo: Float,
    ) {
        if (active && timerText.isNotEmpty()) {
            textPaint.typeface = monoTypeface
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = Orbit.Type.DISPLAY * density * geo
            textPaint.color = Sculpt.lighten(accent, 0.55f)
            textPaint.setShadowLayer(dp(14) * geo, 0f, 0f, Sculpt.withAlpha(accent, 0.5f))
            canvas.drawText(timerText, cx, cy + 7f * density * geo, textPaint)
            textPaint.clearShadowLayer()

            textPaint.typeface = labelTypeface
            textPaint.textSize = 9f * density * geo
            textPaint.letterSpacing = Orbit.Type.TRACK_OVERLINE
            textPaint.color = Sculpt.withAlpha(palette.faint, 0.95f)
            canvas.drawText(
                if (state == State.DEGRADED) "NO TRAFFIC" else "SESSION",
                cx, cy + 28f * density * geo, textPaint,
            )
            textPaint.letterSpacing = 0f
            return
        }

        drawStateGlyph(canvas, cx, cy, accent, geo)

        textPaint.typeface = labelTypeface
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = Orbit.Type.CAPTION * density * geo
        textPaint.letterSpacing = Orbit.Type.TRACK_OVERLINE
        textPaint.color = when (state) {
            State.CONNECTING -> accent
            State.FAILED -> accent
            else -> Sculpt.withAlpha(palette.muted, 0.95f)
        }
        val cta = when (state) {
            State.CONNECTING -> "CONNECTING"
            State.FAILED -> "TAP TO RETRY"
            else -> "TAP TO CONNECT"
        }
        canvas.drawText(cta, cx, cy + dp(28) * geo, textPaint)
        textPaint.letterSpacing = 0f
    }

    /**
     * The glyph inside the core when there is no timer to show.
     *
     * A padlock, not a shield with a tick in it. The old idle glyph was a shield
     * containing a checkmark drawn under the words "Not connected" — a
     * reassurance graphic on a screen whose entire message is that nothing is
     * protected yet. The shackle is open when down, closed while connecting,
     * and FAILED gets its own mark instead of borrowing the idle one.
     */
    private fun drawStateGlyph(canvas: Canvas, cx: Float, cy: Float, accent: Int, geo: Float) {
        val bodyW = dp(26) * geo
        val bodyH = dp(20) * geo
        val bodyTop = cy - dp(6) * geo
        val radius = dp(4) * geo
        val color = when (state) {
            State.CONNECTING, State.FAILED -> accent
            else -> Sculpt.withAlpha(palette.muted, 0.92f)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color

        if (state == State.FAILED) {
            // A broken link: two arcs pulling apart, plus the gap between them.
            // Unmistakably "not joined", and it needs no colour to be read.
            val armR = dp(11) * geo
            val gap = dp(4) * geo
            bounds.set(cx - armR - gap, cy - armR, cx + armR - gap, cy + armR)
            canvas.drawArc(bounds, 40f, 280f, false, paint)
            bounds.set(cx - armR + gap, cy - armR, cx + armR + gap, cy + armR)
            canvas.drawArc(bounds, 220f, 280f, false, paint)
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeCap = Paint.Cap.BUTT
            return
        }

        // Shackle. Open (offset and short on one side) when disconnected.
        val open = state == State.DISCONNECTED
        val shackleR = bodyW * 0.32f
        val shackleCx = if (open) cx + shackleR * 0.55f else cx
        val shackleCy = bodyTop - shackleR * 0.15f
        glyphPath.reset()
        bounds.set(
            shackleCx - shackleR, shackleCy - shackleR,
            shackleCx + shackleR, shackleCy + shackleR,
        )
        glyphPath.addArc(bounds, 180f, 180f)
        glyphPath.moveTo(shackleCx - shackleR, shackleCy)
        glyphPath.lineTo(shackleCx - shackleR, bodyTop)
        glyphPath.moveTo(shackleCx + shackleR, shackleCy)
        // The open shackle stops short of the body; the closed one meets it.
        glyphPath.lineTo(shackleCx + shackleR, if (open) bodyTop - shackleR * 0.5f else bodyTop)
        canvas.drawPath(glyphPath, paint)

        // Body.
        bounds.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        canvas.drawRoundRect(bounds, radius, radius, paint)

        // Keyhole. A dot plus a short stem — it is what makes 26dp of rounded
        // rectangle read as a lock rather than a button.
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, bodyTop + bodyH * 0.38f, 1.9f * density * geo, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.9f * density
        canvas.drawLine(
            cx, bodyTop + bodyH * 0.44f,
            cx, bodyTop + bodyH * 0.68f,
            paint,
        )
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            animate().scaleX(0.965f).scaleY(0.965f)
                .setDuration(Orbit.Motion.INSTANT)
                .setInterpolator(Orbit.Motion.STANDARD)
                .start()
            true
        }
        MotionEvent.ACTION_UP -> {
            animate().scaleX(1f).scaleY(1f)
                .setDuration(Orbit.Motion.BASE)
                .setInterpolator(Orbit.Motion.SPRINGY)
                .start()
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            performClick()
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            animate().scaleX(1f).scaleY(1f)
                .setDuration(Orbit.Motion.BASE)
                .setInterpolator(Orbit.Motion.STANDARD)
                .start()
            true
        }
        else -> super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A view can be detached mid-connection (screen off, returning from
        // Recents) and reattached still CONNECTED. Without this the aura and
        // sheen stay frozen.
        if (state == State.CONNECTING || state == State.CONNECTED || state == State.DEGRADED) startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        gaugeAnimator?.cancel()
        gaugeAnimator = null
        accentAnimator?.cancel()
        accentAnimator = null
        super.onDetachedFromWindow()
    }

    /**
     * The one ambient loop.
     *
     * Restarted rather than reused on a state change because CONNECTING runs
     * roughly four times faster than the resting breath, and retargeting a
     * running INFINITE animator's duration is not reliable across API levels.
     */
    private fun startLoop() {
        val wanted = if (state == State.CONNECTING) CONNECTING_LOOP_MS else Orbit.Motion.AMBIENT
        loopAnimator?.let { if (it.duration == wanted) return else it.cancel() }
        loopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = wanted
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener {
                loopFraction = it.animatedFraction
                // A triangle wave, so the breath has no visible seam at the loop
                // boundary the way a sawtooth does.
                pulse = if (loopFraction < 0.5f) loopFraction * 2f else (1f - loopFraction) * 2f
                invalidate()
            }
            start()
        }
    }

    private fun stopLoop() {
        loopAnimator?.cancel()
        loopAnimator = null
        loopFraction = 0f
        pulse = 0f
    }

    private fun animateGaugeReveal() {
        gaugeAnimator?.cancel()
        gaugeAnimator = ValueAnimator.ofFloat(gaugeReveal, 1f).apply {
            duration = GAUGE_REVEAL_MS
            interpolator = Orbit.Motion.EMPHASIZED
            addUpdateListener {
                gaugeReveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Crossfade the painted accent.
     *
     * Without this the dial changed colour in a single frame, which reads as a
     * glitch rather than a transition — there is no motion for the eye to
     * attribute the change to, so the change registers as "the screen redrew"
     * instead of "the tunnel came up".
     */
    private fun animateAccentTo(target: Int) {
        accentAnimator?.cancel()
        val from = liveAccent
        if (from == target) return
        accentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ACCENT_FADE_MS
            interpolator = Orbit.Motion.STANDARD
            addUpdateListener {
                liveAccent = Sculpt.mix(from, target, it.animatedFraction)
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    companion object {
        /**
         * Gauge resolution. 72 divides by 6, 8, 9 and 12, so majors can be
         * regrouped without leaving an orphan tick at the top.
         */
        const val TICK_COUNT = 72

        /** Every Nth tick is drawn long and bright — the landmark the eye counts. */
        const val TICK_MAJOR_EVERY = 6

        /** Ticks lit when connected. Deliberately not all of them: a full ring
         *  would have no head, and a gauge with no head shows no direction. */
        const val TICK_LIT = 53

        /** How far a ripple grows past the ring. */
        const val RIPPLE_GROWTH = 0.32f

        /** Phase offsets of the ripple rings, as fractions of one loop. */
        val RIPPLE_PHASES = listOf(0f, 0.5f)

        /**
         * Ring radius in dp.
         *
         * 112dp is the largest ring that lets the whole console column fit a
         * 1080x2400 viewport without scrolling while keeping the dial the
         * dominant element on the screen. Raising it brings back the scroll.
         */
        const val RING_DP = 112

        /** How far past the ring the aura's outer edge sits, at rest. */
        const val HALO_OUTSET_DP = 24

        /** Extra reach the aura gains at the top of its breath. */
        const val HALO_PULSE_DP = 7

        /**
         * Slack so a feathered edge or a stroke's outer half never lands on the
         * last row of pixels.
         */
        const val BLEED_MARGIN_DP = 4

        /**
         * Extra radius the view is measured with, beyond the ring, so the layers
         * that deliberately paint outside the ring have canvas to land on.
         *
         * DERIVED, not hand-tuned: computed from the two things that actually
         * paint outside the ring — a ripple reaching ring * RIPPLE_GROWTH, and
         * the aura reaching HALO_OUTSET + HALO_PULSE — so changing RING_DP alone
         * can never crop the dial.
         */
        val BLEED_DP: Int = ceil(
            maxOf(RING_DP * RIPPLE_GROWTH, (HALO_OUTSET_DP + HALO_PULSE_DP).toFloat())
        ).toInt() + BLEED_MARGIN_DP

        /**
         * Floor for [sizeScale]. Below this the dial stops reading as the primary
         * control, so a screen too short even for the shrunk dial is allowed to
         * scroll instead — scrolling is recoverable, an unreachable connect
         * button is not.
         */
        const val MIN_SIZE_SCALE = 0.78f

        /** Core radius as a fraction of the ring. */
        const val CORE_RATIO = 0.744f

        /** One turn of the arc while connecting. */
        const val CONNECTING_LOOP_MS = 1_150L

        /** How long the gauge takes to fill after the tunnel is verified. */
        const val GAUGE_REVEAL_MS = 950L

        /** Accent crossfade on a state change. */
        const val ACCENT_FADE_MS = 420L
    }
}
