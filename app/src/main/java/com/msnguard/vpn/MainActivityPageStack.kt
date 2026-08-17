package com.msnguard.vpn

import android.view.View
import android.view.ViewGroup
import com.msnguard.vpn.MainActivity.Companion.PAGE_ANIMATION_MS
import com.msnguard.vpn.MainActivity.Companion.LOG_CLOSE_ANIMATION_MS

/**
 * Page-stack choreography: the open/close transitions shared by every full
 * screen page, and the back-navigation order.
 */

internal fun MainActivity.animatePageOpen(page: View) {
    page.alpha = 0f
    page.translationY = dp(24).toFloat()
    page.scaleX = 0.92f
    page.scaleY = 0.92f

    val behind = if (pageHost.childCount > 1) pageHost.getChildAt(pageHost.childCount - 2) else mainRoot
    behind.animate()
        .alpha(0.5f)
        .scaleX(0.94f)
        .scaleY(0.94f)
        .setDuration(PAGE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()

    page.animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(PAGE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()
}

internal fun MainActivity.animatePageClose(page: View, onEnd: () -> Unit) {
    page.animate()
        .alpha(0f)
        .translationY(dp(24).toFloat())
        .scaleX(0.92f)
        .scaleY(0.92f)
        .setDuration(LOG_CLOSE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .withEndAction {
            if (page.parent == pageHost) pageHost.removeView(page)
            onEnd()
        }
        .start()

    val behind = if (pageHost.childCount > 1) pageHost.getChildAt(pageHost.childCount - 2) else mainRoot
    behind.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(LOG_CLOSE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()
}

internal fun MainActivity.staggerListItems(container: ViewGroup) {
    for (i in 0 until container.childCount) {
        val child = container.getChildAt(i)
        child.alpha = 0f
        child.translationY = dp(12).toFloat()
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(PAGE_ANIMATION_MS)
            .setStartDelay(80L + i * 32L)
            .setInterpolator(motionInterpolator)
            .start()
    }
}

internal fun MainActivity.handleBack(): Boolean {
    when {
        splitTunnelAppsPage != null -> closeSplitTunnelAppsScreen()
        splitTunnelPage != null -> closeSplitTunnelScreen()
        trafficMonitorPage != null -> closeTrafficMonitorScreen()
        tunnelControlsPage != null -> closeTunnelControlsScreen()
        showingLogs -> closeLogsScreen()
        showingScanner -> closeScannerScreen()
        showingMode -> closeModeScreen()
        showingSettings -> closeSettingsScreen()
        else -> return false
    }
    return true
}
