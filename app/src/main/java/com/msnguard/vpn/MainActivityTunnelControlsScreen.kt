package com.msnguard.vpn

import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.msnguard.vpn.MainActivity.H2Fragmentation
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.PAGE_ANIMATION_MS
import com.msnguard.vpn.MainActivity.Companion.LOG_CLOSE_ANIMATION_MS
import com.msnguard.vpn.MainActivity.Companion.RETRY_OBFUSCATION
import com.msnguard.vpn.MainActivity.Companion.WIREGUARD_DATA_CHECK

/**
 * The Tunnel controls page: connection shaping, routing, troubleshooting and
 * anti-DPI knobs.
 */

internal fun MainActivity.openTunnelControlsScreen() {
    tunnelControlsPage?.let(pageHost::removeView)

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
        addView(createHeaderBackButton { closeTunnelControlsScreen() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        addView(label("Tunnel controls", 22f, INK, TypefaceStyle.MEDIUM))
    })
    content.addView(label("Applied on your next connection", 13.5f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-8); bottomMargin = dp(22) })
    // Title and current value are separate columns now, so a long value
    // (a manual endpoint) truncates on its own instead of shoving the title.
    fun addControl(title: String, value: String?, action: () -> Unit): OrbitSettingsRow =
        navRow(title, value, onClick = action).also { row ->
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(9) })
        }
    content.addView(sectionLabel("CONNECTION SHAPING"))
    lateinit var obfRow: OrbitSettingsRow
    obfRow = addControl("Obfuscation", obfuscationProfile().label) {
        chooseObfuscation { obfRow.setValue(obfuscationProfile().label) }
    }
    addControl("Advanced obfuscation", advancedObfuscationSummary()) { editAdvancedObfuscation() }
    lateinit var retryRow: OrbitSettingsRow
    retryRow = addControl("WireGuard retries", if (retryObfuscationProfiles()) "On" else "Off") {
        preferences().edit().putBoolean(RETRY_OBFUSCATION, !retryObfuscationProfiles()).apply()
        retryRow.setValue(if (retryObfuscationProfiles()) "On" else "Off")
    }
    content.addView(sectionLabel("ROUTING"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    addControl("Manual endpoint", manualEndpoint() ?: "Automatic") { editManualEndpoint() }
    addControl("Gateway cache", defaultEndpointDiscovery().label) { manageGatewayCache() }
    content.addView(sectionLabel("TROUBLESHOOTING"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    lateinit var tlsRow: OrbitSettingsRow
    tlsRow = addControl("TLS fingerprint", tlsCurvePreset().label) {
        chooseTlsCurvePreset { tlsRow.setValue(tlsCurvePreset().label) }
    }
    lateinit var verificationRow: OrbitSettingsRow
    verificationRow = addControl("WireGuard verification", if (wireGuardDataCheck()) "Strict" else "Fast") {
        preferences().edit().putBoolean(WIREGUARD_DATA_CHECK, !wireGuardDataCheck()).apply()
        verificationRow.setValue(if (wireGuardDataCheck()) "Strict" else "Fast")
    }
    // The "VPN CORE" section is gone. It held DNS resolvers, Destination
    // routing, and Zero Trust — all three are proxy-mode features:
    //
    //   * DNS resolvers   -> socks.rs::resolver_addresses(), and socks::serve
    //                        never runs in VPN mode (tun::bridge takes its
    //                        place). The device's real resolvers come from
    //                        applyDns() on the Builder.
    //   * Dest. routing   -> RuleSet::from_env(), read only from socks.rs.
    //   * Zero Trust      -> Cloudflare organization accounts, unused here.
    //
    // The underlying prefs and the core's env bridge are untouched, so the
    // knobs still exist for the CLI; they are simply no longer surfaced as
    // settings that silently do nothing on this device.
    content.addView(sectionLabel("ANTI-DPI"), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(26) })
    lateinit var fragRow: OrbitSettingsRow
    fragRow = addControl("TLS fragmentation", if (h2Fragmentation() == H2Fragmentation.ON) "On" else "Off") {
        chooseH2Fragmentation {
            fragRow.setValue(if (h2Fragmentation() == H2Fragmentation.ON) "On" else "Off")
        }
    }
    scroll.addView(content)
    page.addView(scroll)
    page.setOnApplyWindowInsetsListener { _, insets ->
        content.setPadding(dp(24), insets.systemWindowInsetTop + dp(16), dp(24), insets.systemWindowInsetBottom + dp(24))
        insets
    }
    tunnelControlsPage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    content.alpha = 0f
    content.translationY = dp(12).toFloat()
    page.alpha = 0f
    page.translationX = dp(20).toFloat()
    page.animate().alpha(1f).translationX(0f).setDuration(PAGE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()
    content.animate().alpha(1f).translationY(0f).setStartDelay(70)
        .setDuration(PAGE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()
}

internal fun MainActivity.closeTunnelControlsScreen(animate: Boolean = true) {
    val page = tunnelControlsPage ?: return
    tunnelControlsPage = null
    if (!animate) {
        pageHost.removeView(page)
        return
    }
    page.animate().alpha(0f).translationX(dp(20).toFloat())
        .setDuration(LOG_CLOSE_ANIMATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction { if (page.parent == pageHost) pageHost.removeView(page) }
        .start()
}
