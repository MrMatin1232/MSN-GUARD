package com.msnguard.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.NOTIFICATION_PERMISSION_REQUEST

/**
 * The view vocabulary this screen is built from: labels, rounded backgrounds,
 * rows, buttons and density conversion. No XML layouts exist in this app, so
 * these functions are the layout language.
 */

/**
 * The activity itself.
 *
 * View-building code lifted out of the class body cannot write
 * `this@MainActivity`, because inside an extension function that label does not
 * exist. It refers to the activity through this property instead, which reads
 * the same at the call sites that pass a [Context] to a widget constructor.
 */
internal val MainActivity.self: MainActivity get() = this

/**
 * Draws a focus ring in the accent colour, for D-pad and keyboard navigation.
 *
 * Takes the activity explicitly: it is an extension on [View], so it cannot
 * reach the palette through a receiver of its own.
 */
internal fun View.highlightOnFocus(activity: MainActivity, radius: Int, fill: Int, stroke: Int) {
    onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
        view.background = activity.roundedBackground(
            fill,
            radius,
            if (focused) activity.primary else stroke,
            if (focused) 2 else 1,
        )
    }
}

internal fun MainActivity.configureSystemBars() {
    window.statusBarColor = CANVAS
    window.navigationBarColor = CANVAS
    val lightBars = !isDarkCanvas()
    val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        runCatching {
            window.insetsController?.setSystemBarsAppearance(if (lightBars) flags else 0, flags)
        }.isSuccess
    } else {
        false
    }
    if (!applied) {
        @Suppress("DEPRECATION")
        var visibility = 0
        if (lightBars) {
            visibility = visibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility = visibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = visibility
    }
}

internal fun MainActivity.isDarkCanvas(): Boolean {
    val color = CANVAS
    val r = color shr 16 and 0xFF
    val g = color shr 8 and 0xFF
    val b = color and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000 < 140
}

internal fun MainActivity.createHeaderBackButton(onClick: () -> Unit): ImageView = ImageView(this).apply {
    setImageResource(R.drawable.ic_back)
    contentDescription = "Back"
    isClickable = true
    isFocusable = true
    val p = dp(12)
    setPadding(p, p, p, p)
    setColorFilter(INK)
    val outValue = android.util.TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
    setBackgroundResource(outValue.resourceId)
    setOnClickListener { onClick() }
}

internal fun MainActivity.requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }
}

internal fun MainActivity.label(
    text: String = "",
    textSize: Float,
    color: Int,
    style: TypefaceStyle = TypefaceStyle.REGULAR,
    singleLine: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    this.textSize = textSize
    setTextColor(color)
    if (singleLine) {
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    typeface = when (style) {
        TypefaceStyle.REGULAR -> android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
        TypefaceStyle.MEDIUM -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}

internal fun MainActivity.roundedBackground(fill: Int, radius: Int, stroke: Int, strokeWidth: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(strokeWidth), stroke)
    }

internal fun MainActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun MainActivity.appVersion(): String =
    packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"

internal fun MainActivity.createSettingsButton(
    text: String,
    icon: Int? = null,
    backgroundOverride: Int? = null,
    textColorOverride: Int? = null,
    tintIcon: Boolean = true,
    onClick: () -> Unit,
): TextView = label(text, 15f, textColorOverride ?: INK, TypefaceStyle.MEDIUM).apply {
    gravity = Gravity.CENTER
    setPadding(dp(18), 0, dp(18), 0)
    background = roundedBackground(backgroundOverride ?: SURFACE_VARIANT, 16, backgroundOverride ?: SURFACE_VARIANT)
    isClickable = true
    isFocusable = true
    contentDescription = text
    highlightOnFocus(self, 16, backgroundOverride ?: SURFACE_VARIANT, backgroundOverride ?: SURFACE_VARIANT)
    icon?.let {
        setCompoundDrawablesRelativeWithIntrinsicBounds(it, 0, 0, 0)
        compoundDrawablePadding = dp(12)
        if (tintIcon) compoundDrawablesRelative[0]?.setTint(textColorOverride ?: primary)
        gravity = Gravity.CENTER_VERTICAL
    }
    setOnClickListener { onClick() }
}

internal fun MainActivity.openLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/**
 * Toggle row. Now an [OrbitToggleRow] — a sculpted card with a neon track
 * instead of the old flat grey pill. The return type stays LinearLayout so
 * every existing call site is unchanged.
 */
internal fun MainActivity.createToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit): LinearLayout =
    OrbitToggleRow(this, palette, title, subtitle, checked, onToggle)

/** Section caption with a neon tick, for the settings pages. */
internal fun MainActivity.sectionLabel(text: String): View = OrbitSectionHeader(this, palette, text)

/** A sculpted navigation row: title on the left, current value on the right. */
internal fun MainActivity.navRow(
    title: String,
    value: String? = null,
    iconRes: Int? = null,
    onClick: () -> Unit,
): OrbitSettingsRow = OrbitSettingsRow(this, palette, title, value, iconRes = iconRes, onClick = onClick)
