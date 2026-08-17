package com.msnguard.vpn

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.msnguard.vpn.MainActivity.TypefaceStyle

/**
 * The Traffic monitor page and the byte formatting it shares with the settings
 * summary row.
 */

internal fun MainActivity.openTrafficMonitorScreen() {
    trafficMonitorPage?.let(pageHost::removeView)
    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
    }
    content.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { closeTrafficMonitorScreen() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Traffic monitor", 22f, INK, TypefaceStyle.MEDIUM).apply { setPadding(dp(4), 0, 0, 0) })
    })
    content.addView(label("Traffic carried by MSN-GUARD. Per-app attribution is not available from encrypted tunnel counters.", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(4); bottomMargin = dp(24) })
    trafficSpeedValue = addTrafficMetric(content, "LIVE SPEED")
    trafficSessionValue = addTrafficMetric(content, "THIS SESSION")
    trafficMonthValue = addTrafficMetric(content, "THIS MONTH")
    scroll.addView(content)
    page.addView(scroll, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    page.setOnApplyWindowInsetsListener { _, insets ->
        content.setPadding(dp(24), insets.systemWindowInsetTop + dp(16), dp(24), insets.systemWindowInsetBottom + dp(24))
        insets
    }
    trafficMonitorPage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    renderTrafficMonitor()
    animatePageOpen(page)
}

internal fun MainActivity.addTrafficMetric(parent: LinearLayout, title: String): TextView {
    val value = label("Waiting for tunnel traffic", 18f, INK, TypefaceStyle.MEDIUM)
    parent.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(15), dp(18), dp(15))
        background = Sculpt.sculptedBackground(
            resources.displayMetrics.density,
            SURFACE_VARIANT,
            18,
            stroke = DIVIDER,
        )
        addView(OrbitSectionHeader(self, palette, title))
        addView(value, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(7) })
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(11) })
    return value
}

internal fun MainActivity.closeTrafficMonitorScreen() {
    trafficMonitorPage?.let { animatePageClose(it) { trafficMonitorPage = null } }
    trafficSpeedValue = null
    trafficSessionValue = null
    trafficMonthValue = null
}

internal fun MainActivity.renderTrafficMonitor() {
    trafficSpeedValue?.text = "↓ ${formatTraffic(trafficSpeedRx)}/s   ↑ ${formatTraffic(trafficSpeedTx)}/s"
    trafficSessionValue?.text = "↓ ${formatTraffic(trafficRx)}   ↑ ${formatTraffic(trafficTx)}"
    trafficMonthValue?.text = "↓ ${formatTraffic(trafficMonthRx)}   ↑ ${formatTraffic(trafficMonthTx)}"
}

/** One-line month total, shown as the Traffic monitor row's value. */
internal fun MainActivity.trafficHeadline(): String =
    if (trafficMonthRx + trafficMonthTx == 0L) "No data yet"
    else formatTraffic(trafficMonthRx + trafficMonthTx) + " this month"

internal fun MainActivity.formatTraffic(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
}
