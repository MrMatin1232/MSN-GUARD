package com.msnguard.vpn

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.KILL_SWITCH
import com.msnguard.vpn.MainActivity.Companion.LAN_BYPASS

/**
 * The Settings page: protection, routing, connection and about sections.
 */

internal fun MainActivity.openSettingsScreen(animate: Boolean = true) {
    showingSettings = true
    settingsPage?.let(pageHost::removeView)

    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
    }

    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(CANVAS)
        addView(createHeaderBackButton { closeSettingsScreen() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Settings", 22f, INK, TypefaceStyle.MEDIUM).apply {
            setPadding(dp(4), 0, 0, 0)
        })
    }
    content.addView(sectionLabel("PROTECTION"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ))
    val killSwitchRow = createToggleRow("Kill switch", "Block all traffic if the tunnel drops", killSwitchEnabled()) {
        preferences().edit().putBoolean(KILL_SWITCH, it).apply()
    }
    content.addView(killSwitchRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) })

    // "Share on LAN" is gone with proxy mode: it only ever widened the SOCKS
    // bind from 127.0.0.1 to 0.0.0.0, and in VPN mode no SOCKS listener is
    // exposed at all. lanSharingEnabled() survives as a helper because
    // lanBypassEnabled() still migrates the old value forward.
    val lanBypassRow = createToggleRow(
        "Bypass LAN",
        "Keep local devices reachable while connected",
        lanBypassEnabled(),
    ) {
        preferences().edit().putBoolean(LAN_BYPASS, it).apply()
    }
    content.addView(lanBypassRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })

    content.addView(sectionLabel("ROUTING & DATA"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    content.addView(navRow("Traffic monitor", trafficHeadline()) { openTrafficMonitorScreen() }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) })
    splitTunnelSummaryButton = navRow("Split tunneling", splitTunnelSummary()) { openSplitTunnelScreen() }
    content.addView(splitTunnelSummaryButton, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })
    // PERF moved here off the home screen: a once-a-year knob does not earn
    // a quarter of the first thing the user sees.
    lateinit var perfRow: OrbitSettingsRow
    perfRow = navRow("Performance", perfProfile().label) {
        choosePerfProfile { perfRow.setValue(perfProfile().label) }
    }
    content.addView(perfRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })

    content.addView(sectionLabel("CONNECTION"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    content.addView(navRow("Connection mode", selectedProtocol.label) { openModeScreen() }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) })
    content.addView(navRow("Tunnel controls", "Shaping · Anti-DPI") { openTunnelControlsScreen() }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })

    content.addView(sectionLabel("ABOUT"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    content.addView(navRow("Check for updates", "v${appVersion()}") {
        appUpdater.checkForUpdate()
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) })
    content.addView(navRow("Source on GitHub", iconRes = R.drawable.ic_github) {
        openLink("https://github.com/mbm110/MSN-GUARD")
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) })
    content.addView(label("MSN-GUARD ${appVersion()}", 11.5f, Sculpt.withAlpha(MUTED, 0.7f)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        letterSpacing = 0.06f
        setPadding(0, dp(22), 0, 0)
    })

    scroll.addView(content)
    page.addView(scroll, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply { topMargin = dp(56) })
    page.addView(header, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(48),
        Gravity.TOP,
    ).apply { leftMargin = dp(24); rightMargin = dp(24); topMargin = dp(8) })

    page.setOnApplyWindowInsetsListener { _, insets ->
        (scroll.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insets.systemWindowInsetTop + dp(56)
            bottomMargin = insets.systemWindowInsetBottom
            scroll.layoutParams = this
        }
        (header.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insets.systemWindowInsetTop + dp(8)
            header.layoutParams = this
        }
        insets
    }

    settingsPage = page
    pageHost.addView(page, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    page.requestApplyInsets()
    if (animate) {
        animatePageOpen(page)
        staggerListItems(content)
    }
}

internal fun MainActivity.closeSettingsScreen() {
    showingSettings = false
    settingsPage?.let { animatePageClose(it) { settingsPage = null } }
}
