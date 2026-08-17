package com.msnguard.vpn

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.msnguard.vpn.MainActivity.Protocol
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.DISABLED_ALPHA

/**
 * The Connection mode page, listing every [MainActivity.Protocol].
 */

internal fun MainActivity.openModeScreen() {
    if (visualState == OrbitDialView.State.CONNECTING ||
        visualState == OrbitDialView.State.CONNECTED ||
        TunnelStatus.isActive()
    ) return

    showingMode = true
    modePage?.let(pageHost::removeView)

    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
    }

    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { closeModeScreen() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Connection mode", 22f, INK, TypefaceStyle.MEDIUM).apply {
            setPadding(dp(4), 0, 0, 0)
        })
    }
    content.addView(header, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) })

    content.addView(label("Choose how MSN-GUARD connects", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(4); bottomMargin = dp(24) })

    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    Protocol.entries.forEachIndexed { index, protocol ->
        options.addView(createModeOption(protocol), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(76),
        ).apply { if (index > 0) topMargin = dp(12) })
    }

    content.addView(options)
    page.addView(content, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ))

    page.setOnApplyWindowInsetsListener { _, insets ->
        content.setPadding(dp(24), insets.systemWindowInsetTop + dp(16), dp(24), insets.systemWindowInsetBottom + dp(24))
        insets
    }

    modePage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    animatePageOpen(page)
    staggerListItems(options)
}

internal fun MainActivity.closeModeScreen() {
    showingMode = false
    modePage?.let { animatePageClose(it) { modePage = null } }
}

internal fun MainActivity.createModeOption(protocol: Protocol): LinearLayout {
    val selected = protocol == selectedProtocol
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), 0, dp(20), 0)
        background = roundedBackground(
            if (selected) primaryContainer else SURFACE_VARIANT,
            20,
            if (selected) primary else SURFACE_VARIANT,
        )
        isClickable = protocol.androidAvailable
        isFocusable = protocol.androidAvailable
        alpha = if (protocol.androidAvailable) 1f else DISABLED_ALPHA
        setOnClickListener {
            if (!protocol.androidAvailable) return@setOnClickListener
            if (protocol != selectedProtocol) updateConnectionMode(protocol)
            closeModeScreen()
        }

        val texts = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(protocol.label, 16f, INK, TypefaceStyle.MEDIUM))
        texts.addView(label(protocol.description, 13f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) })

        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) addView(label("CURRENT", 11f, primary, TypefaceStyle.MEDIUM).apply {
            letterSpacing = 0.08f
        }) else if (!protocol.androidAvailable) addView(label("DESKTOP ONLY", 11f, MUTED, TypefaceStyle.MEDIUM).apply {
            letterSpacing = 0.05f
        })
    }
}
