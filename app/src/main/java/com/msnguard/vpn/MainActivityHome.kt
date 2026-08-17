package com.msnguard.vpn

import android.widget.ImageView.ScaleType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.msnguard.vpn.MainActivity.Protocol
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.FIT_SLACK_DP

/**
 * Construction and repainting of the Orbit home console: splash overlay, header,
 * dial column, metric tiles and the sparkline samples pushed into them.
 */

internal fun MainActivity.showOpeningOverlay() {
    val overlay = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val logo = ImageView(this).apply {
        setImageResource(R.drawable.msnguard_splash_logo)
        contentDescription = getString(R.string.app_name)
        scaleType = ScaleType.FIT_CENTER
        alpha = 0f
        scaleX = 0.82f
        scaleY = 0.82f
    }
    overlay.addView(logo, FrameLayout.LayoutParams(dp(198), dp(276), Gravity.CENTER))
    pageHost.addView(overlay)

    logo.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(500)
        .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
        .withEndAction {
            logo.animate()
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(700)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            pageHost.removeView(overlay)
                            orbitDial.requestFocus()
                        }
                        .start()
                }
                .start()
        }
        .start()
}

internal fun MainActivity.createHeader(): LinearLayout = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    // No mark in the header. The brand lives on the launcher icon and the
    // opening splash; repeating it above a single connect button was upstream
    // furniture, not information. What belongs here is live state: a small
    // LED that mirrors the dial, plus the settings entry.
    statusLed.layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
        rightMargin = dp(8)
    }
    addView(statusLed, statusLed.layoutParams)
    addView(label("MSN-GUARD", 13f, MUTED, TypefaceStyle.MEDIUM).apply {
        letterSpacing = 0.14f
    })
    addView(View(self), LinearLayout.LayoutParams(0, 1, 1f))
    addView(ImageView(self).apply {
        setImageResource(R.drawable.ic_settings)
        contentDescription = "Settings"
        isClickable = true
        isFocusable = true
        val p = dp(12)
        setPadding(p, p, p, p)
        setColorFilter(INK)
        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { openSettingsScreen() }
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
}

internal fun MainActivity.railLabel(protocol: Protocol): String = when (protocol) {
    Protocol.WARP_IN_WARP -> "WoW"
    else -> protocol.label
}

/** Status LED colour + glow for the header chip. */
internal fun MainActivity.renderStatusLed() {
    val (fill, glow) = when (visualState) {
        OrbitDialView.State.CONNECTED -> connected to true
        OrbitDialView.State.DEGRADED -> 0xFFFFC46B.toInt() to true
        OrbitDialView.State.CONNECTING -> primary to true
        OrbitDialView.State.FAILED -> 0xFFFF6B7F.toInt() to false
        OrbitDialView.State.DISCONNECTED -> MUTED to false
    }
    statusLed.background = Sculpt.sculptedBackground(
        resources.displayMetrics.density,
        if (glow) fill else Sculpt.withAlpha(MUTED, 0.4f),
        999,
        accent = if (glow) Sculpt.lighten(fill, 0.4f) else null,
    )
}

/**
 * Shrink the dial until the console fits the viewport, instead of scrolling.
 *
 * The main screen used to scroll on a 1080x2400 phone: the column measured
 * taller than the space between the header and the navigation bar, so the
 * action bar sat partly below the fold and the user had to drag the screen
 * to reach it. A one-tap-connect app must not hide its controls.
 *
 * The fix scales the dial, which is by far the tallest element, rather than
 * squeezing the cards or the type — those are already at their minimum
 * legible size. The scale is applied to the dial's whole measured box (ring
 * AND bleed together), so no amount of shrinking can crop the halo or the
 * pulse rings: that was the previous bug and it must not come back.
 *
 * Runs on every layout pass because the viewport changes with rotation,
 * multi-window, and the inset listener firing after the first measure. It is
 * idempotent: [OrbitDialView.sizeScale] ignores a value it already has, so a
 * settled layout costs one comparison and no relayout.
 */
internal fun MainActivity.fitConsoleToViewport(scroll: ScrollView, console: LinearLayout) {
    // isFillViewport must stay false for this to work. With it on, a console
    // shorter than the viewport is stretched to the viewport height, so
    // console.height would read "exactly fits" no matter how much room is
    // actually free and the dial could never grow back after a rotation.
    scroll.isFillViewport = false
    scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val viewport = scroll.height
        val natural = console.height
        val dialBox = orbitDial.height
        if (viewport <= 0 || natural <= 0 || dialBox <= 0) return@addOnLayoutChangeListener

        // Signed: > 0 means the column overflows, < 0 means room to spare.
        // The dial is the only element that scales, so the whole delta has to
        // come out of (or go into) its box.
        val delta = natural - viewport - dp(FIT_SLACK_DP)
        val current = orbitDial.sizeScale
        // Clamped to the SAME range the setter enforces. If the target were
        // left below the floor, current would sit clamped at the floor while
        // target stayed lower, and the two would never agree — an endless
        // relayout loop on a screen too short to satisfy.
        val target = (current * (dialBox - delta).toFloat() / dialBox)
            .coerceIn(OrbitDialView.MIN_SIZE_SCALE, 1f)

        // Tolerance, not equality: two adjacent float values would otherwise
        // keep re-triggering layout and the screen would dither forever.
        // 0.004 of the dial is well under one pixel at any density.
        if (kotlin.math.abs(target - current) > 0.004f) {
            // Posted, not applied inline: this runs inside a layout pass, and
            // requestLayout() from there is either dropped or logged as
            // "improperly called during layout" depending on the Android
            // version. The post lands it on the next frame instead.
            orbitDial.post { orbitDial.sizeScale = target }
        }
    }
}

internal fun MainActivity.createConnectionConsole(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(20), 0, dp(20), dp(20))
    // The dial's halo and its two pulse rings are drawn outside its own 266dp
    // box, on purpose, exactly as the mock's `inset:-24px` / `scale(1.32)` do.
    // With the default clipChildren=true Android cut them off at the box edge,
    // which is why the glow looked amputated at the bottom and the heartbeat
    // seemed to burst out of an invisible frame. Both flags are required:
    // clipChildren for the ring, clipToPadding for the 20dp side padding.
    clipChildren = false
    clipToPadding = false

    addView(orbitDial, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(14)
        // Cancel the console's 20dp side padding for this one child.
        //
        // The dial measures (RING + BLEED) * 2 so its glow has canvas to land
        // on. The console's own padding would clamp that box and force the
        // ring below its intended size; negative margins give the dial the
        // full width back, so the ring keeps its size and the bleed still
        // fits. clipToPadding=false (set above) is what lets it draw there.
        leftMargin = -dp(20)
        rightMargin = -dp(20)
    })

    addView(connectionTitle, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(14) })
    addView(connectionDetail, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(3) })

    val chipLine = LinearLayout(self).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        chipLatency.setTextColor(Sculpt.withAlpha(MUTED, 0.95f))
        chipProtocol.setTextColor(Sculpt.withAlpha(MUTED, 0.95f))
        addView(chipLatency)
        addView(label("  ·  ", 12f, Sculpt.withAlpha(MUTED, 0.5f)))
        addView(chipProtocol)
    }
    addView(chipLine, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(7) })

    val tiles = LinearLayout(self).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tileDown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(9) })
        addView(tileUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(0); rightMargin = dp(9) })
        addView(tileSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(0) })
    }
    addView(tiles, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(14) })

    addView(exitNodeCard, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) })

    addView(transportRail, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(46),
    ).apply { topMargin = dp(12) })

    addView(actionBar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(56),
    ).apply { topMargin = dp(10) })

    // Fills the gap that used to sit between the action bar and the bottom
    // inset. Weight is one Path; it only animates while connected.
    addView(footerWave, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(44),
    ).apply { topMargin = dp(8) })
}

internal fun MainActivity.resetMetrics() {
    tileDown.setValue("0", "B")
    tileUp.setValue("0", "B")
    tileSpeed.setValue("0", "KB/S")
    tileDown.resetBars()
    tileUp.resetBars()
    tileSpeed.resetBars()
    exitNodeCard.resetSpark()
}

/** Splits a byte count into a scaled number and its unit for the tiles. */
internal fun MainActivity.scaleBytes(bytes: Long): Pair<String, String> = when {
    bytes < 1_024L -> bytes.toString() to "B"
    bytes < 1_048_576L -> (bytes / 1_024L).toString() to "KB"
    bytes < 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0) to "MB"
    else -> String.format(java.util.Locale.US, "%.2f", bytes / 1_073_741_824.0) to "GB"
}

/** Pushes the latest traffic sample into the three home-screen tiles. */
internal fun MainActivity.renderHomeMetrics() {
    val (downValue, downUnit) = scaleBytes(trafficRx)
    val (upValue, upUnit) = scaleBytes(trafficTx)
    tileDown.setValue(downValue, downUnit)
    tileUp.setValue(upValue, upUnit)
    val combined = trafficSpeedRx + trafficSpeedTx
    // Speed is shown in KB/s, not MB/s. On Iranian mobile carriers a normal
    // session sits in the tens or low hundreds of KB/s, and "%.1f MB/S"
    // rendered every one of those as a flat 0.0 — the tile looked broken on a
    // working tunnel. KB/s keeps two useful digits at real speeds and only
    // switches to MB/s once there is a whole megabyte to show.
    val kbPerSecond = combined / 1_024.0
    if (kbPerSecond >= 1_024.0) {
        tileSpeed.setValue(String.format(java.util.Locale.US, "%.1f", kbPerSecond / 1_024.0), "MB/S")
    } else {
        tileSpeed.setValue(String.format(java.util.Locale.US, "%.0f", kbPerSecond), "KB/S")
    }
    // Bars are relative to a 512 KB/s ceiling — a realistic mobile-tunnel
    // full scale. The old 4 MB/s ceiling squashed every real sample into the
    // bottom 5% of the sparkline, so the bars never visibly moved.
    val ceiling = 512.0
    tileDown.push((trafficSpeedRx / 1_024.0 / ceiling).toFloat().coerceIn(0.04f, 1f))
    tileUp.push((trafficSpeedTx / 1_024.0 / ceiling).toFloat().coerceIn(0.04f, 1f))
    tileSpeed.push((kbPerSecond / ceiling).toFloat().coerceIn(0.04f, 1f))
    exitNodeCard.pushSample((kbPerSecond / ceiling).toFloat().coerceIn(0.04f, 1f))
}

internal fun MainActivity.setModeEnabled(enabled: Boolean) {
    transportRail.isEnabled = enabled
}
