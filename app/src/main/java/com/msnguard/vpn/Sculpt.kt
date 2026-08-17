package com.msnguard.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared drawing helpers for the Orbit visual language.
 *
 * A "sculpted" control is not one colour and it is not a gradient. It is a
 * stack of six layers, and dropping any one of them is what makes hand-drawn
 * glass look like a flat rounded rectangle with a border:
 *
 *   1. body gradient — the material itself
 *   2. a WIDE soft specular across the upper half (the light source)
 *   3. a TIGHT hotspot near the top-left corner (the curvature of the edge)
 *   4. ambient occlusion at the bottom (where the surface meets the page)
 *   5. a bevel that is bright on the top edge and fades around the sides
 *   6. an optional outer bloom, for a surface that is lit rather than merely
 *      present
 *
 * The previous implementation had 1, a version of 2, and 5. That is why the
 * shipped controls looked flat next to their own design preview: without the
 * corner hotspot there is no curvature cue, and without occlusion there is no
 * contact cue, so the brain has nothing to build a 3D reading from.
 *
 * Pressing swaps the vertical lighting and moves the occlusion to the top edge,
 * so a press genuinely sinks. Everything is drawn with shaders on one Paint; no
 * bitmaps, no blurs, no extra APK weight.
 */
object Sculpt {

    /** Alpha-blend [overlay] onto [base]. Used to fake translucency on opaque views. */
    fun blend(base: Int, overlay: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val r = ((Color.red(base) * (1 - a)) + (Color.red(overlay) * a)).roundToInt()
        val g = ((Color.green(base) * (1 - a)) + (Color.green(overlay) * a)).roundToInt()
        val b = ((Color.blue(base) * (1 - a)) + (Color.blue(overlay) * a)).roundToInt()
        return Color.rgb(r, g, b)
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /** Lift a colour towards white — the highlight edge of a bevel. */
    fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    /** Push a colour towards black — the shadow edge of a bevel. */
    fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

    /** Linear interpolation between two colours, alpha included. */
    fun mix(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(
            channel(Color.alpha(from), Color.alpha(to)),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }

    /**
     * The standard MSN-GUARD raised glass surface. [radius] is in dp.
     *
     * [accent] is a lit outline used for active states and wins over [stroke].
     * [pressed] forces the recessed lighting for callers that manage their own
     * state; everyone else gets a state list, so any clickable view using this
     * background genuinely sinks on touch instead of only scaling.
     */
    fun sculptedBackground(
        density: Float,
        fill: Int,
        radius: Int,
        accent: Int? = null,
        stroke: Int? = null,
        strokeWidth: Int = 1,
        pressed: Boolean = false,
    ): Drawable {
        val outline = accent ?: stroke ?: withAlpha(Color.WHITE, 0.10f)
        fun layer(down: Boolean) = GlassDrawable(
            density = density,
            fill = fill,
            radiusDp = radius.toFloat(),
            stroke = outline,
            strokeWidthDp = strokeWidth * 1.1f,
            pressed = down,
            glow = accent,
        )
        if (pressed) return layer(true)
        // StateListDrawable, not a bare GlassDrawable: this is what gives every
        // button in the app the "press = sink inwards" behaviour for free. Views
        // that are not clickable simply never enter state_pressed and always
        // render the raised layer.
        return StateListDrawable().apply {
            setEnterFadeDuration(0)
            setExitFadeDuration(Orbit.Motion.FAST.toInt())
            addState(intArrayOf(android.R.attr.state_pressed), layer(true))
            addState(intArrayOf(), layer(false))
        }
    }

    /** A recessed well: the inverse lighting, used for the transport rail track. */
    fun recessedBackground(
        density: Float,
        fill: Int,
        radius: Int,
        accent: Int? = null,
    ): Drawable = GlassDrawable(
        density = density,
        fill = fill,
        radiusDp = radius.toFloat(),
        stroke = accent ?: withAlpha(Color.WHITE, 0.07f),
        strokeWidthDp = 1.1f,
        pressed = true,
    )

    /**
     * Wrap a sculpted surface in a ripple so touch feedback survives.
     *
     * selectableItemBackground draws nothing over a custom drawable on some OEM
     * skins, so the ripple is explicit and always has a mask.
     */
    fun sculptedRipple(
        density: Float,
        fill: Int,
        radius: Int,
        rippleColor: Int,
        accent: Int? = null,
    ): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(withAlpha(rippleColor, 0.18f)),
            sculptedBackground(density, fill, radius, accent),
            mask,
        )
    }

    /**
     * A small filled dot with its own bloom — status LEDs, tile accents, the
     * head of a sparkline.
     *
     * Pulled out of three separate copies that had drifted to three different
     * glow radii.
     */
    fun dot(density: Float, color: Int, lit: Boolean): Drawable =
        GlassDrawable(
            density = density,
            fill = if (lit) color else withAlpha(color, 0.35f),
            radiusDp = Orbit.Radius.PILL.toFloat(),
            stroke = if (lit) lighten(color, 0.45f) else withAlpha(color, 0.30f),
            strokeWidthDp = 1f,
            pressed = false,
            glow = if (lit) color else null,
        )
}

/**
 * The six-layer glass surface, drawn by hand.
 *
 * [pressed] swaps the vertical lighting and moves the occlusion band to the top,
 * which is what makes a press read as "sunk in" rather than "faded".
 */
class GlassDrawable(
    private val density: Float,
    private val fill: Int,
    private val radiusDp: Float,
    private val stroke: Int,
    private val strokeWidthDp: Float = 1.1f,
    private val pressed: Boolean = false,
    private val glow: Int? = null,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val strokeWidth = (strokeWidthDp * density).coerceAtLeast(1f)
        val inset = strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        // A pill radius (999dp) has to clamp to half the shorter side or
        // drawRoundRect produces a lens shape on short views.
        val radius = (radiusDp * density).coerceAtMost(minOf(rect.width(), rect.height()) / 2f)

        // ---- 1. body ----------------------------------------------------
        // Three stops, not two. A two-stop gradient on a tall surface reads as
        // a flat wash because the midpoint sits exactly where the eye expects
        // the material's own colour to be.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            if (pressed) {
                intArrayOf(Sculpt.darken(fill, 0.26f), Sculpt.darken(fill, 0.08f), fill)
            } else {
                intArrayOf(Sculpt.lighten(fill, 0.10f), fill, Sculpt.darken(fill, 0.10f))
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (!pressed) {
            // ---- 2. wide specular across the upper half -----------------
            // The light source. Centred above the surface and very wide, so it
            // does not read as a blob sitting on the glass.
            paint.shader = RadialGradient(
                rect.centerX(),
                rect.top - rect.height() * 0.35f,
                maxOf(rect.width(), rect.height()) * 1.15f,
                intArrayOf(
                    Sculpt.withAlpha(Color.WHITE, 0.11f),
                    Sculpt.withAlpha(Color.WHITE, 0.03f),
                    Sculpt.withAlpha(Color.WHITE, 0f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)

            // ---- 3. tight corner hotspot -------------------------------
            // This is the curvature cue, and it is the layer that was missing.
            // Small, bright, near the top-left corner: it tells the eye the
            // edge rolls over rather than being cut.
            paint.shader = RadialGradient(
                rect.left + rect.width() * 0.16f,
                rect.top + rect.height() * 0.10f,
                maxOf(rect.width(), rect.height()) * 0.42f,
                intArrayOf(
                    Sculpt.withAlpha(Color.WHITE, 0.09f),
                    Sculpt.withAlpha(Color.WHITE, 0f),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        // ---- 4. ambient occlusion --------------------------------------
        // Bottom when raised (contact shadow), top when pressed (the surface is
        // now below its own frame, so the frame shades it).
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            if (pressed) {
                intArrayOf(Sculpt.withAlpha(Color.BLACK, 0.50f), Sculpt.withAlpha(Color.BLACK, 0f))
            } else {
                intArrayOf(Sculpt.withAlpha(Color.BLACK, 0f), Sculpt.withAlpha(Color.BLACK, 0.32f))
            },
            if (pressed) floatArrayOf(0f, 0.42f) else floatArrayOf(0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        // ---- 5. bevel ---------------------------------------------------
        // Bright on the top edge, fading to nothing by the bottom. Drawn before
        // the outline so an accent ring sits on top of it rather than under.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(
                Sculpt.withAlpha(Color.WHITE, if (pressed) 0.04f else 0.24f),
                Sculpt.withAlpha(Color.WHITE, if (pressed) 0.02f else 0.07f),
                Sculpt.withAlpha(Color.WHITE, 0.02f),
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        // outline / lit accent ring
        paint.color = stroke
        canvas.drawRoundRect(rect, radius, radius, paint)

        // ---- 6. outer bloom, for a lit surface --------------------------
        // Two passes at widening stroke and falling alpha. A single wide stroke
        // reads as a second border; two graded ones read as light leaving the
        // edge, which is the whole point.
        glow?.let { color ->
            if (Color.alpha(color) < 40) return@let
            paint.strokeWidth = strokeWidth * 2.2f
            paint.color = Sculpt.withAlpha(color, 0.18f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.strokeWidth = strokeWidth * 4.4f
            paint.color = Sculpt.withAlpha(color, 0.07f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getPadding(padding: Rect): Boolean = false
}

/**
 * Sparkline floor for a metric tile.
 *
 * Three properties, each of which fixes something the flat version got wrong:
 *
 *  * bars EASE toward a new sample instead of snapping to it. Samples arrive
 *    about once a second, and a one-frame jump at that cadence reads as a
 *    rendering glitch rather than a measurement.
 *  * colour walks across the row between two accents, so a tile reads as one
 *    gradient object instead of eleven identical sticks.
 *  * amplitude drives brightness as well as height, so a quiet tile is dim and
 *    a busy one glows. Height alone is a weak signal at 16dp tall.
 *
 * The easing ticker is self-limiting: it starts on a push, and stops the frame
 * every bar is within half a percent of its target or the view detaches. An
 * idle tile costs nothing.
 */
class MicroBarsView(
    context: Context,
    private var barColor: Int,
    private var barColorAlt: Int = barColor,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val bar = RectF()

    /** Where each bar is heading. */
    private val target = ArrayDeque<Float>()

    /** Where each bar currently is. Same size as [target], always. */
    private val shown = ArrayDeque<Float>()

    private var easing = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!easing) return
            if (stepTowardTarget()) {
                postDelayed(this, Orbit.Motion.TICK_MS)
            } else {
                easing = false
            }
            invalidate()
        }
    }

    fun setColors(primary: Int, secondary: Int = primary) {
        barColor = primary
        barColorAlt = secondary
        invalidate()
    }

    fun push(value: Float) {
        val clean = value.coerceAtLeast(0f)
        target.addLast(clean)
        // A new bar grows in from the floor rather than appearing at full height.
        shown.addLast(0f)
        while (target.size > MAX_BARS) target.removeFirst()
        while (shown.size > MAX_BARS) shown.removeFirst()
        startEasing()
    }

    fun seed() {
        if (target.isNotEmpty()) return
        repeat(MAX_BARS) {
            target.addLast(0f)
            shown.addLast(0f)
        }
        invalidate()
    }

    fun reset() {
        target.clear()
        shown.clear()
        seed()
    }

    private fun startEasing() {
        if (easing) return
        easing = true
        if (isAttachedToWindow) post(ticker)
    }

    /** Returns true while any bar is still visibly away from its target. */
    private fun stepTowardTarget(): Boolean {
        var moving = false
        val targets = target.toList()
        val currents = shown.toList()
        shown.clear()
        for (i in currents.indices) {
            val to = targets.getOrElse(i) { 0f }
            val from = currents[i]
            val next = from + (to - from) * EASE
            // Snap once the remaining distance is imperceptible, otherwise the
            // ticker would run forever chasing an asymptote.
            val settled = abs(to - next) <= abs(to) * 0.005f + 0.0005f
            shown.addLast(if (settled) to else next)
            if (!settled) moving = true
        }
        return moving
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (easing) post(ticker)
    }

    override fun onDetachedFromWindow() {
        easing = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shown.isEmpty() || width <= 0 || height <= 0) return
        val peak = maxOf(target.maxOrNull() ?: 0f, shown.maxOrNull() ?: 0f)
        val gap = 2f * density
        val slot = (width - gap * (MAX_BARS - 1)) / MAX_BARS
        if (slot <= 0f) return
        val radius = slot / 2f
        shown.forEachIndexed { index, value ->
            // A zero peak means "no traffic yet": draw a floor stub, never NaN.
            val ratio = if (peak <= 0f) 0.08f else (0.08f + 0.92f * (value / peak))
            val barHeight = (height * ratio).coerceAtLeast(radius * 2f)
            val left = index * (slot + gap)
            // Colour walks across the row, and quiet bars stay dim.
            val hue = Sculpt.mix(barColor, barColorAlt, index / (MAX_BARS - 1f))
            paint.shader = LinearGradient(
                0f, height - barHeight, 0f, height.toFloat(),
                Sculpt.withAlpha(hue, 0.30f + 0.65f * ratio),
                Sculpt.withAlpha(hue, 0.05f),
                Shader.TileMode.CLAMP,
            )
            bar.set(left, height - barHeight, left + slot, height.toFloat())
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
        paint.shader = null
    }

    private companion object {
        const val MAX_BARS = 11

        /** Per-frame approach fraction. 0.28 at 20fps settles in ~250ms. */
        const val EASE = 0.28f
    }
}

/**
 * The live trace next to the exit-node IP.
 *
 * Midpoint-smoothed rather than a straight polyline: at 14 points across 52dp
 * a polyline is mostly corners, and corners read as a bar chart that forgot to
 * fill. The head of the trace also gets a lit dot, which is what tells the eye
 * which end is "now".
 */
class SparkLineView(
    context: Context,
    private var lineColor: Int,
) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val fillPath = Path()
    private val samples = ArrayDeque<Float>()

    fun setColor(color: Int) {
        lineColor = color
        invalidate()
    }

    fun push(value: Float) {
        samples.addLast(value.coerceIn(0f, 1f))
        while (samples.size > MAX_POINTS) samples.removeFirst()
        invalidate()
    }

    /** A gentle resting wave so the card never shows an empty box. */
    fun seed() {
        samples.clear()
        repeat(MAX_POINTS) { index ->
            samples.addLast((0.36f + 0.18f * sin(index * 0.85f)).coerceIn(0f, 1f))
        }
        invalidate()
    }

    fun reset() = seed()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2 || width <= 0 || height <= 0) return
        val inset = 2.5f * density
        val usableH = height - inset * 2
        val points = samples.toList()
        val step = width.toFloat() / (points.size - 1)

        fun xOf(i: Int) = i * step
        fun yOf(i: Int) = inset + usableH * (1f - points[i])

        // Quadratic through midpoints: cheap, always smooth, never overshoots
        // the data the way a naive cubic through the samples themselves does.
        path.reset()
        path.moveTo(xOf(0), yOf(0))
        for (i in 1 until points.size) {
            val midX = (xOf(i - 1) + xOf(i)) / 2f
            val midY = (yOf(i - 1) + yOf(i)) / 2f
            path.quadTo(xOf(i - 1), yOf(i - 1), midX, midY)
        }
        path.lineTo(xOf(points.size - 1), yOf(points.size - 1))

        fillPath.set(path)
        fillPath.lineTo(width.toFloat(), height.toFloat())
        fillPath.lineTo(0f, height.toFloat())
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            Sculpt.withAlpha(lineColor, 0.24f), Sculpt.withAlpha(lineColor, 0f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        stroke.strokeWidth = 1.6f * density
        stroke.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            Sculpt.withAlpha(lineColor, 0.30f), lineColor,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, stroke)
        stroke.shader = null

        // The head. Two circles: a faint bloom and a solid core.
        val hx = xOf(points.size - 1)
        val hy = yOf(points.size - 1)
        dotPaint.color = Sculpt.withAlpha(lineColor, 0.22f)
        canvas.drawCircle(hx, hy, 3.4f * density, dotPaint)
        dotPaint.color = lineColor
        canvas.drawCircle(hx, hy, 1.6f * density, dotPaint)
    }

    private companion object {
        const val MAX_POINTS = 14
    }
}

/**
 * The strip that closes the home screen under LOG / SPLIT / SCAN.
 *
 * That area used to be dead space. It now carries a horizon trace in the accent
 * colour plus the build signature: two summed harmonics, an envelope that
 * fades both ends into the background, and a travelling highlight so the line
 * has a direction of travel rather than just wobbling in place.
 *
 * Deliberately cheap, because it is decoration on a screen that stays open next
 * to a live tunnel: one path, ~20fps, animating ONLY while attached AND lit.
 * When it is unlit it draws a single flat hairline and posts nothing.
 */
class OrbitFooterWave(
    context: Context,
    private val palette: AppAppearance.Palette,
    private val caption: String,
) : View(context) {

    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Orbit.Type.MEDIUM
        letterSpacing = Orbit.Type.TRACK_OVERLINE
    }
    private val density = resources.displayMetrics.density
    private val path = Path()
    private var lit = false
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, Orbit.Motion.TICK_MS)
        }
    }

    fun setLit(value: Boolean) {
        if (lit == value) return
        lit = value
        syncTicker()
        invalidate()
    }

    private fun syncTicker() {
        val shouldRun = lit && isAttachedToWindow
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncTicker()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val accent = if (lit) palette.connected else palette.faint
        val midY = height * 0.40f

        if (!lit) {
            // Idle: a flat hairline that fades out at both ends. No animation,
            // no path building, no posted work.
            wave.strokeWidth = 1f * density
            wave.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(
                    Sculpt.withAlpha(accent, 0f),
                    Sculpt.withAlpha(accent, 0.28f),
                    Sculpt.withAlpha(accent, 0f),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawLine(0f, midY, width.toFloat(), midY, wave)
            wave.shader = null
            drawCaption(canvas, lit = false)
            return
        }

        val phase = (SystemClock.uptimeMillis() % Orbit.Motion.AMBIENT) /
            Orbit.Motion.AMBIENT.toFloat() * (2f * Math.PI.toFloat())
        val amplitude = 6f * density
        path.reset()
        for (i in 0..POINTS) {
            val t = i / POINTS.toFloat()
            val x = width * t
            // Two summed sines: one long swell, one short ripple. The envelope
            // fades both ends so the trace melts into the background instead of
            // stopping at a hard edge.
            val envelope = sin(t * Math.PI.toFloat())
            val y = midY + amplitude * envelope *
                (sin(t * 6.2f + phase) * 0.7f + sin(t * 13f - phase * 1.6f) * 0.3f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Base pass: the whole trace, dim.
        wave.strokeWidth = 1.5f * density
        wave.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Sculpt.withAlpha(accent, 0f),
                Sculpt.withAlpha(accent, 0.55f),
                Sculpt.withAlpha(accent, 0f),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, wave)

        // Highlight pass: a bright window sliding left to right along the same
        // path, which is what gives the wave a direction.
        val head = (SystemClock.uptimeMillis() % (Orbit.Motion.AMBIENT * 2)) /
            (Orbit.Motion.AMBIENT * 2).toFloat()
        val windowW = 0.22f
        wave.strokeWidth = 2f * density
        wave.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Sculpt.withAlpha(accent, 0f),
                Sculpt.withAlpha(accent, 0.95f),
                Sculpt.withAlpha(accent, 0f),
            ),
            floatArrayOf(
                (head - windowW).coerceIn(0f, 1f),
                head.coerceIn(0f, 1f),
                (head + windowW).coerceIn(0f, 1f),
            ),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, wave)
        wave.shader = null

        drawCaption(canvas, lit = true)
    }

    private fun drawCaption(canvas: Canvas, lit: Boolean) {
        text.textSize = Orbit.Type.OVERLINE * density
        text.color = Sculpt.withAlpha(
            if (lit) palette.muted else palette.faint,
            if (lit) 0.95f else 0.65f,
        )
        canvas.drawText(caption, width / 2f, height * 0.94f, text)
    }

    private companion object {
        const val POINTS = 48
    }
}
