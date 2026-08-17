package com.msnguard.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reusable Orbit surfaces.
 *
 * Everything here is deliberately view-based and hand built: the app ships no
 * Compose runtime and no Material components, and the APK is already mostly
 * native libraries. Adding a UI toolkit to style four widgets would cost more
 * than the four widgets.
 */

private fun Context.px(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

private fun Context.orbitLabel(
    text: String,
    size: Float,
    color: Int,
    medium: Boolean = false,
    mono: Boolean = false,
    spacing: Float = 0f,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    letterSpacing = spacing
    typeface = when {
        mono -> Orbit.Type.MONO
        medium -> Orbit.Type.MEDIUM
        else -> Orbit.Type.REGULAR
    }
}

/**
 * One at-a-glance counter with a live sparkline floor.
 *
 * Each tile owns its own accent — mint for DOWN, violet for UP, amber for SPEED.
 * That is not decoration: three counters of identical colour and identical
 * layout side by side cannot be told apart without reading all three captions,
 * which defeats the entire purpose of an at-a-glance row.
 *
 * The accent appears in four places (dot, caption, bars, bottom underglow) so
 * the tile is identifiable in peripheral vision, and the value cross-dissolves
 * on change so movement is visible without being loud.
 */
class MetricTile(
    context: Context,
    private val palette: AppAppearance.Palette,
    keyText: String,
    private val accent: Int,
    private val accentSecondary: Int = accent,
    onClick: () -> Unit,
) : LinearLayout(context) {

    private val valueView: TextView
    private val unitView: TextView
    private val captionView: TextView
    private val dotView: View
    private val bars: MicroBarsView
    private val underglow: AccentUnderglow
    private var lastValue: String = "0"

    init {
        orientation = VERTICAL
        val fill = Sculpt.blend(palette.surface, palette.ink, 0.03f)
        background = Sculpt.sculptedRipple(
            resources.displayMetrics.density, fill, Orbit.Radius.TILE, accent,
            accent = Sculpt.withAlpha(accent, 0.16f),
        )
        setPadding(context.px(13), context.px(11), context.px(13), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        // Caption row: a lit dot plus the label, both in the tile's own accent.
        val captionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotView = View(context).apply {
            background = Sculpt.dot(resources.displayMetrics.density, accent, lit = true)
        }
        captionRow.addView(dotView, LayoutParams(context.px(5), context.px(5)).apply {
            rightMargin = context.px(5)
        })
        captionView = context.orbitLabel(
            keyText,
            Orbit.Type.OVERLINE,
            Sculpt.withAlpha(accent, 0.92f),
            medium = true,
            spacing = Orbit.Type.TRACK_OVERLINE * 0.8f,
        ).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        captionRow.addView(captionView)
        addView(captionRow, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        valueView = context.orbitLabel("0", 21f, palette.ink, medium = true, mono = true)
        unitView = context.orbitLabel(
            "B",
            9f,
            Sculpt.withAlpha(palette.faint, 0.95f),
            medium = true,
            spacing = Orbit.Type.TRACK_LABEL,
        )
        row.addView(valueView)
        row.addView(unitView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = context.px(3); bottomMargin = context.px(3) })
        addView(row, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.px(2) })

        bars = MicroBarsView(context, accent, accentSecondary).apply { seed() }
        addView(bars, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.px(16),
        ).apply { topMargin = context.px(5) })

        // A 2dp accent wash below the bars. It is the cheapest way to make three
        // otherwise identical tiles separable without reading them.
        //
        // The 9dp bottom margin is also the tile's bottom padding. It used to be
        // carried by the sparkline's own margin, which is a fragile place to keep
        // a layout invariant — anything added after the sparkline lost it.
        underglow = AccentUnderglow(context, accent, accentSecondary)
        addView(underglow, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.px(3),
        ).apply { topMargin = context.px(5); bottomMargin = context.px(9) })

        contentDescription = "$keyText 0 B"
    }

    /** [value] is pre-scaled for display; [unit] is its suffix, e.g. "GB". */
    fun setValue(value: String, unit: String) {
        unitView.text = unit
        contentDescription = "${captionView.text} $value $unit"
        if (value == lastValue) return
        lastValue = value
        // Cross-dissolve with a short rise. Not a rolling odometer: at one update
        // per second a roll never finishes before the next value lands, so it
        // reads as permanent jitter. A dissolve always completes.
        valueView.animate().cancel()
        valueView.alpha = 0f
        valueView.translationY = valueView.height * 0.22f
        valueView.text = value
        valueView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Orbit.Motion.FAST)
            .setInterpolator(Orbit.Motion.STANDARD)
            .start()
    }

    fun push(sample: Float) {
        bars.push(sample)
        underglow.setIntensity(sample)
    }

    fun resetBars() {
        bars.reset()
        underglow.setIntensity(0f)
    }

    /**
     * Inactive tiles fade their ACCENTS, not their text.
     *
     * The old version dropped the whole tile to 55% alpha, which took the value
     * with it — and the value is exactly the thing that still means something
     * when the tunnel is down (it is the last session's total). Only the colour
     * cues recede.
     */
    fun dim(active: Boolean) {
        alpha = if (active) 1f else 0.82f
        val accentAlpha = if (active) 0.92f else 0.32f
        captionView.setTextColor(Sculpt.withAlpha(accent, accentAlpha))
        dotView.background = Sculpt.dot(resources.displayMetrics.density, accent, lit = active)
        valueView.setTextColor(if (active) palette.ink else palette.inkDim)
        underglow.setLit(active)
    }

    /** A soft accent wash, brighter the busier the tile is. */
    private class AccentUnderglow(
        context: Context,
        private val from: Int,
        private val to: Int,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private var intensity = 0f
        private var lit = true

        fun setIntensity(sample: Float) {
            // Compressed, not linear: raw byte-rate samples span orders of
            // magnitude, and a linear map leaves this strip either off or
            // saturated with almost nothing in between.
            val next = (sample / (sample + 1f)).coerceIn(0f, 1f)
            if (abs(next - intensity) < 0.01f) return
            intensity = next
            invalidate()
        }

        fun setLit(value: Boolean) {
            if (lit == value) return
            lit = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val strength = (if (lit) 0.22f else 0.06f) + (if (lit) 0.5f else 0f) * intensity
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(
                    Sculpt.withAlpha(from, 0f),
                    Sculpt.withAlpha(from, strength),
                    Sculpt.withAlpha(to, strength * 0.7f),
                    Sculpt.withAlpha(to, 0f),
                ),
                floatArrayOf(0f, 0.3f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.shader = null
            // A one-pixel bright core along the middle, only when busy. It is
            // what turns a wash into a signal.
            if (lit && intensity > 0.05f) {
                paint.color = Sculpt.withAlpha(Sculpt.mix(from, to, 0.5f), 0.5f * intensity)
                canvas.drawRect(
                    width * 0.08f, height / 2f - 0.5f * density,
                    width * 0.92f, height / 2f + 0.5f * density,
                    paint,
                )
            }
        }
    }
}

/**
 * Segmented transport picker with a lit thumb that slides between cells.
 *
 * The thumb is a sibling view positioned by translationX rather than a
 * background on the selected cell, so the movement is animatable and the cells
 * stay dumb text views.
 *
 * WHY NOT OvershootInterpolator. The previous version used one, so the thumb
 * flew past the protocol the user picked and came back. On a decorative chip
 * that is charming; on the control that chooses how your traffic leaves the
 * country it is a control that appears to disagree with you. The shared springy
 * curve settles once, in the same time.
 */
class TransportRail(
    context: Context,
    private val palette: AppAppearance.Palette,
    private val labels: List<String>,
    private val onPick: (Int) -> Unit,
) : FrameLayout(context) {

    private val thumb: View
    private val cells = mutableListOf<TextView>()
    private var selectedIndex = 0
    private var thumbAnimator: ValueAnimator? = null

    /** Width of one segment, derived from the padded content box. */
    private val cellWidth: Float
        get() {
            val inner = width - paddingLeft - paddingRight
            if (inner <= 0) return 0f
            return inner.toFloat() / labels.size.coerceAtLeast(1)
        }

    init {
        val fill = Sculpt.darken(palette.surface, 0.32f)
        background = Sculpt.recessedBackground(
            resources.displayMetrics.density, fill, Orbit.Radius.PILL,
        )
        val inset = context.px(4)
        setPadding(inset, inset, inset, inset)

        thumb = View(context).apply {
            background = Sculpt.sculptedBackground(
                resources.displayMetrics.density,
                Sculpt.blend(palette.surface, palette.primary, 0.18f),
                Orbit.Radius.PILL,
                Sculpt.withAlpha(palette.primary, 0.48f),
            )
        }
        // No margins here: the FrameLayout padding already insets children.
        // Adding margins on top of the padding is what used to leave the lit
        // thumb short of, and offset from, the cell it was supposed to be under.
        addView(thumb, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, text ->
            val cell = object : TextView(context) {
                // Pressing a rail cell sinks it, the same as every other button.
                // Without this, tapping a protocol produced no feedback at all:
                // the cells are bare TextViews and the thumb only moves after the
                // pick is accepted.
                override fun setPressed(pressed: Boolean) {
                    super.setPressed(pressed)
                    background = if (pressed) {
                        Sculpt.sculptedBackground(
                            resources.displayMetrics.density,
                            Sculpt.darken(palette.surface, 0.18f),
                            Orbit.Radius.PILL,
                            pressed = true,
                        )
                    } else {
                        null
                    }
                }
            }.apply {
                this.text = text
                textSize = 10.5f
                setTextColor(palette.faint)
                letterSpacing = 0.04f
                typeface = Orbit.Type.MEDIUM
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
                contentDescription = "$text transport"
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    select(index, animate = true)
                    onPick(index)
                }
            }
            cells.add(cell)
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        addView(row, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = cellWidth.roundToInt()
        if (width <= 0) return
        thumb.layoutParams = (thumb.layoutParams as LayoutParams).apply { this.width = width }
        thumb.requestLayout()
        thumb.translationX = selectedIndex * cellWidth
    }

    fun select(index: Int, animate: Boolean) {
        if (index !in labels.indices) return
        selectedIndex = index
        cells.forEachIndexed { i, cell ->
            val on = i == index
            cell.setTextColor(if (on) palette.ink else palette.faint)
            // Weight as well as colour. Colour alone is not a state cue — it
            // fails for a colour-blind user and it fails for anyone glancing.
            cell.typeface = if (on) Orbit.Type.MEDIUM else Orbit.Type.REGULAR
            cell.contentDescription = "${cell.text} transport${if (on) ", selected" else ""}"
        }
        val slot = cellWidth
        if (slot <= 0f) return
        val target = index * slot
        thumbAnimator?.cancel()
        if (!animate) {
            thumb.translationX = target
            return
        }
        thumbAnimator = ValueAnimator.ofFloat(thumb.translationX, target).apply {
            duration = Orbit.Motion.SLOW
            interpolator = Orbit.Motion.SPRINGY
            addUpdateListener { thumb.translationX = it.animatedValue as Float }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // Dim the whole rail, and take the thumb's glow away entirely. A locked
        // control that still glows reads as broken rather than locked.
        alpha = if (enabled) 1f else 0.45f
        thumb.background = Sculpt.sculptedBackground(
            resources.displayMetrics.density,
            Sculpt.blend(palette.surface, palette.primary, if (enabled) 0.18f else 0.07f),
            Orbit.Radius.PILL,
            Sculpt.withAlpha(palette.primary, if (enabled) 0.48f else 0.14f),
        )
        cells.forEach { it.isEnabled = enabled }
        contentDescription = if (enabled) null else "Transport locked while the tunnel is active"
    }

    override fun onDetachedFromWindow() {
        thumbAnimator?.cancel()
        thumbAnimator = null
        super.onDetachedFromWindow()
    }
}
