package com.msnguard.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Settings-screen building blocks in the Orbit visual language.
 *
 * There are six sub-screens (Tunnel controls, Theme, Traffic monitor, Split
 * tunneling, Scan mode, Logs) and they are all assembled from the three
 * components in this file. That is deliberate: restyling six screens by hand is
 * how they drift apart, and they had already drifted — the home screen was
 * sculpted glass while Settings was still flat rectangles.
 */

/**
 * A section label: neon tick, caption, then a hairline rule to the edge.
 *
 * The rule is the part that was missing. An uppercase caption on its own is not
 * a divider, it is simply smaller text above other text, and a page made of six
 * of them reads as one long undifferentiated list. The rule gives the eye an
 * actual horizontal break to rest on, which is what lets someone skim to the
 * section they want instead of reading every row.
 */
class OrbitSectionHeader(
    context: Context,
    palette: AppAppearance.Palette,
    text: String,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(Orbit.Space.XS), 0, dp(Orbit.Space.XS))

        addView(TickView(context, palette.primary), LayoutParams(dp(3), dp(12)).apply {
            rightMargin = dp(Orbit.Space.S)
        })
        addView(TextView(context).apply {
            this.text = text
            textSize = Orbit.Type.CAPTION
            setTextColor(palette.muted)
            letterSpacing = Orbit.Type.TRACK_OVERLINE * 0.85f
            typeface = Orbit.Type.MEDIUM
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(RuleView(context, palette.divider), LayoutParams(0, dp(1), 1f).apply {
            leftMargin = dp(Orbit.Space.S)
        })

        // The whole thing is one heading to a screen reader, not a decoration
        // followed by a word.
        contentDescription = text
        isFocusable = false
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /** A rounded 3dp bar with a soft glow; drawn rather than shipped as a
     *  drawable so the glow radius can scale with density. */
    private class TickView(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density

        init {
            // setShadowLayer is a no-op under hardware acceleration on some
            // drivers; this view is 3x12dp so a software layer costs nothing.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = color
            paint.setShadowLayer(3f * density, 0f, 0f, Sculpt.withAlpha(color, 0.75f))
            val radius = width / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.clearShadowLayer()
        }
    }

    /** A hairline that fades out toward the right, so it ends rather than stops. */
    private class RuleView(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Sculpt.withAlpha(color, 0.9f), Sculpt.withAlpha(color, 0f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
    }
}

/**
 * A sculpted settings row: optional leading glyph in a well, title, optional
 * trailing value, chevron.
 *
 * The value is a separate, dimmer text view on the right instead of being
 * appended to the title with " · ", so a long value (a manual endpoint, a theme
 * name) truncates on its own without pushing the title off-screen. The title is
 * what somebody is scanning for; it never yields space to the value.
 *
 * The three background states are built ONCE, in init. The previous version
 * constructed a fresh GlassDrawable on every press and every focus change — six
 * shader allocations per tap, on a list that can be twenty rows long.
 */
class OrbitSettingsRow(
    context: Context,
    private val palette: AppAppearance.Palette,
    title: String,
    value: String? = null,
    private val destructive: Boolean = false,
    iconRes: Int? = null,
    onClick: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val titleView: TextView
    private val valueView: TextView

    private val restingBackground: Drawable
    private val pressedBackground: Drawable
    private val focusedBackground: Drawable

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Orbit.Space.M), dp(Orbit.Space.M), dp(Orbit.Space.M), dp(Orbit.Space.M))
        // Rows were landing near 46dp with a 19dp icon, which is under the
        // platform's 48dp touch minimum — fine for a mouse, not for a thumb on a
        // moving bus, which is the realistic use case for this app.
        minimumHeight = dp(56)
        isClickable = true
        isFocusable = true

        val accent = if (destructive) palette.danger else palette.primary

        restingBackground = Sculpt.sculptedBackground(
            density, palette.surfaceVariant, Orbit.Radius.TILE, stroke = palette.hairline,
        )
        pressedBackground = Sculpt.sculptedBackground(
            density,
            Sculpt.darken(palette.surfaceVariant, 0.10f),
            Orbit.Radius.TILE,
            accent = Sculpt.withAlpha(accent, 0.45f),
            pressed = true,
        )
        focusedBackground = Sculpt.sculptedBackground(
            density,
            palette.surfaceVariant,
            Orbit.Radius.TILE,
            accent = Sculpt.withAlpha(accent, 0.75f),
            strokeWidth = 2,
        )
        background = restingBackground

        iconRes?.let { res ->
            // The icon sits in a recessed well, as it does in the action bar, so
            // the two surfaces read as the same design language rather than as
            // two apps stitched together.
            val well = View(context).apply {
                background = Sculpt.recessedBackground(
                    density,
                    Sculpt.darken(palette.surfaceVariant, 0.20f),
                    Orbit.Radius.CHIP,
                    Sculpt.withAlpha(palette.ink, 0.07f),
                )
            }
            val icon = ImageView(context).apply {
                setImageResource(res)
                setColorFilter(accent)
            }
            addView(FrameLayout(context).apply {
                addView(well, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                addView(icon, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            }, LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(Orbit.Space.M) })
        }

        titleView = TextView(context).apply {
            text = title
            textSize = Orbit.Type.BODY
            setTextColor(if (destructive) palette.danger else palette.ink)
            typeface = Orbit.Type.MEDIUM
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        valueView = TextView(context).apply {
            text = value.orEmpty()
            textSize = Orbit.Type.LABEL
            setTextColor(Sculpt.withAlpha(palette.muted, 0.95f))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (value.isNullOrBlank()) GONE else VISIBLE
        }
        addView(valueView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(Orbit.Space.S) })

        addView(
            ChevronGlyph(context, Sculpt.withAlpha(palette.muted, 0.75f)),
            LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(Orbit.Space.XS) },
        )

        syncDescription()
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }

    /** Presses sink inward: the highlight and the occlusion swap places. */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        syncBackground()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, rect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, rect)
        syncBackground()
    }

    /**
     * One place that decides which of the three backgrounds is current.
     *
     * onFocusChanged used to set resting/focused directly without consulting
     * isPressed, so a D-pad focus change (or a focus steal) while a finger was
     * down repainted the row as raised mid-press. Both entry points now resolve
     * the same precedence.
     */
    private fun syncBackground() {
        background = when {
            isPressed -> pressedBackground
            isFocused -> focusedBackground
            else -> restingBackground
        }
    }

    fun setValue(value: String?) {
        valueView.text = value.orEmpty()
        valueView.visibility = if (value.isNullOrBlank()) GONE else VISIBLE
        syncDescription()
    }

    fun setTitle(title: String) {
        titleView.text = title
        syncDescription()
    }

    private fun syncDescription() {
        val value = valueView.text?.toString()?.takeIf { it.isNotBlank() }
        contentDescription = listOfNotNull(titleView.text?.toString(), value).joinToString(", ")
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}

/** A chevron drawn with two strokes; avoids shipping another vector asset. */
private class ChevronGlyph(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        paint.strokeWidth = 1.7f * density
        val cx = width / 2f - density
        val cy = height / 2f
        val arm = 3.8f * density
        canvas.drawLine(cx - arm / 2, cy - arm, cx + arm / 2, cy, paint)
        canvas.drawLine(cx + arm / 2, cy, cx - arm / 2, cy + arm, paint)
    }
}

/**
 * A sculpted toggle row with a lit track.
 *
 * FIXED HERE: the content description was set once, in the constructor, and
 * never updated. Every toggle in Settings therefore reported its INITIAL state
 * to a screen reader for the lifetime of the screen — turn the kill switch on
 * and TalkBack kept saying "off". For a security setting that is not a polish
 * issue, it is a correctness one. It is now resynced on every change, on the row
 * and on the track.
 *
 * The track lights with a gradient rather than a flat accent fill, and the thumb
 * keeps a contact shadow, so "on" is unmistakable in a dark room — which is the
 * condition this app is actually used in.
 */
class OrbitToggleRow(
    context: Context,
    private val palette: AppAppearance.Palette,
    private val title: String,
    subtitle: String,
    checked: Boolean,
    private val onToggle: (Boolean) -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var isOn = checked
    private val track: View
    private val thumb: View
    private val trackWidth = dp(46)
    private val trackHeight = dp(27)
    private val thumbSize = dp(21)
    private val thumbInset = dp(3)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Orbit.Space.M), dp(Orbit.Space.M), dp(Orbit.Space.M), dp(Orbit.Space.M))
        minimumHeight = dp(60)
        background = Sculpt.sculptedBackground(
            density, palette.surfaceVariant, Orbit.Radius.TILE, stroke = palette.hairline,
        )

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = Orbit.Type.BODY
                setTextColor(palette.ink)
                typeface = Orbit.Type.MEDIUM
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = Orbit.Type.CAPTION
                setTextColor(Sculpt.withAlpha(palette.muted, 0.95f))
            }, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(Orbit.Space.HAIR) })
        }
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        thumb = View(context).apply {
            layoutParams = LayoutParams(thumbSize, thumbSize)
            translationX = restingThumbX()
        }
        track = LinearLayout(context).apply {
            layoutParams = LayoutParams(trackWidth, trackHeight)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            addView(thumb)
            setOnClickListener { toggle() }
        }
        addView(track, LayoutParams(trackWidth, trackHeight).apply {
            leftMargin = dp(Orbit.Space.M)
        })

        renderSwitch()
        // Tapping anywhere on the row toggles it. A 27dp track is a small target
        // on a 6" phone, and the whole row already looks tappable.
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
        syncDescription()
    }

    private fun toggle() {
        isOn = !isOn
        onToggle(isOn)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        thumb.animate()
            .translationX(restingThumbX())
            .setDuration(Orbit.Motion.FAST)
            .setInterpolator(Orbit.Motion.STANDARD)
            .start()
        renderSwitch()
        syncDescription()
    }

    private fun restingThumbX(): Float =
        if (isOn) (trackWidth - thumbSize - thumbInset).toFloat() else thumbInset.toFloat()

    private fun renderSwitch() {
        track.background = if (isOn) {
            // trackHeight is already in pixels; there used to be a
            // trackHeightDp() helper here returning a second copy of the literal
            // 27, which was then run through dp() again at this call site.
            LitTrack(context, palette.primary, trackHeight.toFloat())
        } else {
            Sculpt.recessedBackground(
                density,
                Sculpt.darken(palette.canvas, 0.20f),
                Orbit.Radius.PILL,
                palette.hairline,
            )
        }
        thumb.background = Sculpt.sculptedBackground(
            density,
            if (isOn) 0xFFFFFFFF.toInt() else Sculpt.lighten(palette.muted, 0.10f),
            Orbit.Radius.PILL,
            accent = if (isOn) 0xFFFFFFFF.toInt() else null,
        )
    }

    private fun syncDescription() {
        val label = "$title, ${if (isOn) "on" else "off"}"
        contentDescription = label
        track.contentDescription = label
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /**
     * The lit track.
     *
     * A gradient rather than a flat accent fill: a flat pill of one bright colour
     * next to a white thumb loses its edge definition at low screen brightness,
     * which is exactly when this app is open.
     */
    private class LitTrack(
        context: Context,
        private val accent: Int,
        private val heightPx: Float,
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val r = minOf(b.width(), b.height()) / 2f
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                intArrayOf(Sculpt.darken(accent, 0.18f), accent, Sculpt.lighten(accent, 0.22f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                r, r, paint,
            )
            paint.shader = null
            // Inner top shadow so the thumb still reads as sitting IN the track
            // rather than on top of a coloured pill.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * density
            paint.color = Sculpt.withAlpha(Sculpt.darken(accent, 0.45f), 0.55f)
            canvas.drawRoundRect(
                b.left + 0.6f * density, b.top + 0.6f * density,
                b.right - 0.6f * density, b.bottom - 0.6f * density,
                r, r, paint,
            )
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicHeight(): Int = heightPx.roundToInt()
    }
}
