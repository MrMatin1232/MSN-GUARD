package com.msnguard.vpn

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.msnguard.vpn.MainActivity.Protocol
import com.msnguard.vpn.MainActivity.ScanTarget
import com.msnguard.vpn.MainActivity.ScanMode
import com.msnguard.vpn.MainActivity.MasqueTransport
import com.msnguard.vpn.MainActivity.EndpointDiscovery
import com.msnguard.vpn.MainActivity.SelectionOption
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.SETTINGS
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_SCAN
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_SCAN_MODE
import com.msnguard.vpn.MainActivity.Companion.ENDPOINT_DISCOVERY
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_MASQUE_TRANSPORT

/**
 * The Scanner options page: discovery budget, MASQUE transport, scan mode and
 * address family.
 */

internal fun MainActivity.openScannerScreen(animate: Boolean = true) {
    if (visualState == OrbitDialView.State.CONNECTING ||
        visualState == OrbitDialView.State.CONNECTED ||
        TunnelStatus.isActive()
    ) return

    showingScanner = true
    scannerPage?.let(pageHost::removeView)

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
        addView(createHeaderBackButton { closeScannerScreen() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Scanner options", 22f, INK, TypefaceStyle.MEDIUM).apply {
            setPadding(dp(4), 0, 0, 0)
        })
    }
    content.addView(label("Choose Aether's endpoint-discovery budget and address families", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(4); bottomMargin = dp(24) })

    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val discoveryOptions = mutableMapOf<EndpointDiscovery, SelectionOption>()
    val transportOptions = mutableMapOf<MasqueTransport, SelectionOption>()
    val modeOptions = mutableMapOf<ScanMode, SelectionOption>()
    val targetOptions = mutableMapOf<ScanTarget, SelectionOption>()

    options.addView(label(if (selectedProtocol == Protocol.MASQUE) "MASQUE GATEWAY DISCOVERY" else "WIREGUARD ENDPOINT DISCOVERY", 12f, MUTED).apply { letterSpacing = 0.1f })
    EndpointDiscovery.entries.forEachIndexed { index, discovery ->
        val option = createEndpointDiscoveryOption(discovery) { chosen ->
            getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
                .putString(ENDPOINT_DISCOVERY, chosen.coreName)
                .apply()
            discoveryOptions.forEach { (item, view) -> setSelectionState(view, item == chosen, animate = true) }
        }
        discoveryOptions[discovery] = option
        options.addView(option.row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
        ).apply { topMargin = if (index == 0) dp(10) else dp(8) })
    }

    if (selectedProtocol == Protocol.MASQUE) {
        options.addView(label("MASQUE TRANSPORT", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) })
        MasqueTransport.entries.forEachIndexed { index, transport ->
            val option = createMasqueTransportOption(transport) { chosen ->
                getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString(DEFAULT_MASQUE_TRANSPORT, chosen.coreName).apply()
                transportOptions.forEach { (item, view) -> setSelectionState(view, item == chosen, animate = true) }
            }
            transportOptions[transport] = option
            options.addView(option.row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(68),
            ).apply { topMargin = if (index == 0) dp(10) else dp(8) })
        }
    }

    options.addView(label("SCAN MODE", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(20) })
    ScanMode.entries.forEachIndexed { index, mode ->
        val option = createScanModeOption(mode) { chosen ->
            getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString(DEFAULT_SCAN_MODE, chosen.coreName).apply()
            modeOptions.forEach { (item, view) -> setSelectionState(view, item == chosen, animate = true) }
        }
        modeOptions[mode] = option
        options.addView(option.row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
        ).apply { topMargin = if (index == 0) dp(10) else dp(8) })
    }

    options.addView(label("IP VERSION", 12f, MUTED).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(20) })
    ScanTarget.entries.forEachIndexed { index, target ->
        val option = createScannerOption(target) { chosen ->
            getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString(DEFAULT_SCAN, chosen.coreName).apply()
            targetOptions.forEach { (item, view) -> setSelectionState(view, item == chosen, animate = true) }
        }
        targetOptions[target] = option
        options.addView(option.row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
        ).apply { topMargin = if (index == 0) dp(10) else dp(8) })
    }

    content.addView(options)
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

    scannerPage = page
    pageHost.addView(page, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    page.requestApplyInsets()
    if (animate) {
        animatePageOpen(page)
        staggerListItems(options)
    }
}

internal fun MainActivity.closeScannerScreen() {
    showingScanner = false
    scannerPage?.let { animatePageClose(it) { scannerPage = null } }
}

internal fun MainActivity.createScannerOption(target: ScanTarget, onSelect: (ScanTarget) -> Unit): SelectionOption {
    val selected = target == defaultScan()
    val title = label(target.label, 16f, INK, TypefaceStyle.MEDIUM)
    val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(18), 0, dp(18), 0)
        contentDescription = "Scan ${target.label} endpoints"
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect(target) }
        val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title)
        labels.addView(label(target.description, 13f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator)
    }
    return SelectionOption(row, title, indicator, 18).also { setSelectionState(it, selected, animate = false) }
}

internal fun MainActivity.createEndpointDiscoveryOption(
    discovery: EndpointDiscovery,
    onSelect: (EndpointDiscovery) -> Unit,
): SelectionOption {
    val selected = discovery == defaultEndpointDiscovery()
    val title = label(discovery.label, 16f, INK, TypefaceStyle.MEDIUM)
    val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(18), 0, dp(18), 0)
        contentDescription = "Use ${discovery.label} MASQUE gateway discovery"
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect(discovery) }
        val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title)
        labels.addView(label(discovery.description, 13f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator)
    }
    return SelectionOption(row, title, indicator, 18).also { setSelectionState(it, selected, animate = false) }
}

internal fun MainActivity.createScanModeOption(mode: ScanMode, onSelect: (ScanMode) -> Unit): SelectionOption {
    val selected = mode == defaultScanMode()
    val title = label(mode.label, 16f, INK, TypefaceStyle.MEDIUM)
    val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(18), 0, dp(18), 0)
        contentDescription = "Use ${mode.label} scan mode"
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect(mode) }
        val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title)
        labels.addView(label(mode.description, 13f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator)
    }
    return SelectionOption(row, title, indicator, 18).also { setSelectionState(it, selected, animate = false) }
}

internal fun MainActivity.createMasqueTransportOption(
    transport: MasqueTransport,
    onSelect: (MasqueTransport) -> Unit,
): SelectionOption {
    val selected = transport == defaultMasqueTransport()
    val title = label(transport.label, 16f, INK, TypefaceStyle.MEDIUM)
    val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(18), 0, dp(18), 0)
        contentDescription = "Use ${transport.label} for MASQUE scanning"
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect(transport) }
        val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(title)
        labels.addView(label(transport.description, 13f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator)
    }
    return SelectionOption(row, title, indicator, 18).also { setSelectionState(it, selected, animate = false) }
}
