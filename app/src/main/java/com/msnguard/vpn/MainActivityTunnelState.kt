package com.msnguard.vpn

import android.content.Intent
import android.net.VpnService
import android.view.animation.DecelerateInterpolator
import com.msnguard.vpn.MainActivity.Protocol
import com.msnguard.vpn.MainActivity.Companion.VPN_REQUEST
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_PROTOCOL
import com.msnguard.vpn.MainActivity.Companion.ERROR

/**
 * The tunnel state machine as the UI sees it: connect, disconnect, and every
 * paint of the dial, title, LED and session clock that follows from a state
 * change.
 */

internal fun MainActivity.updateConnectionMode(protocol: Protocol) {
    if (selectedProtocol == protocol) return
    selectedProtocol = protocol
    preferences().edit().putString(DEFAULT_PROTOCOL, protocol.coreName).apply()
    // Keep the rail in sync when the change came from somewhere else (the
    // mode screen, a restored preference) rather than from a rail tap.
    transportRail.select(Protocol.entries.indexOf(protocol), animate = true)
    chipProtocol.animate().cancel()
    chipProtocol.animate().alpha(0f).setDuration(80)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            chipProtocol.text = protocol.label.uppercase()
            chipProtocol.animate().alpha(1f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        .start()
}

internal fun MainActivity.toggleTunnel() {
    // Cancelling mid-connect must work. Previously this only asked
    // TunnelStatus.isActive(), which is false while Psiphon is still
    // establishing (tun2socks has not started yet). The tap therefore fell
    // through to the connect path, where the service's
    // connected.compareAndSet(false, true) guard rejected it silently — so
    // the UI sat on "Connecting" until the tunnel came up on its own or the
    // user force-stopped the app.
    if (TunnelStatus.isActive() || visualState == OrbitDialView.State.CONNECTING) {
        startService(Intent(this, MsnGuardVpnService::class.java).setAction(MsnGuardVpnService.ACTION_DISCONNECT))
        showDisconnected("Disconnecting")
        return
    }

    val config = configJson()
    // VPN mode is the only mode, so Android's VPN consent is always required
    // before the service may build a TUN.
    val permissionIntent = VpnService.prepare(this)
    if (permissionIntent == null) connect(config) else {
        pendingConfig = config
        startActivityForResult(permissionIntent, VPN_REQUEST)
    }
}

internal fun MainActivity.connect(config: String) {
    // Clear our mirrors of the core's per-tunnel byte counters before the new
    // tunnel starts. The service broadcasts a zero sample too, but the
    // verification baseline must not depend on that broadcast having been
    // delivered first, and the receiver is only registered while this screen
    // is started.
    trafficTx = 0
    trafficRx = 0
    trafficSpeedTx = 0
    trafficSpeedRx = 0
    showConnecting()
    startForegroundService(Intent(this, MsnGuardVpnService::class.java)
        .setAction(MsnGuardVpnService.ACTION_CONNECT)
        .putExtra(MsnGuardVpnService.EXTRA_CONFIG, config))
}

internal fun MainActivity.configJson(): String = CoreConfig.json(this, selectedProtocol.coreName)

internal fun MainActivity.renderStatus() {
    if (!TunnelStatus.isActive() && isTunnelActive()) {
        NativeCore.lastError().takeIf(String::isNotBlank)?.let { showFailure(it) } ?: showDisconnected("Tunnel stopped unexpectedly")
    }
}

internal fun MainActivity.showConnecting(detail: String? = null) {
    showConnectionProgress("Connecting", detail ?: "Starting ${selectedProtocol.label} tunnel")
}

internal fun MainActivity.showStarting() {
    showConnectionProgress("Starting", "Preparing ${selectedProtocol.label} tunnel")
}

internal fun MainActivity.showScanning() {
    showConnectionProgress("Scanning", "Finding the best MASQUE gateway")
}

internal fun MainActivity.showConnectionProgress(title: String, detail: String) {
    latencyRequest++
    chipLatency.text = "Latency —"
    visualState = OrbitDialView.State.CONNECTING
    orbitDial.state = visualState
    renderStatusLed()
    connectionTitle.setTextColor(primary)
    connectionTitle.text = title
    connectionDetail.text = detail
    footerWave.setLit(false)
    setModeEnabled(false)
}

internal fun MainActivity.showConnected(restored: Boolean = false) {
    visualState = OrbitDialView.State.CONNECTED
    orbitDial.state = visualState
    renderStatusLed()
    pingFailureStreak = 0
    connectionTitle.setTextColor(connected)
    connectionTitle.text = "Connected"
    connectionDetail.text = if (restored) "${selectedProtocol.label} tunnel recovered" else "${selectedProtocol.label} tunnel is active"
    footerWave.setLit(true)
    chipLatency.text = "Latency …"
    startSessionTimer(restored)
    setModeEnabled(false)
    if (!restored) {
        pingConnection()
        startAutoPing()
        refreshPublicIp()
    }
}

internal fun MainActivity.showDegraded() {
    if (!isTunnelActive()) return
    visualState = OrbitDialView.State.DEGRADED
    orbitDial.state = visualState
    renderStatusLed()
    connectionTitle.setTextColor(0xFFFFD180.toInt())
    connectionTitle.text = "Connection degraded"
    connectionDetail.text = "Tunnel is active; HTTP health check failed"
    footerWave.setLit(false)
    chipLatency.text = "Latency n/a"
}

internal fun MainActivity.showFailure(detail: String? = null) {
    latencyRequest++
    cancelVerification()
    // Belongs to the tunnel that just died; keeping it would show a stale exit
    // next to a failure message.
    clearCoreExitIp()
    chipLatency.text = "Latency —"
    visualState = OrbitDialView.State.FAILED
    orbitDial.state = visualState
    renderStatusLed()
    stopSessionTimer()
    connectionTitle.setTextColor(ERROR)
    connectionTitle.text = "Connection failed"
    footerWave.setLit(false)
    connectionDetail.text = detail ?: "Check the server and try again"
    setModeEnabled(true)
}

internal fun MainActivity.showDisconnected(detail: String = "Tap the dial to connect") {
    latencyRequest++
    cancelVerification()
    clearCoreExitIp()
    stopAutoPing()
    chipLatency.text = "Latency —"
    visualState = OrbitDialView.State.DISCONNECTED
    orbitDial.state = visualState
    renderStatusLed()
    stopSessionTimer()
    resetMetrics()
    connectionTitle.setTextColor(INK)
    connectionTitle.text = "Not connected"
    footerWave.setLit(false)
    connectionDetail.text = detail
    setModeEnabled(true)
    refreshPublicIp()
}

/**
 * Session timer. [restored] means the UI reattached to a tunnel that was
 * already up — the service knows when it connected, so ask it rather than
 * restarting the clock at zero and lying to the user.
 */
internal fun MainActivity.startSessionTimer(restored: Boolean) {
    val serviceStart = MsnGuardVpnService.connectedSinceElapsed()
    sessionStartedAt = when {
        serviceStart > 0L -> serviceStart
        sessionStartedAt > 0L && restored -> sessionStartedAt
        else -> android.os.SystemClock.elapsedRealtime()
    }
    sessionHandler.removeCallbacks(sessionTicker)
    sessionHandler.post(sessionTicker)
}

internal fun MainActivity.stopSessionTimer() {
    sessionHandler.removeCallbacks(sessionTicker)
    sessionStartedAt = 0L
    orbitDial.timerText = ""
}

internal fun MainActivity.formatUptime(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

internal fun MainActivity.isTunnelActive(): Boolean = visualState == OrbitDialView.State.CONNECTED ||
    visualState == OrbitDialView.State.DEGRADED
