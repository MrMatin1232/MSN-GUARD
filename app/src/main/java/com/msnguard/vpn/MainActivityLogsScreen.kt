package com.msnguard.vpn

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.msnguard.vpn.MainActivity.LogLevel
import com.msnguard.vpn.MainActivity.LogTab
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.LOG_REFRESH_MS
import com.msnguard.vpn.MainActivity.Companion.LOG_LEVEL

/**
 * The Logs page: level chips, App/Core tabs and the tailing log view.
 */

internal fun MainActivity.openLogsScreen() {
    showingLogs = true
    logsPage?.let(pageHost::removeView)
    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { closeLogsScreen() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        addView(label("Logs", 22f, INK, TypefaceStyle.MEDIUM))
    }
    content.addView(header)
    content.addView(label("Tunnel and VPN events", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-8); bottomMargin = dp(16) })
    val logLevelRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val currentLogLevel = logLevel()
    LogLevel.entries.forEach { level ->
        val isActive = level == currentLogLevel
        val chip = label(level.label, 14f, if (isActive) primaryContainer else INK, TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = roundedBackground(if (isActive) primary else SURFACE_VARIANT, 14, if (isActive) primary else DIVIDER)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                preferences().edit().putString(LOG_LEVEL, level.coreName).apply()
                for (i in 0 until logLevelRow.childCount) {
                    val child = logLevelRow.getChildAt(i) as TextView
                    val childLevel = LogLevel.entries[i]
                    val selected = childLevel == level
                    child.setTextColor(if (selected) primaryContainer else INK)
                    child.background = roundedBackground(if (selected) primary else SURFACE_VARIANT, 12, if (selected) primary else DIVIDER)
                }
            }
        }
        logLevelRow.addView(chip, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { rightMargin = dp(4) })
    }
    content.addView(logLevelRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(0); rightMargin = dp(0); bottomMargin = dp(12) })
    val logTabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    var selectedLogTab = LogTab.ALL
    val tabViews = mutableMapOf<LogTab, TextView>()
    LogTab.entries.forEach { tab ->
        val tabView = label(tab.label, 13f, INK, TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedLogTab = tab
                tabViews.forEach { (item, view) ->
                    val active = item == tab
                    view.setTextColor(if (active) primaryContainer else INK)
                    view.background = roundedBackground(if (active) primary else SURFACE_VARIANT, 14, if (active) primary else DIVIDER)
                }
            }
        }
        tabViews[tab] = tabView
        logTabs.addView(tabView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })
    }
    tabViews.forEach { (tab, view) ->
        val active = tab == selectedLogTab
        view.setTextColor(if (active) primaryContainer else INK)
        view.background = roundedBackground(if (active) primary else SURFACE_VARIANT, 14, if (active) primary else DIVIDER)
    }
    content.addView(logTabs, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(12) })
    val events = label(textSize = 13f, color = INK).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
    var followLatest = true
    val scroll = ScrollView(this).apply {
        addView(events)
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = getChildAt(0)?.height ?: 0
            followLatest = scrollY >= contentHeight - height - dp(8)
        }
    }
    content.addView(scroll, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
    ))
    page.addView(content, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply {
        leftMargin = dp(24)
        rightMargin = dp(24)
        topMargin = dp(16)
        bottomMargin = dp(16)
    })
    page.setOnApplyWindowInsetsListener { _, insets ->
        (content.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insets.systemWindowInsetTop + dp(16)
            bottomMargin = insets.systemWindowInsetBottom + dp(16)
            content.layoutParams = this
        }
        insets
    }
    val refreshHandler = Handler(Looper.getMainLooper())
    var renderedLogs: String? = null
    val refresh = object : Runnable {
        override fun run() {
            val updatedLogs = connectionLogText(selectedLogTab)
            if (updatedLogs != renderedLogs) {
                val keepAtBottom = followLatest || renderedLogs == null
                events.text = updatedLogs
                renderedLogs = updatedLogs
                if (keepAtBottom) {
                    scroll.post {
                        scroll.scrollTo(0, (scroll.getChildAt(0)?.height ?: 0) - scroll.height)
                    }
                }
            }
            if (showingLogs) refreshHandler.postDelayed(this, LOG_REFRESH_MS)
        }
    }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = refresh.run()
        override fun onViewDetachedFromWindow(view: View) = refreshHandler.removeCallbacks(refresh)
    })
    logsPage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    animatePageOpen(page)
}

internal fun MainActivity.closeLogsScreen() {
    showingLogs = false
    logsPage?.let { animatePageClose(it) { logsPage = null } }
}

internal fun MainActivity.connectionLogText(tab: LogTab = LogTab.ALL): String {
    val appEvents = ConnectionLog.snapshot()
    val coreEvents = NativeCore.lastLog().lineSequence().filter(String::isNotBlank).toList()
    val events = when (tab) {
        LogTab.ALL -> appEvents + coreEvents
        LogTab.APP -> appEvents
        LogTab.CORE -> coreEvents
    }
    return events.joinToString("\n").ifBlank { "No connection events yet" }
}
