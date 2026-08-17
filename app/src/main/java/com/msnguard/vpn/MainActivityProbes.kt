package com.msnguard.vpn

import android.content.Intent
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import com.msnguard.vpn.MainActivity.Companion.PING_TIMEOUT_MS
import com.msnguard.vpn.MainActivity.Companion.VERIFY_TIMEOUT_MS
import com.msnguard.vpn.MainActivity.Companion.VERIFY_RETRY_DELAY_MS
import com.msnguard.vpn.MainActivity.Companion.BYTE_WATCH_MS
import com.msnguard.vpn.MainActivity.Companion.VERIFY_MIN_RX_BYTES
import com.msnguard.vpn.MainActivity.Companion.MAX_PING_FAILURES
import com.msnguard.vpn.MainActivity.Companion.PING_URLS
import com.msnguard.vpn.MainActivity.Companion.IP_INFO_URLS
import com.msnguard.vpn.MainActivity.Companion.IP_ADDRESS
import com.msnguard.vpn.MainActivity.Companion.COUNTRY_LOOKUP_URLS
import com.msnguard.vpn.MainActivity.Companion.COUNTRY_CODE_JSON
import com.msnguard.vpn.MainActivity.Companion.IP_TIMEOUT_MS
import com.msnguard.vpn.MainActivity.Companion.IP_FETCH_ATTEMPTS
import com.msnguard.vpn.MainActivity.Companion.IP_RETRY_DELAY_MS

/**
 * Reachability, verification and exit-address probing for [MainActivity].
 *
 * Everything here answers one question: is the tunnel really carrying bytes?
 * The screen is not allowed to say Connected until this code proves it.
 */

internal fun MainActivity.startAutoPing() {
    autoPingRunning = true
    autoPingHandler.removeCallbacks(autoPingRunnable)
    autoPingHandler.postDelayed(autoPingRunnable, 5000L)
}

internal fun MainActivity.stopAutoPing() {
    autoPingRunning = false
    autoPingHandler.removeCallbacks(autoPingRunnable)
}

internal fun MainActivity.pingConnection() {
    if (!isTunnelActive() || pingInFlight) return
    pingInFlight = true
    val request = ++latencyRequest
    chipLatency.text = "Latency …"
    Thread {
        // Try each endpoint until one answers. A single unreachable probe URL
        // must not be reported as a degraded tunnel.
        val result: Pair<String, Float?> = pingAnyEndpoint() ?: ("Ping unavailable" to null)
        runOnUiThread {
            pingInFlight = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (request == latencyRequest && isTunnelActive()) {
                chipLatency.text = result.second?.let { "Latency ${it.toInt()} ms" } ?: "Latency n/a"
                val reachable = result.second
                if (reachable != null) {
                    pingFailureStreak = 0
                    if (visualState == OrbitDialView.State.DEGRADED) showConnected(restored = true)
                } else {
                    // A session that stops passing traffic is a dead tunnel,
                    // not a cosmetic "degraded" badge. Show degraded for the
                    // first misses (a carrier hiccup recovers), then stop
                    // pretending and tear it down.
                    pingFailureStreak++
                    if (pingFailureStreak >= MAX_PING_FAILURES) {
                        ConnectionLog.record(
                            "Tunnel stopped passing traffic ($pingFailureStreak consecutive failed probes) — dropping it"
                        )
                        failFakeConnection()
                        return@runOnUiThread
                    }
                    showDegraded()
                }
                updateNotificationHealth(ping = result.first)
            }
        }
    }.start()
}

/**
 * Probe every health-check endpoint in turn, returning the first success.
 *
 * Returns null only when all of them failed, which is the one case that
 * genuinely warrants the degraded state.
 */
internal fun MainActivity.pingAnyEndpoint(): Pair<String, Float>? {
    for (url in PING_URLS) {
        val attempt = runCatching {
            val startedAt = System.nanoTime()
            val connection = openTunnelConnection(url)
            try {
                connection.connectTimeout = PING_TIMEOUT_MS
                connection.readTimeout = PING_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                check(connection.responseCode in 200..399) { "HTTP ${connection.responseCode}" }
                val ms = (System.nanoTime() - startedAt) / 1_000_000
                "${ms} ms" to ms.toFloat()
            } finally {
                connection.disconnect()
            }
        }
        attempt.getOrNull()?.let { return it }
    }
    return null
}

/**
 * Waits for the core's own byte counters to move, which is the only signal
 * on this screen that is measured *inside* the tunnel.
 *
 * Needed because in native TUN mode our own package is excluded from the VPN
 * (otherwise the core's control sockets would route into their own tunnel),
 * so an HTTP probe from this process leaves over the carrier link and
 * succeeds even when the tunnel carries nothing. That is exactly how a dead
 * Hamrah-e-Aval WireGuard session produced "Reachability probe passed in 1
 * attempt — 479 ms" followed by zero bytes. A probe that cannot enter the
 * tunnel cannot be evidence about the tunnel.
 *
 * Returns true as soon as [trafficRx] exceeds [rxAtStart].
 */
internal fun MainActivity.awaitTunnelBytes(request: Int, rxAtStart: Long, deadline: Long): Boolean {
    // Not "> 0": the core's own WireGuard health probe sends a small DNS query
    // every few seconds and its reply crosses the TUN, so a completely dead
    // tunnel still drips a few hundred bytes. That drip is what made the
    // counters show a trickle while nothing loaded. Requiring
    // [VERIFY_MIN_RX_BYTES] puts the bar above the probe traffic and below
    // anything a real app does on connect.
    var base = rxAtStart
    var target = base + VERIFY_MIN_RX_BYTES
    while (System.currentTimeMillis() < deadline && request == verifyRequest) {
        // The counter going backwards means the core started a fresh tunnel
        // (its totals are per-tunnel locals) — its own reconnect loop can do
        // that mid-verification. Re-baseline instead of waiting out the
        // deadline against a target the new counter can never reach.
        if (trafficRx < base) {
            base = trafficRx
            target = base + VERIFY_MIN_RX_BYTES
        }
        if (trafficRx >= target) return true
        Thread.sleep(VERIFY_RETRY_DELAY_MS)
    }
    return trafficRx >= target
}

/**
 * Gate between "the transport says it is up" and "the UI says Connected".
 *
 * Why this exists: on Hamrah-e-Aval a WireGuard handshake completes but no
 * payload ever crosses. The service broadcast CONNECTED, the dial went green,
 * and the counters sat at a few bytes while Telegram and every site stayed
 * dark — a connection that is connected to nothing. A handshake is not a data
 * plane, so it is not allowed to paint Connected on its own.
 *
 * The gate is a real HTTP fetch pulled through the tunnel, retried for up to
 * [VERIFY_TIMEOUT_MS]. Pass → Connected. Fail → tear the tunnel down and say
 * so, instead of leaving the user on a dead green dial. Protocol-agnostic on
 * purpose: it verifies bytes, so it covers WireGuard, MASQUE, WoW and
 * Psiphon without per-protocol special cases.
 */
internal fun MainActivity.beginVerification() {
    // Already verified and live: a Psiphon rotation and the native core's own
    // reconnect loop both re-broadcast CONNECTED mid-session, and neither must
    // restart the whole gate. DEGRADED counts as live — the auto-ping owns
    // recovery from there.
    if (visualState == OrbitDialView.State.CONNECTED ||
        visualState == OrbitDialView.State.DEGRADED
    ) return
    if (verifyInFlight) return
    verifyInFlight = true
    val request = ++verifyRequest
    // Byte counter at the moment the transport claimed to be up. The probe
    // below cannot see the tunnel in native mode (see pingAnyEndpoint: our
    // package is disallowed on the TUN, so the request leaves over the
    // carrier link), but this counter is emitted by the core from inside the
    // TUN bridge and therefore cannot be faked by the carrier.
    val rxAtStart = trafficRx
    showVerifying()
    Thread {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        // In native TUN mode the HTTP probe rides the carrier link, not the
        // tunnel, so it proves nothing. Gate on in-tunnel bytes instead and
        // use the probe only for the latency figure afterwards.
        val nativeMode = TunnelStatus.isNativeTunMode
        if (nativeMode) {
            val moved = awaitTunnelBytes(request, rxAtStart, deadline)
            runOnUiThread {
                verifyInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (request != verifyRequest) return@runOnUiThread
                if (moved) {
                    ConnectionLog.record("Tunnel is passing traffic — verified from inside the tunnel")
                    showConnected()
                    // Latency is cosmetic, so a failure here must not undo a
                    // verification that already succeeded on real bytes.
                    Thread {
                        val probe = pingAnyEndpoint()
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (request != verifyRequest) return@runOnUiThread
                            probe?.let { chipLatency.text = "Latency ${it.second.toInt()} ms" }
                        }
                    }.start()
                } else {
                    ConnectionLog.record(
                        "Tunnel moved no bytes in ${VERIFY_TIMEOUT_MS / 1000}s — handshake succeeded but nothing passes"
                    )
                    failFakeConnection()
                }
            }
            return@Thread
        }
        var proof: Pair<String, Float>? = null
        var attempts = 0
        while (System.currentTimeMillis() < deadline && request == verifyRequest) {
            attempts++
            proof = pingAnyEndpoint()
            if (proof != null) break
            // The tunnel may still be settling (routes, DNS, lwIP warm-up),
            // so retry rather than failing on the first miss.
            Thread.sleep(VERIFY_RETRY_DELAY_MS)
        }
        val verified = proof
        runOnUiThread {
            verifyInFlight = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (request != verifyRequest) return@runOnUiThread
            if (verified != null) {
                ConnectionLog.record("Reachability probe passed in $attempts attempt(s) — ${verified.first}")
                showConnected()
                chipLatency.text = "Latency ${verified.second.toInt()} ms"
                // Second opinion, from inside the tunnel. A probe that rode
                // the carrier link proves nothing about the TUN, so if the
                // core has still not moved a single byte after the settle
                // window, do not leave the user on a confident green dial.
                watchForTunnelBytes(request, rxAtStart)
            } else {
                ConnectionLog.record(
                    "No reachability after $attempts probe(s) — treating the tunnel as dead"
                )
                failFakeConnection()
            }
        }
    }.start()
}

/**
 * Watches the core's own byte counters after the UI has gone green.
 *
 * The counters come from [core] tun.rs, which increments them as packets
 * cross the TUN fd — the one number on this screen that is measured inside
 * the tunnel. If it has not budged [BYTE_WATCH_MS] after connect, the tunnel
 * is carrying nothing regardless of what the handshake said, and the dial
 * drops to DEGRADED with a message that says so. It is not torn down: the
 * core's own 10s stale-timeout owns teardown, and killing a tunnel that is
 * merely idle would be worse than labelling it.
 */
internal fun MainActivity.watchForTunnelBytes(request: Int, rxAtStart: Long) {
    sessionHandler.postDelayed({
        if (isFinishing || isDestroyed) return@postDelayed
        if (request != verifyRequest) return@postDelayed
        if (visualState != OrbitDialView.State.CONNECTED) return@postDelayed
        if (trafficRx > rxAtStart) return@postDelayed
        ConnectionLog.record("Tunnel moved no bytes in ${BYTE_WATCH_MS / 1000}s — reporting degraded")
        visualState = OrbitDialView.State.DEGRADED
        orbitDial.state = OrbitDialView.State.DEGRADED
        connectionTitle.setTextColor(palette.amber)
        connectionDetail.text = "Tunnel is up but no traffic is passing"
        renderStatusLed()
    }, BYTE_WATCH_MS)
}

/** Cancels an in-flight verification (user disconnect, real failure, stop). */
internal fun MainActivity.cancelVerification() {
    verifyRequest++
    verifyInFlight = false
}

/**
 * A handshake-only tunnel: tear it down and report it honestly.
 *
 * Leaving it running would keep the TUN installed and silently blackhole the
 * whole device, which is worse than being disconnected.
 */
internal fun MainActivity.failFakeConnection() {
    suppressNextDisconnectedPaint = true
    startService(Intent(this, MsnGuardVpnService::class.java)
        .setAction(MsnGuardVpnService.ACTION_DISCONNECT))
    showFailure("Tunnel handshake succeeded but no traffic passes — try another protocol")
}

/** The state between handshake and proof. Keeps the dial in its CONNECTING look. */
internal fun MainActivity.showVerifying() {
    showConnectionProgress("Verifying", "Checking that traffic really passes")
}

internal fun MainActivity.refreshPublicIp() {
    // The core already told us, from inside the tunnel. Nothing an HTTP
    // request from this process could add is more accurate.
    if (coreExitIp.isNotBlank()) {
        ipRequest++
        exitNodeCard.render(
            coreExitIp,
            coreExitCountry.takeIf { it.isNotBlank() },
            isTunnelActive(),
        )
        // A tap on the card with the country still missing should retry it.
        if (coreExitCountry.isBlank()) resolveExitCountry(coreExitIp)
        return
    }
    // Native TUN mode and no measurement yet: our own request would leave over
    // the carrier link and paint the carrier's country. Wait for the core
    // instead of showing a number we know to be wrong.
    if (TunnelStatus.isActive() &&
        TunnelStatus.isNativeTunMode &&
        !Tun2SocksManager.isRunning
    ) {
        ipRequest++
        exitNodeCard.render("", null, isTunnelActive(), measuring = true)
        return
    }
    if (ipRefreshInFlight) {
        ipRefreshPending = true
        return
    }
    ipRefreshInFlight = true
    val request = ++ipRequest
    exitNodeCard.render("", null, isTunnelActive())
    Thread {
        val result = runCatching {
            repeat(IP_FETCH_ATTEMPTS) { attempt ->
                runCatching { fetchPublicIp() }.getOrNull()?.let { return@runCatching it }
                if (attempt + 1 < IP_FETCH_ATTEMPTS) Thread.sleep(IP_RETRY_DELAY_MS)
            }
            error("IP unavailable")
        }
        runOnUiThread {
            ipRefreshInFlight = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (ipRefreshPending) {
                ipRefreshPending = false
                refreshPublicIp()
                return@runOnUiThread
            }
            if (request != ipRequest) return@runOnUiThread
            val (ip, country) = result.getOrElse { "IP unavailable" to "" }
            exitNodeCard.render(ip, country, isTunnelActive())
            if (isTunnelActive() && ip != "IP unavailable") updateNotificationHealth(ip = ip)
            exitNodeCard.alpha = 0.45f
            exitNodeCard.animate().alpha(1f).setDuration(240)
                .setInterpolator(motionInterpolator).start()
        }
    }.start()
}

internal fun MainActivity.fetchPublicIp(): Pair<String, String> {
    var failure: Throwable? = null
    for (url in IP_INFO_URLS) {
        try {
            val connection = openTunnelConnection(url)
            try {
                connection.connectTimeout = IP_TIMEOUT_MS
                connection.readTimeout = IP_TIMEOUT_MS
                connection.requestMethod = "GET"
                check(connection.responseCode in 200..399) { "HTTP ${connection.responseCode}" }
                val body = connection.inputStream.bufferedReader().use { it.readText().trim() }
                val values = body.lineSequence().mapNotNull { line -> line.split('=', limit = 2).let { pair ->
                    pair.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                } }.toMap()
                val ip = values["ip"] ?: body.takeIf { it.matches(IP_ADDRESS) }.orEmpty()
                check(ip.isNotBlank()) { "IP unavailable" }
                return ip to values["loc"].orEmpty()
            } finally {
                connection.disconnect()
            }
        } catch (error: Throwable) {
            failure = error
        }
    }
    throw failure ?: IllegalStateException("IP unavailable")
}

internal fun MainActivity.openTunnelConnection(url: String): HttpURLConnection {
    // OURS, deliberately kept over upstream's
    // `PROXY mode && NativeCore.isRunning()` (proxy mode has since been removed).
    //
    // In Psiphon VPN mode the service calls addDisallowedApplication(packageName),
    // so our own process is excluded from the TUN and its traffic leaves over
    // the carrier link. Upstream's condition would skip the proxy in VPN mode
    // and the IP/ping checks would report the real Iranian IP instead of the
    // tunnel exit. Routing through the local SOCKS port whenever any tunnel is
    // active is what makes the header IP and flag correct.
    //
    // But a local SOCKS listener only exists when something is actually
    // listening. Psiphon VPN mode has one (port 1819, which tun2socks also
    // dials). WireGuard and MASQUE VPN mode do NOT: the Rust core takes the
    // `tun_fd` branch in main.rs and binds a TUN bridge instead of calling
    // socks::serve, which only runs in the `else` (proxy) branch. Sending the
    // health check to 127.0.0.1:1819 there gets connection-refused on every
    // poll, pingConnection() lands in its `?: showDegraded()` arm, and the UI
    // says "Connection degraded" while the tunnel is carrying traffic fine.
    //
    // So: use the proxy only when a proxy is really there. In native VPN mode
    // go direct.
    //
    // LIMIT OF THIS PROBE — read before trusting it. applySplitTunneling()
    // calls addDisallowedApplication(packageName) on the native path too, so
    // this request leaves over the CARRIER link, not the tunnel. It therefore
    // proves the phone has internet; it does not prove the tunnel carries
    // anything. That is exactly how a WireGuard session with a completed
    // handshake and a dead data plane still passed the gate.
    //
    // The exclusion cannot simply be dropped: on the native path the core
    // provisions its identity (account.rs, plain reqwest, unprotected
    // sockets) *after* establish(), so those calls would be routed into a TUN
    // whose tunnel does not exist yet and connect would deadlock.
    //
    // So the honest signal is elsewhere: watchForTunnelBytes() reads the byte
    // counters the core emits from inside the TUN bridge. Keep both.
    val useSocksProxy = TunnelStatus.isActive() &&
        // tun2socks is up, which only happens in Psiphon VPN mode, and it
        // implies a live SOCKS listener on this port. Otherwise the Rust core
        // is running: it only has a SOCKS listener in proxy mode, never when
        // it is driving a TUN directly.
        (Tun2SocksManager.isRunning || !TunnelStatus.isNativeTunMode)

    val target = URL(url)
    val connection = if (useSocksProxy) {
        target.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort())))
    } else {
        target.openConnection()
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = IP_TIMEOUT_MS
        readTimeout = IP_TIMEOUT_MS
    }
}

internal fun MainActivity.updateNotificationHealth(ip: String? = null, ping: String? = null) {
    if (!TunnelStatus.isActive()) return
    startService(Intent(this, MsnGuardVpnService::class.java)
        .setAction(MsnGuardVpnService.ACTION_NOTIFICATION_HEALTH)
        .apply {
            ip?.let { putExtra(MsnGuardVpnService.EXTRA_NOTIFICATION_IP, it) }
            ping?.let { putExtra(MsnGuardVpnService.EXTRA_NOTIFICATION_PING, it) }
        })
}

/**
 * Forgets the core's exit measurement, so the next tunnel measures afresh.
 *
 * Called on failure and disconnect. Without this a reconnect through a
 * different endpoint would keep showing the previous exit until the new
 * measurement lands, which is the same class of lie this change removes.
 */
internal fun MainActivity.clearCoreExitIp() {
    coreExitIp = ""
    coreExitCountry = ""
    countryRequest++
}

/**
 * Resolves which country [ip] is in, and repaints the card when it lands.
 *
 * Why this is a separate HTTP call rather than part of the core's in-tunnel
 * measurement: the core can only ask DNS, and DNS exposes the RIR
 * *registration* country, not a geolocation. Those disagree badly here —
 * 104.28.214.161 is registered to ARIN in the US and geolocates to Tehran,
 * and its neighbours in the same /24 sit in PT, CA, GB and CO — because
 * Cloudflare hands out anycast egress addresses per user, not per region.
 *
 * Unlike the address itself, this question is safe to ask over any link: the
 * answer is a property of [ip], not of the route the query takes. Both
 * endpoints are Cloudflare-fronted, so they are reachable from Iran, and both
 * were verified returning IR for the two addresses above.
 */
internal fun MainActivity.resolveExitCountry(ip: String) {
    val request = ++countryRequest
    Thread {
        val country = runCatching { fetchCountryFor(ip) }.getOrNull()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // A newer measurement (or a disconnect) superseded this lookup.
            if (request != countryRequest || coreExitIp != ip) return@runOnUiThread
            if (country.isNullOrBlank()) return@runOnUiThread
            coreExitCountry = country
            exitNodeCard.render(ip, country, isTunnelActive())
        }
    }.start()
}

/** Two-letter country code for [ip], or null when no endpoint answers. */
internal fun MainActivity.fetchCountryFor(ip: String): String? {
    for (template in COUNTRY_LOOKUP_URLS) {
        try {
            val connection = (URL(template.format(ip)).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = IP_TIMEOUT_MS
                    readTimeout = IP_TIMEOUT_MS
                    requestMethod = "GET"
                }
            try {
                if (connection.responseCode !in 200..299) continue
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                COUNTRY_CODE_JSON.find(body)?.groupValues?.get(1)?.let { return it.uppercase() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            // Try the next endpoint.
        }
    }
    return null
}
