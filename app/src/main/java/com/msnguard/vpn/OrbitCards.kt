package com.msnguard.vpn

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Exit-node card: flag well, address readout, location line, live trace.
 *
 * Structure is deliberately three columns of fixed intent — identity marker,
 * identity text, activity — because the address is the one value on this screen
 * whose length is not under our control. A 39-character IPv6 literal must not be
 * able to push the card taller or shove the transport rail off the screen, so
 * the text column is weighted and the other two are fixed. Shortening is
 * delegated to [IpFormatter]; this view only picks a font step.
 *
 * Three things this pass added, each closing a hole:
 *
 *  * a live status dot on the flag well, so the card states whether it is
 *    describing a tunnel or the bare carrier without the user parsing the
 *    caption underneath;
 *  * a pulse on the measuring state, because a motionless "measuring..." is
 *    indistinguishable from a hang, and this state can legitimately last several
 *    seconds while the core reports from inside the tunnel;
 *  * long-press to copy. This is the only screen in the app showing a value a
 *    user plausibly wants to paste elsewhere, and there was no way to get it
 *    out.
 */
class ExitNodeCard(
    context: Context,
    private val palette: AppAppearance.Palette,
    onClick: () -> Unit,
) : LinearLayout(context) {

    private val flagWell: FrameLayout
    private val flagView: TextView
    private val statusDot: View
    private val keyView: TextView
    private val ipView: TextView
    private val locView: TextView
    private val spark: SparkLineView

    /** The unshortened address, for accessibility and for the clipboard. */
    private var fullAddress: String = ""
    private var measurePulse: ValueAnimator? = null

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun text(
        value: String,
        size: Float,
        color: Int,
        medium: Boolean = false,
        mono: Boolean = false,
        spacing: Float = 0f,
    ): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        letterSpacing = spacing
        typeface = when {
            mono -> Orbit.Type.MONO
            medium -> Orbit.Type.MEDIUM
            else -> Orbit.Type.REGULAR
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val fill = Sculpt.blend(palette.surface, palette.ink, 0.03f)
        background = Sculpt.sculptedRipple(
            resources.displayMetrics.density, fill, Orbit.Radius.CARD, palette.primary,
            accent = Sculpt.withAlpha(palette.ink, 0.08f),
        )
        setPadding(px(12), px(11), px(14), px(11))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
        setOnLongClickListener { copyAddress() }

        // The flag lives in a recessed well with a status dot pinned to its
        // corner. The well matters on devices with no emoji font: without it the
        // missing glyph leaves a hole in the layout instead of an empty slot.
        flagView = text(GLOBE, 19f, palette.ink).apply { gravity = Gravity.CENTER }
        statusDot = View(context).apply {
            background = Sculpt.dot(resources.displayMetrics.density, palette.faint, lit = false)
        }
        flagWell = FrameLayout(context).apply {
            background = Sculpt.recessedBackground(
                resources.displayMetrics.density,
                Sculpt.darken(palette.surface, 0.16f),
                Orbit.Radius.WELL,
                Sculpt.withAlpha(palette.ink, 0.09f),
            )
            addView(flagView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ))
            addView(statusDot, FrameLayout.LayoutParams(
                px(7), px(7), Gravity.BOTTOM or Gravity.END,
            ).apply { rightMargin = px(3); bottomMargin = px(3) })
        }
        addView(flagWell, LayoutParams(px(44), px(44)))

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        keyView = text(
            "EXIT NODE",
            Orbit.Type.OVERLINE,
            Sculpt.withAlpha(palette.faint, 0.95f),
            medium = true,
            spacing = Orbit.Type.TRACK_OVERLINE * 0.85f,
        )
        ipView = text("not tunnelled", Orbit.Type.BODY, palette.ink, medium = true, mono = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        locView = text("tap to refresh", 10.5f, Sculpt.withAlpha(palette.faint, 0.9f))
        column.addView(keyView)
        column.addView(ipView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(2) })
        column.addView(locView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px(1) })
        addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = px(12)
        })

        spark = SparkLineView(context, palette.mint).apply { seed() }
        addView(spark, LayoutParams(px(54), px(26)))
    }

    /**
     * @param address raw address as reported by the trace endpoint; may be v4 or v6
     * @param countryCode two-letter code, or null/blank when unknown
     * @param tunnelled true when the tunnel is up, which changes the caption
     * @param measuring true while the core is still reading the exit from inside
     *   the tunnel — a state that must never be presented as an error
     */
    fun render(
        address: String,
        countryCode: String?,
        tunnelled: Boolean,
        measuring: Boolean = false,
    ) {
        keyView.text = if (tunnelled) "EXIT NODE" else "YOUR IP"
        if (address.isBlank() || address == UNAVAILABLE) {
            fullAddress = ""
            // On the native path the exit address comes from the core, measured
            // inside the tunnel, and arrives a second or two after connect.
            // Saying "unavailable, tap to retry" there invites the user to retry
            // something that is simply not finished — and tapping cannot speed it
            // up, because this process has no route into the tunnel.
            if (measuring) {
                ipView.text = MEASURING
                ipView.textSize = Orbit.Type.LABEL
                locView.text = "reading from inside the tunnel"
                flagView.text = GLOBE
                setStatus(palette.primary, lit = true)
                startMeasurePulse()
                contentDescription = "Measuring the tunnel exit address"
                return
            }
            stopMeasurePulse()
            ipView.text = UNAVAILABLE
            ipView.textSize = Orbit.Type.BODY
            locView.text = "tap to retry"
            flagView.text = GLOBE
            setStatus(palette.danger, lit = false)
            contentDescription = "IP unavailable, tap to retry"
            return
        }
        stopMeasurePulse()
        val fit = IpFormatter.fit(address)
        fullAddress = fit.full
        ipView.text = fit.text
        ipView.textSize = when (fit.step) {
            IpFormatter.Step.V4 -> Orbit.Type.BODY
            IpFormatter.Step.V6 -> 12.5f
            IpFormatter.Step.V6_LONG -> 11f
        }
        flagView.text = IpFormatter.flag(countryCode)
        val country = countryCode?.trim()?.uppercase().orEmpty()
        // Country only — city was explicitly not wanted, and the trace endpoint
        // does not return one anyway.
        locView.text = when {
            country.isNotEmpty() && tunnelled -> "$country · tunnelled"
            country.isNotEmpty() -> country
            tunnelled -> "tunnelled"
            else -> "not tunnelled"
        }
        locView.setTextColor(
            if (tunnelled) Sculpt.withAlpha(palette.connected, 0.9f)
            else Sculpt.withAlpha(palette.faint, 0.9f)
        )
        setStatus(if (tunnelled) palette.connected else palette.muted, lit = tunnelled)
        spark.setColor(if (tunnelled) palette.connected else palette.mint)
        // Accessibility reads the full address; the visual is the shortened one.
        contentDescription = buildString {
            append(keyView.text).append(": ").append(fit.full)
            if (country.isNotEmpty()) append(", ").append(country)
            append(". Long press to copy.")
        }
    }

    fun pushSample(value: Float) = spark.push(value)

    fun resetSpark() = spark.reset()

    private fun setStatus(color: Int, lit: Boolean) {
        statusDot.background = Sculpt.dot(resources.displayMetrics.density, color, lit)
    }

    /**
     * Copies the full address, not the shortened one.
     *
     * A truncated IPv6 literal on the clipboard would be worse than no clipboard
     * support at all, because it looks like it worked.
     */
    private fun copyAddress(): Boolean {
        val value = fullAddress
        if (value.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("IP address", value))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * A slow alpha breath on the address while it is being measured.
     *
     * Not a spinner: a spinner claims a determinate amount of work is underway,
     * and this wait is "whenever the core next reports". A breath says "still
     * live" without claiming progress.
     */
    private fun startMeasurePulse() {
        if (measurePulse?.isRunning == true) return
        measurePulse = ValueAnimator.ofFloat(0.42f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = Orbit.Motion.STANDARD
            addUpdateListener { ipView.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopMeasurePulse() {
        measurePulse?.cancel()
        measurePulse = null
        ipView.alpha = 1f
    }

    override fun onDetachedFromWindow() {
        stopMeasurePulse()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val UNAVAILABLE = "IP unavailable"
        const val MEASURING = "measuring…"
        const val GLOBE = "\uD83C\uDF10"
    }
}

/**
 * Bottom action bar: LOG / SPLIT / SCAN MODE.
 *
 * Each entry is a sculpted pill holding a recessed icon well and a caption. The
 * well is the point: a glyph floating directly on a pill reads as a picture
 * printed on a surface, while a glyph sitting in a recess reads as a component
 * of a control. Same pixels, entirely different affordance.
 *
 * Presses tint the glyph and the caption as well as sinking the surface, because
 * on a 56dp pill the surface change alone is easy to miss under a thumb.
 */
class OrbitActionBar(
    context: Context,
    private val palette: AppAppearance.Palette,
    entries: List<Entry>,
) : LinearLayout(context) {

    data class Entry(val caption: String, val glyph: Glyph, val onClick: () -> Unit)

    enum class Glyph { LOG, SPLIT, SCAN }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    init {
        orientation = HORIZONTAL
        val density = resources.displayMetrics.density
        val fill = Sculpt.blend(palette.surface, palette.ink, 0.025f)
        entries.forEachIndexed { index, entry ->
            val glyphView = GlyphView(context, entry.glyph, palette.muted)
            val caption = TextView(context).apply {
                text = entry.caption
                textSize = Orbit.Type.OVERLINE
                setTextColor(Sculpt.withAlpha(palette.muted, 0.95f))
                letterSpacing = Orbit.Type.TRACK_OVERLINE * 0.7f
                typeface = Orbit.Type.MEDIUM
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
            val well = View(context).apply {
                background = Sculpt.recessedBackground(
                    density,
                    Sculpt.darken(palette.surface, 0.22f),
                    Orbit.Radius.CHIP,
                    Sculpt.withAlpha(palette.ink, 0.07f),
                )
            }

            val cell = object : FrameLayout(context) {
                override fun setPressed(pressed: Boolean) {
                    super.setPressed(pressed)
                    background = Sculpt.sculptedBackground(
                        density,
                        if (pressed) Sculpt.darken(fill, 0.10f) else fill,
                        Orbit.Radius.TILE,
                        accent = Sculpt.withAlpha(
                            if (pressed) palette.primary else palette.ink,
                            if (pressed) 0.34f else 0.08f,
                        ),
                        pressed = pressed,
                    )
                    // Tint the contents too. The surface change alone is largely
                    // hidden under the finger that caused it.
                    glyphView.setTint(if (pressed) palette.primary else palette.muted)
                    caption.setTextColor(
                        Sculpt.withAlpha(
                            if (pressed) palette.primary else palette.muted,
                            0.95f,
                        )
                    )
                }
            }.apply {
                background = Sculpt.sculptedBackground(
                    density, fill, Orbit.Radius.TILE,
                    accent = Sculpt.withAlpha(palette.ink, 0.08f),
                )
                isClickable = true
                isFocusable = true
                contentDescription = entry.caption
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    entry.onClick()
                }
            }

            val iconStack = FrameLayout(context).apply {
                addView(well, FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER))
                addView(glyphView, FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
            }
            val stack = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(iconStack, LayoutParams(px(26), px(26)))
                addView(caption, LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = px(4) })
            }
            cell.addView(stack, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = if (index == 0) 0 else px(9)
            })
        }
    }
}

/**
 * Tiny vector glyphs drawn in code — three shapes is not worth three XML assets.
 *
 * Redrawn at 16dp with the constraint that each must be identifiable in
 * silhouette. The old SPLIT was three bare lines meeting at a point, which at
 * this size reads as an arrow; the old SCAN was two arcs and a dot, which reads
 * as an eye. Terminated branches and three graduated arcs fix both.
 */
private class GlyphView(
    context: Context,
    private val glyph: OrbitActionBar.Glyph,
    private var tint: Int,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arc = RectF()

    fun setTint(color: Int) {
        if (tint == color) return
        tint = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        paint.color = tint
        paint.strokeWidth = 1.6f * d
        paint.style = Paint.Style.STROKE
        val w = width.toFloat()
        val h = height.toFloat()
        when (glyph) {
            OrbitActionBar.Glyph.LOG -> {
                // A page with a folded corner, plus three lines of text. The
                // outline is what makes it a log rather than a hamburger menu.
                val left = w * 0.20f
                val right = w * 0.80f
                val top = h * 0.10f
                val bottom = h * 0.90f
                val fold = w * 0.20f
                val page = android.graphics.Path().apply {
                    moveTo(left, top)
                    lineTo(right - fold, top)
                    lineTo(right, top + fold)
                    lineTo(right, bottom)
                    lineTo(left, bottom)
                    close()
                }
                canvas.drawPath(page, paint)
                paint.strokeWidth = 1.3f * d
                val xs = left + w * 0.12f
                canvas.drawLine(xs, h * 0.44f, right - w * 0.12f, h * 0.44f, paint)
                canvas.drawLine(xs, h * 0.62f, right - w * 0.12f, h * 0.62f, paint)
                canvas.drawLine(xs, h * 0.78f, w * 0.55f, h * 0.78f, paint)
            }
            OrbitActionBar.Glyph.SPLIT -> {
                // A trunk that forks, with a dot terminating each end. The dots
                // are what stop it reading as an arrowhead at 16dp.
                val cx = w * 0.5f
                canvas.drawLine(cx, h * 0.88f, cx, h * 0.52f, paint)
                canvas.drawLine(cx, h * 0.52f, w * 0.22f, h * 0.24f, paint)
                canvas.drawLine(cx, h * 0.52f, w * 0.78f, h * 0.24f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, h * 0.88f, 1.5f * d, paint)
                canvas.drawCircle(w * 0.22f, h * 0.24f, 1.5f * d, paint)
                canvas.drawCircle(w * 0.78f, h * 0.24f, 1.5f * d, paint)
            }
            OrbitActionBar.Glyph.SCAN -> {
                // Radar: three arcs at falling opacity plus an emitter dot. The
                // graduation is what implies a sweep outward rather than a
                // static pair of curves.
                val cx = w * 0.5f
                val cy = h * 0.78f
                val radii = floatArrayOf(0.20f, 0.40f, 0.60f)
                val alphas = floatArrayOf(1f, 0.7f, 0.4f)
                radii.forEachIndexed { index, ratio ->
                    paint.color = Sculpt.withAlpha(tint, alphas[index])
                    val r = w * ratio
                    arc.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(arc, 210f, 120f, false, paint)
                }
                paint.color = tint
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 1.6f * d, paint)
            }
        }
        paint.style = Paint.Style.STROKE
    }
}
