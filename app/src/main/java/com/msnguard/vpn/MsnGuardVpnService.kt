package com.msnguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tunnel's lifecycle owner: foreground notification, TUN interface, Psiphon
 * controller, escalation ladder and traffic accounting.
 *
 * Three things that used to live here now do not, because none of them are about
 * lifecycle:
 *
 *  * [PsiphonLadder]        — the rung definitions and their carrier tuning
 *  * [applyDns] and friends — everything that shapes the TUN (VpnInterfaceBuilder.kt)
 *  * [ConnectionLog]        — the app-wide event buffer, used by four callers
 *  * [ByteFormat]           — counter formatting
 */
class MsnGuardVpnService : VpnService(), NativeCore.CoreCallback, PsiphonTunnel.HostService {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val connected = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val vpnModeActive = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var lastTrafficSampleMs = 0L
    private var currentTx = 0L
    private var currentRx = 0L
    private var prevTx = 0L
    private var prevRx = 0L
    private var prevSpeedSampleMs = 0L
    private var currentSpeedTx = 0L
    private var currentSpeedRx = 0L
    private var accountedTx = 0L
    private var accountedRx = 0L

    /**
     * Monthly totals held in memory, flushed to disk on a timer.
     *
     * These used to be written through to SharedPreferences on every traffic
     * sample, i.e. roughly once a second for the whole life of a tunnel. That is
     * thousands of `apply()` calls an hour, each one a disk write behind the
     * scenes — expensive on flash and on battery, to persist a counter nobody
     * reads until the traffic screen is opened.
     *
     * Now the counters live here and reach disk every [TRAFFIC_FLUSH_MS] and on
     * teardown. Worst case a hard process kill loses the last few seconds of
     * accounting, which is not a number anything depends on being exact.
     */
    private var monthKey: String? = null
    private var monthTxTotal = 0L
    private var monthRxTotal = 0L
    private var lastTrafficFlushMs = 0L
    private var lastNotificationUpdateMs = 0L
    private var storedConfig: String? = null
    private var currentProtocol = "Tunnel"
    private var currentVpnIp = ""
    private var currentPing = ""
    private var psiphonTunnel: PsiphonTunnel? = null
    private var psiphonConfigJson: String = ""

    /**
     * True for the whole life of a Psiphon session, false for every other
     * protocol.
     *
     * MUST be reassigned on every [startTunnel]. It used to be set to true on the
     * Psiphon branch and never cleared, so after one Psiphon session every
     * subsequent MASQUE / WireGuard / WoW disconnect took the Psiphon teardown
     * path in [stopTunnel] — closing the TUN and calling stopSelf() while the
     * native worker's own `finally` block was about to do the same.
     */
    private var psiphonVpnMode = false
    private var psiphonVpnActivated = false
    private var activeSocksPort = 0

    // Evidence about how the tunnel was actually established, gathered from
    // Psiphon's own notices rather than inferred from which rung was active.
    private var activeTunnelProtocol = ""
    private var inproxyInUse = false

    // --- Psiphon escalation ladder state ---
    // A hostile carrier (Hamrah-e-Aval) null-routes Psiphon's server IPs, so the
    // first rung of the ladder will time out. Rather than sitting on one config
    // for two minutes and giving up, we walk the ladder automatically: each rung
    // gets its own budget, and a timeout promotes us to the next rung without
    // any user interaction. The rungs themselves live in PsiphonLadder.kt.
    private val ladderScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val psiphonLadder: List<PsiphonStrategy> = PsiphonLadder.RUNGS
    private var ladderIndex = 0
    private var ladderAttempts = 0
    private var ladderTimer: ScheduledFuture<*>? = null
    private val ladderActive = AtomicBoolean(false)
    private val attributionPending = AtomicBoolean(false)

    companion object {
        const val LOG_TAG = "MsnGuardVpnService"
        const val ACTION_CONNECT = "com.msnguard.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.msnguard.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.msnguard.vpn.RECONNECT"
        const val ACTION_NOTIFICATION_HEALTH = "com.msnguard.vpn.NOTIFICATION_HEALTH"
        const val ACTION_STATUS = "com.msnguard.vpn.STATUS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TRAFFIC_TX = "traffic_tx"
        const val EXTRA_TRAFFIC_RX = "traffic_rx"
        const val EXTRA_TRAFFIC_SPEED_TX = "traffic_speed_tx"
        const val EXTRA_TRAFFIC_SPEED_RX = "traffic_speed_rx"
        const val EXTRA_TRAFFIC_MONTH_TX = "traffic_month_tx"
        const val EXTRA_TRAFFIC_MONTH_RX = "traffic_month_rx"
        const val EXTRA_NOTIFICATION_IP = "notification_ip"
        const val EXTRA_NOTIFICATION_PING = "notification_ping"
        /** Exit address measured by the core from inside the tunnel. */
        const val EXTRA_EXIT_IP = "exit_ip"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STARTING = "starting"
        const val STATUS_SCANNING = "scanning"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_FAILED = "failed"
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
        const val TRAFFIC_PREFS = "traffic_stats"
        const val TRAFFIC_MONTH = "month"
        const val TRAFFIC_TX = "tx"
        const val TRAFFIC_RX = "rx"

        /**
         * How often the monthly traffic counters are written to disk while a
         * tunnel is up. Teardown always flushes, so this only bounds what a hard
         * process kill can lose.
         */
        private const val TRAFFIC_FLUSH_MS = 60_000L

        /**
         * How often the ongoing notification's byte counters are refreshed. The
         * core reports traffic about once a second; repainting the shade that
         * often is visually indistinguishable and measurably more expensive.
         */
        private const val NOTIFICATION_UPDATE_MS = 5_000L

        /** Minimum gap between traffic samples we act on, in ms. */
        private const val TRAFFIC_SAMPLE_MIN_MS = 900L

        /** Grace added on top of a rung's own budget before the watchdog fires. */
        private const val LADDER_GRACE_SECONDS = 8L

        /**
         * elapsedRealtime at the moment the tunnel last reached CONNECTED, or 0
         * when it is down. The activity reads this so a session timer survives
         * the UI being destroyed and recreated (rotation, screen off, returning
         * from Recents) instead of restarting from zero on every rebind.
         *
         * Volatile and static because the service and the activity are different
         * lifecycles in the same process; it is a plain timestamp, so a stale
         * read is harmless.
         */
        @Volatile
        private var connectedSince = 0L

        fun connectedSinceElapsed(): Long = connectedSince
    }

    // ---------------------------------------------------------------- Psiphon

    override fun bindToDevice(fd: Long) {
        if (!protect(fd.toInt())) {
            throw PsiphonTunnel.Exception("protect(fd=$fd) failed")
        }
    }

    override fun onListeningSocksProxyPort(port: Int) {
        activeSocksPort = port
        ConnectionLog.record("Psiphon SOCKS proxy listening on port $port")
    }

    override fun onConnecting() {
        ConnectionLog.record("Psiphon connecting")
    }

    override fun onConnected() {
        ConnectionLog.record("Psiphon connected — upstream tunnel ready")
        // A tunnel exists: disarm the watchdog so it cannot tear down a working
        // connection.
        ladderActive.set(false)
        cancelLadderTimer()
        ladderAttempts = 0
        // Attribution is NOT done here. The ActiveTunnel notice that names the
        // protocol arrives *after* this callback — both field logs show it one
        // line below "Psiphon connected" — so at this point activeTunnelProtocol
        // is still empty and any decision would be a guess. See
        // scheduleLadderAttribution() for the deferred, evidence-based version.
        scheduleLadderAttribution()

        val port = activeSocksPort
        if (port <= 0) {
            sendStatus(STATUS_FAILED, "Psiphon SOCKS port unavailable")
            return
        }
        val socksProxy = "127.0.0.1:$port"

        if (!psiphonVpnMode) {
            // PROXY MODE: just expose the SOCKS port — no TUN needed.
            ConnectionLog.record("Psiphon SOCKS proxy ready at $socksProxy")
            sendStatus(STATUS_CONNECTED)
            return
        }

        // VPN MODE: TUN is already up (created in startTunnel() before Psiphon
        // started). tun2socks keeps running across Psiphon rotations — the SOCKS
        // port is fixed, so a rotation only breaks in-flight upstream sockets and
        // lwIP resets those individual flows while the TUN device stays up.
        if (psiphonVpnActivated && Tun2SocksManager.isRunning) {
            ConnectionLog.record("Psiphon reconnected — tun2socks still routing, nothing to do")
            sendStatus(STATUS_CONNECTED)
            return
        }

        val tunFd = tun
        if (tunFd == null) {
            sendStatus(STATUS_FAILED, "VPN interface missing")
            return
        }

        if (!Tun2SocksManager.start(tunFd, port)) {
            sendStatus(STATUS_FAILED, "Could not start whole-device routing")
            return
        }
        psiphonVpnActivated = true
        ConnectionLog.record("Whole-device routing active via tun2socks → $socksProxy")
        connected.set(true)
        // Replace the placeholder "Connecting..." notification immediately. It used
        // to be overwritten by the first traffic sample from the Rust core; with
        // tun2socks the first sample can be seconds away, so the notification
        // would sit on "Connecting..." while the device was fully tunnelled.
        postNotification()
        sendStatus(STATUS_CONNECTED)
    }

    override fun onExiting() {
        ConnectionLog.record("Psiphon exiting")
        // The Go controller has already unwound by the time this fires, so the
        // handle is dead: drop it rather than calling stop() on it again.
        psiphonTunnel = null
        // Psiphon hit its own EstablishTunnelTimeout and shut the controller down.
        // That is the definitive "this rung is dead" signal, and it arrives before
        // our watchdog's grace period expires — so escalate now instead of leaving
        // the user staring at a stalled spinner for another 8 seconds.
        // Guarded: a user-initiated stop also lands here, and so does a teardown
        // that follows a successful connection.
        if (!stopRequested.get() && !psiphonVpnActivated && ladderActive.get()) {
            escalateLadder()
        }
    }

    override fun onClientAddress(address: String?) {
        if (!address.isNullOrBlank()) {
            ConnectionLog.record("Psiphon exit IP: $address")
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("last_ip", address).apply()
        }
    }

    override fun onHomepage(homepage: String?) {
        ConnectionLog.record("Psiphon homepage: ${homepage ?: "—"}")
    }

    override fun onClientRegion(region: String?) {
        if (!region.isNullOrBlank()) ConnectionLog.record("Psiphon region: $region")
    }

    override fun onBytesTransferred(sent: Long, received: Long) {
        // In VPN mode there is no Rust core in the data path anymore, so Psiphon's
        // own byte counters are the source of traffic stats. These arrive as
        // deltas, not totals.
        if (!psiphonVpnMode) return
        currentTx += sent
        currentRx += received
        updateTrafficNotification(currentTx, currentRx)
    }

    override fun onDiagnosticMessage(message: String) {
        ConnectionLog.record("Psiphon: $message")
        // Capture the protocol that actually carried the tunnel.
        //
        // InitialLimitTunnelProtocols is a preference, not a constraint: once the
        // candidate budget is spent Psiphon reverts to its full protocol set. Both
        // reported field logs proved this — Hamrah-e-Aval ended on
        // FRONTED-MEEK-OSSH (rung A's protocol) while rung B was active, and
        // SamanTel ended on plain OSSH with an in-proxy broker (rung C's mechanism)
        // while rung B was active. Attributing the win to the active rung was
        // therefore wrong in both cases, and persisting that wrong rung meant the
        // next connect started from a strategy that had not actually worked.
        if (message.startsWith("ActiveTunnel:")) {
            runCatching {
                val protocol = JSONObject(message.substringAfter("ActiveTunnel:").trim())
                    .optString("protocol")
                if (protocol.isNotBlank()) activeTunnelProtocol = protocol
            }
        }
        // An in-proxy broker selection is decisive evidence the peer-relay path is
        // in play, regardless of which OSSH variant rides on top of it.
        if (message.contains("inproxy: selected broker")) {
            inproxyInUse = true
        }
    }

    override fun getContext(): android.content.Context = this

    override fun getPsiphonConfig(): String = psiphonConfigJson

    private fun buildPsiphonConfig(): String {
        // Fixed port so the TUN can be pre-created before Psiphon starts.
        val socksPort = CoreConfig.SOCKS_PORT
        val config = JSONObject().apply {
            put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            put("SponsorId", "1111111111111111")
            put("EgressRegion", "")
            put("EstablishTunnelTimeoutSeconds", 120)
            put("DataDirectory", filesDir.absolutePath)
            put("ClientVersion", "1")
            put("TunnelProtocol", "")
            put("RemoteServerListURL", "")
            put("LocalSocksProxyPort", socksPort)
            put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
            put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
            put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
            // Required for onBytesTransferred() to ever fire. Psiphon suppresses
            // the BytesTransferred notice unless this is set, and in VPN mode
            // Psiphon's counters are the ONLY traffic source now that the Rust
            // core is out of the data path — without it the notification stays
            // stuck on "Connecting..." forever and data usage reads 0.
            put("EmitBytesTransferred", true)
            // --- Anti-censorship tuning for restrictive ISPs (e.g. Hamrah-e-Aval) ---
            // Tell Psiphon the user is in Iran so Iran-specific Tactics (protocol
            // selection, padding, server prioritization) are downloaded and applied.
            put("DeviceRegion", "IR")
            // More concurrent connection attempts = higher probability of finding
            // a server/protocol that survives DPI on restrictive networks.
            put("ConnectionWorkerPoolSize", 12)
            // Emit detailed diagnostic notices so we can see exactly which
            // protocols/servers fail on which carriers.
            put("EmitDiagnosticNotices", true)
            // Note: "DisableNetworkManager" was tried here and is a no-op — the
            // key does not exist in libgojni.so (verified with strings). Psiphon's
            // NetworkMonitor still restarts the tunnel when tun0 appears. That is
            // survivable now: tun2socks holds the TUN fd and the SOCKS port is
            // fixed, so a rotation only kills in-flight upstream sockets and lwIP
            // resets those flows individually instead of dropping the interface.
        }

        // Apply the current rung of the escalation ladder. Each rung overrides
        // protocol selection and worker-pool sizing on top of the base config,
        // and owns the establish timeout so a dead rung is abandoned quickly
        // instead of burning the full two minutes.
        val strategy = psiphonLadder.getOrNull(ladderIndex)
        if (strategy != null) {
            strategy.configure(config)
            config.put("EstablishTunnelTimeoutSeconds", strategy.timeoutSeconds)
            ConnectionLog.record(
                "Strategy ${ladderIndex + 1}/${psiphonLadder.size} " +
                    "(${strategy.name}): ${strategy.label} — ${strategy.timeoutSeconds}s budget"
            )
        }
        return config.toString()
    }

    private fun startPsiphonTunnel() {
        try {
            // Clear evidence from any previous rung: attribution must reflect this
            // attempt only, otherwise a protocol notice from a failed rung would
            // be credited to whichever rung eventually connects.
            activeTunnelProtocol = ""
            inproxyInUse = false
            val tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            // Always SOCKS mode — in VPN mode we bridge TUN→SOCKS ourselves.
            tunnel.setVpnMode(false)
            psiphonTunnel = tunnel
            psiphonConfigJson = buildPsiphonConfig()

            // Hex-encoded server entries bundled in assets.
            val serverEntries = try {
                assets.open("server_entries.txt").bufferedReader().readText().trim()
            } catch (e: Exception) {
                ConnectionLog.record("No server_entries.txt in assets: ${e.message}")
                ""
            }
            // Fire-and-forget: Psiphon connects asynchronously.
            // onListeningSocksProxyPort() saves the port, onConnected() starts
            // tun2socks.
            tunnel.startTunneling(serverEntries)
            ConnectionLog.record("Psiphon tunnel starting...")
            armLadderTimer()
        } catch (e: Exception) {
            ConnectionLog.record("Psiphon start failed: ${e.message}")
            activeSocksPort = 0
        }
    }

    /**
     * Arm the watchdog for the current rung.
     *
     * Psiphon's own EstablishTunnelTimeout fires inside the Go core and shuts the
     * controller down without telling us which rung failed, so we keep our own
     * timer with a small grace period on top. Whichever fires first, the effect
     * is the same: [escalateLadder] moves to the next rung.
     */
    private fun armLadderTimer() {
        val strategy = psiphonLadder.getOrNull(ladderIndex) ?: return
        cancelLadderTimer()
        ladderActive.set(true)
        // Grace so Psiphon's internal timeout and teardown land first; racing it
        // would restart the tunnel while the old controller is still stopping.
        val budget = strategy.timeoutSeconds.toLong() + LADDER_GRACE_SECONDS
        ladderTimer = ladderScheduler.schedule({
            if (ladderActive.get() && !psiphonVpnActivated) escalateLadder()
        }, budget, TimeUnit.SECONDS)
    }

    private fun cancelLadderTimer() {
        ladderTimer?.cancel(false)
        ladderTimer = null
    }

    /**
     * Defer winner attribution until Psiphon has reported the live protocol.
     *
     * Ordering in the real logs, both carriers, is always:
     *
     *     Psiphon connected — upstream tunnel ready   <- onConnected()
     *     Tunnels: {"count":1}
     *     ActiveTunnel: {"protocol":"FRONTED-MEEK-HTTP-OSSH"}   <- the evidence
     *
     * so reading the protocol inside onConnected() always saw an empty string
     * and fell through to "keep the active rung". That is precisely the wrong
     * answer in the interesting cases: Hamrah-e-Aval was credited to A while
     * rung A was active only by luck, and SamanTel was credited to C purely on a
     * background broker notice while the tunnel was direct QUIC-OSSH.
     *
     * A short delay is enough — the notice follows within milliseconds — and the
     * whole thing is best-effort: if nothing arrives we keep the active rung,
     * which is the old behaviour.
     */
    private fun scheduleLadderAttribution() {
        if (!attributionPending.compareAndSet(false, true)) return
        val rungAtConnect = ladderIndex
        ladderScheduler.schedule({
            attributionPending.set(false)
            if (!stopRequested.get()) recordLadderWinner(rungAtConnect)
        }, 2, TimeUnit.SECONDS)
    }

    /**
     * Persist the rung that genuinely produced the tunnel.
     *
     * Attribution is by *evidence*, in order of how conclusive it is:
     *
     *  1. An in-proxy broker was selected -> rung C, whatever protocol rode on
     *     top. SamanTel connected with plain "OSSH" but the log also showed
     *     "inproxy: selected broker", so protocol alone would have mislabelled it.
     *  2. The live ActiveTunnel protocol matches exactly one rung's preferred
     *     list -> that rung. Hamrah-e-Aval ended on FRONTED-MEEK-OSSH, which is
     *     rung A's signature.
     *  3. The protocol appears in several rungs' lists (FRONTED-MEEK-OSSH is in
     *     all three) -> keep the rung that was active, since it is consistent
     *     with the evidence and switching on ambiguity would just add churn.
     *  4. No protocol notice arrived at all -> keep the active rung.
     *
     * Getting this right matters because the stored value decides where the next
     * connect *starts*: a wrong entry costs the user a full rung timeout before
     * the ladder stumbles onto the path that already worked on their carrier.
     */
    private fun recordLadderWinner(rungAtConnect: Int) {
        val protocol = activeTunnelProtocol
        val inproxyRung = PsiphonLadder.INPROXY_RUNG

        val (winnerIndex, reason) = when {
            // The protocol name is the strongest signal available. An INPROXY-*
            // tunnel is unambiguously the peer-relay rung.
            protocol.startsWith("INPROXY") && inproxyRung >= 0 ->
                inproxyRung to "in-proxy protocol $protocol"

            protocol.isNotBlank() -> {
                val matches = psiphonLadder.indices.filter { i ->
                    psiphonLadder[i].preferredProtocols.contains(protocol)
                }
                when {
                    matches.size == 1 -> matches[0] to "protocol $protocol is unique to this strategy"
                    matches.contains(rungAtConnect) -> rungAtConnect to "protocol $protocol consistent with active strategy"
                    matches.isNotEmpty() -> matches[0] to "protocol $protocol best match"
                    else -> rungAtConnect to "protocol $protocol not in any preference list; keeping active strategy"
                }
            }

            // Only fall back to broker evidence when no protocol was reported.
            // "inproxy: selected broker" is NOT proof the tunnel used a peer
            // relay: the SamanTel log shows that notice arriving while the
            // established tunnel was plain direct QUIC-OSSH, because the
            // in-proxy machinery keeps negotiating in the background. Crediting
            // rung C there would have pinned that SIM to the slowest rung (75s)
            // when the direct rung connects in seconds.
            inproxyInUse && inproxyRung >= 0 ->
                inproxyRung to "in-proxy broker in use, no protocol notice"

            else -> rungAtConnect to "no protocol notice; keeping active strategy"
        }

        val winner = psiphonLadder.getOrNull(winnerIndex) ?: return
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("psiphon_winning_strategy", winnerIndex).apply()

        val via = if (protocol.isNotBlank()) " via $protocol" else ""
        ConnectionLog.record(
            "Connected$via — crediting strategy ${winner.name} (${winner.label}): $reason"
        )
        if (winnerIndex != rungAtConnect) {
            val active = psiphonLadder.getOrNull(rungAtConnect)
            ConnectionLog.record(
                "Note: strategy ${active?.name ?: "?"} was active but ${winner.name} " +
                    "carried the tunnel — next connect will start from ${winner.name}"
            )
        }
    }

    /**
     * Move to the next rung and re-dial, or give up if the ladder is exhausted.
     *
     * The TUN interface is deliberately left up across rungs: it was created
     * before Psiphon started, tun2socks is not running yet (no tunnel ever came
     * up), and rebuilding it would drop the VPN permission dialog state. Only
     * the Psiphon controller is torn down and restarted with the next config.
     */
    private fun escalateLadder() {
        if (stopRequested.get()) return
        if (!ladderActive.compareAndSet(true, false)) return
        cancelLadderTimer()

        val failed = psiphonLadder.getOrNull(ladderIndex)
        ladderAttempts += 1

        // Wrap around instead of walking off the end. Because a successful rung is
        // remembered and reused first, the ladder can start anywhere — so "done"
        // means every rung has had a turn, not that the index hit the last slot.
        if (ladderAttempts >= psiphonLadder.size) {
            ConnectionLog.record(
                "All ${psiphonLadder.size} strategies exhausted — carrier is blocking every available path"
            )
            sendStatus(STATUS_FAILED, "Could not connect on this carrier. Try Wi-Fi or another SIM.")
            ladderIndex = 0
            ladderAttempts = 0
            return
        }

        ladderIndex = (ladderIndex + 1) % psiphonLadder.size
        val next = psiphonLadder[ladderIndex]
        ConnectionLog.record(
            "Strategy ${failed?.name ?: "?"} timed out — escalating to ${next.name}: ${next.label}"
        )
        sendStatus(STATUS_CONNECTING, "Trying ${next.label}...")

        worker.execute {
            if (stopRequested.get()) return@execute
            // Tear down only the Psiphon controller. The TUN stays up.
            try { psiphonTunnel?.stop() } catch (_: Exception) {}
            psiphonTunnel = null
            activeSocksPort = CoreConfig.SOCKS_PORT
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            if (stopRequested.get()) return@execute
            startPsiphonTunnel()
        }
    }

    private fun stopPsiphonTunnel() {
        // PsiphonTunnel.stop() blocks until the Go controller has fully unwound,
        // which during establishing means waiting on in-flight dials. stopTunnel()
        // is reached from onStartCommand() on the main thread, so doing that here
        // synchronously froze the UI — which is what made a mid-connect cancel
        // look like it did nothing. Detach the reference synchronously (so
        // nothing else can use it) and let the blocking stop happen off-thread.
        val tunnel = psiphonTunnel ?: return
        psiphonTunnel = null
        Thread({
            try { tunnel.stop() } catch (_: Exception) {}
        }, "psiphon-stop").start()
    }

    // -------------------------------------------------------------- lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_CONFIG)?.let { config ->
                startTunnel(config)
            }
            ACTION_DISCONNECT -> stopTunnel()
            ACTION_RECONNECT -> {
                val config = storedConfig
                if (config != null && connected.get()) {
                    ConnectionLog.record("Quick reconnect requested")
                    stopTunnel(notify = false, teardownService = false)
                    worker.execute {
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                        startTunnel(config)
                    }
                }
            }
            ACTION_NOTIFICATION_HEALTH -> {
                intent.getStringExtra(EXTRA_NOTIFICATION_IP)?.let { currentVpnIp = it }
                intent.getStringExtra(EXTRA_NOTIFICATION_PING)?.let { currentPing = it }
                postNotification()
            }
        }
        return Service.START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopTunnel(notify = false)
        cancelLadderTimer()
        ladderScheduler.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Called from the native core to keep a socket off the TUN.
     *
     * Public and un-obfuscated on purpose: the Rust side resolves it by name.
     */
    fun protectSocket(fd: Int): Boolean = !vpnModeActive.get() || protect(fd)

    override fun onEvent(json: String) {
        try {
            val event = JSONObject(json)
            when (event.getString("type")) {
                "status" -> {
                    val status = event.getString("status")
                    val detail = if (event.isNull("detail")) null else event.getString("detail")
                    sendStatus(status, detail)
                }
                "traffic" -> {
                    val tx = event.getLong("tx")
                    val rx = event.getLong("rx")
                    currentTx = tx
                    currentRx = rx
                    updateTrafficNotification(tx, rx)
                }
                // The core measured the exit address from inside the tunnel. This
                // is the authoritative source: the app's own HTTP lookup leaves
                // over the carrier link (we are excluded from our own TUN) and so
                // reports the carrier's IP, not the tunnel's.
                "exit_ip" -> {
                    val ip = event.getString("ip")
                    if (ip.isNotBlank()) {
                        currentVpnIp = ip
                        ConnectionLog.record("Tunnel exit $ip")
                        // The country is resolved by the UI from this address; the
                        // core cannot tell one from inside the tunnel.
                        sendExitIp(ip)
                        postNotification()
                    }
                }
                "log" -> ConnectionLog.record(event.getString("message"))
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse event: $json", e)
        }
    }

    /**
     * Start a tunnel. Always whole-device VPN mode — proxy mode was removed, so
     * there is no longer a `vpnMode` parameter to branch on.
     */
    private fun startTunnel(config: String) {
        if (!connected.compareAndSet(false, true)) return
        storedConfig = config
        currentProtocol = config.substringAfter("\"protocol\":\"").substringBefore('"').uppercase()
        currentVpnIp = ""
        currentPing = ""
        // Derived here, on EVERY start, and not only inside the Psiphon branch.
        // Leaving a stale `true` behind sent the next non-Psiphon disconnect down
        // the Psiphon teardown path in stopTunnel(), which closes the TUN and
        // calls stopSelf() while the native worker's own finally block is doing
        // the same thing.
        psiphonVpnMode = currentProtocol.contains("PSIPHON")
        // Every new tunnel makes the core start counting bytes from zero again
        // (rx_total/tx_total are locals inside tun::bridge). Anything here that
        // still holds the previous session's totals would then be compared
        // against a counter that just went backwards, so it all has to be reset
        // together, before the first sample of the new session arrives.
        resetSessionTraffic()
        stopRequested.set(false)
        vpnModeActive.set(true)
        startAsForeground()

        // PSIPHON: callback-driven lifecycle — MUST NOT enter try/finally.
        // The finally block calls stopSelf(), which destroys the service and kills
        // Psiphon before it has finished establishing.
        if (psiphonVpnMode) {
            psiphonVpnActivated = false
            // Start from the rung that last worked on this device. On the first
            // ever connect, or after a full ladder failure, this is rung 0.
            ladderIndex = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("psiphon_winning_strategy", 0)
                .coerceIn(0, psiphonLadder.size - 1)
            ladderAttempts = 0
            worker.execute { startPsiphonSession() }
            return
        }

        worker.execute { startNativeSession(config) }
    }

    /**
     * Psiphon path: create the TUN first, then start the Go controller.
     *
     * Order matters. Creating the TUN after Psiphon starts makes Psiphon's
     * NetworkMonitor see tun0 appear as a network change, which used to cause a
     * 13-second restart loop.
     */
    private fun startPsiphonSession() {
        try {
            ConnectionLog.record("Preparing PSIPHON identity")
            val socksPort = CoreConfig.SOCKS_PORT

            // Address plan comes from tun2socks: the interface gets .ipAddress
            // while lwIP answers on .router, which is also the DNS resolver the
            // system will use. These must not be swapped or lwIP drops every
            // packet.
            val address = Tun2SocksManager.selectPrivateAddress()

            ConnectionLog.record("Creating TUN interface BEFORE Psiphon starts")
            tun = Builder()
                .setSession("MSN-GUARD")
                .setMtu(Tun2SocksManager.VPN_INTERFACE_MTU)
                .addAddress(address.ipAddress, address.prefixLength)
                .addRoute("0.0.0.0", 0)
                .addRoute(address.subnet, address.prefixLength)
                .addDnsServer(address.router)
                // --- Break the DNS bootstrap deadlock ---
                // With only address.router as a resolver, every DNS query goes
                // lwIP → udpgw → Psiphon. Before a tunnel exists there is nothing
                // on the far end, so DNS is dead exactly when Psiphon needs it to
                // resolve the CDN hostnames FRONTED-MEEK depends on. The log
                // showed this as "resp 0/0" with 20-second RTTs and four
                // consecutive "resolve canceled" tactics failures.
                //
                // Public resolvers give the resolver somewhere to go. Combined
                // with addDisallowedApplication(packageName) below — which keeps
                // our own process off the TUN entirely — Psiphon's queries leave
                // over the carrier link and resolve normally, so the fronted
                // protocols become usable.
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)
                .establish() ?: error("Android could not establish the VPN interface")
            vpnModeActive.set(true)
            ConnectionLog.record("TUN ready — now starting Psiphon on port $socksPort")
            // Pre-save the SOCKS port so onConnected() can start tun2socks immediately.
            activeSocksPort = socksPort
            startPsiphonTunnel()
            sendStatus(STATUS_CONNECTING, "Psiphon starting...")
        } catch (e: Exception) {
            ConnectionLog.record("Psiphon start failed: ${e.message}")
            sendStatus(STATUS_FAILED, e.message)
            connected.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Native path (MASQUE / WireGuard / WoW): the Rust core binds the Android TUN
     * directly, so no local SOCKS listener exists for this session.
     */
    private fun startNativeSession(config: String) {
        try {
            ConnectionLog.record("Preparing $currentProtocol identity")
            NativeCore.attach(this)
            val addresses = NativeCore.prepare(config)
            if (addresses.organization.isNotBlank()) {
                ConnectionLog.record("Zero Trust organization ${addresses.organization}")
            }
            ConnectionLog.record("Creating Android VPN interface")
            tun = Builder()
                .setSession("MSN-GUARD")
                .setMtu(1280)
                .applyTunnelAddresses(addresses)
                .applyDns(config, addresses)
                .applyGatewayProxy(config, addresses)
                .applyLanAccess(this, addresses)
                // Handles app exclusion per mode, including keeping ourselves off
                // the TUN.
                .applySplitTunneling(this)
                .establish() ?: error("Android could not establish the VPN interface")
            ConnectionLog.record("Scanning gateways for VPN")
            // The Rust core is about to bind this TUN fd directly, which means no
            // local SOCKS listener will exist for this session. The UI health
            // check must go direct, not via 127.0.0.1.
            TunnelStatus.isNativeTunMode = true
            val result = NativeCore.start(config, tun!!.fd)

            when {
                stopRequested.get() -> sendStatus(STATUS_DISCONNECTED)
                result != 0 -> {
                    val detail = NativeCore.lastError().ifBlank { "Tunnel exited with code $result" }
                    ConnectionLog.record("Native tunnel exited: $detail")
                    sendStatus(STATUS_FAILED, detail)
                }
                else -> {
                    ConnectionLog.record("Native tunnel stopped unexpectedly")
                    sendStatus(STATUS_FAILED, "Tunnel stopped unexpectedly")
                }
            }
        } catch (error: Exception) {
            val detail = NativeCore.lastError().ifBlank { error.message ?: "Tunnel setup failed" }
            Log.e(LOG_TAG, "Tunnel failed: $detail", error)
            sendStatus(STATUS_FAILED, detail)
        } finally {
            NativeCore.detach()
            vpnModeActive.set(false)
            TunnelStatus.isNativeTunMode = false
            val killSwitch = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("kill_switch", false)
            tun?.close()
            tun = null
            connected.set(false)
            if (killSwitch && !stopRequested.get()) {
                ConnectionLog.record("Kill switch active; blocking all traffic")
                sendStatus(STATUS_FAILED, "Kill switch active — tunnel dropped")
                rebuildKillSwitchVpn()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopTunnel(notify: Boolean = true, teardownService: Boolean = true) {
        stopRequested.set(true)
        // The traffic counters are only flushed to disk on a slow timer while
        // running, so an ordinary disconnect must persist the remainder here or
        // the last minute of the session would be lost from the monthly total.
        flushMonthlyTraffic()
        // Clear the session stamp here, not in sendStatus: the reconnect path and
        // onDestroy both call stopTunnel(notify = false), so relying on the
        // DISCONNECTED broadcast left connectedSince set and the next session's
        // timer resumed the old elapsed time instead of restarting at zero.
        connectedSince = 0L
        // Disarm the escalation ladder before anything else: a pending timer that
        // fires after teardown would resurrect Psiphon on a dead TUN.
        ladderActive.set(false)
        cancelLadderTimer()
        // Order matters: stop routing first so no more packets enter a tunnel
        // that is being torn down, then stop Psiphon itself.
        Tun2SocksManager.stop()
        stopPsiphonTunnel()
        NativeCore.stop()
        TunnelStatus.isNativeTunMode = false

        if (psiphonVpnMode) {
            // In VPN mode nothing else owns the service lifecycle now that the
            // Rust core is out of the data path, so tear down here.
            NativeCore.detach()
            vpnModeActive.set(false)
            psiphonVpnActivated = false
            psiphonVpnMode = false
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            // A reconnect re-enters startTunnel() on the worker thread, so the
            // service must survive; only a real disconnect stops it.
            if (teardownService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        // Native path: startNativeSession()'s finally block owns the rest of the
        // teardown, because NativeCore.start() is still unwinding on the worker.
        if (notify && !connected.get()) sendStatus(STATUS_DISCONNECTED)
    }

    private fun rebuildKillSwitchVpn() {
        try {
            tun?.close()
            tun = Builder()
                .setSession("MSN-GUARD — Kill Switch")
                .setMtu(1280)
                .addAddress("100.64.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .establish()
            ConnectionLog.record("Kill switch VPN active; all traffic blocked")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Kill switch rebuild failed: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------ broadcasts

    private fun sendStatus(status: String, detail: String? = null) {
        Log.i(LOG_TAG, "status=$status${detail?.let { " detail=$it" } ?: ""}")
        // Stamp the connect moment here rather than at each call site: there are
        // several paths to CONNECTED (native tunnel ready, Psiphon proxy ready,
        // tun2socks up, reconnect) and every one funnels through sendStatus.
        when (status) {
            STATUS_CONNECTED -> if (connectedSince == 0L) connectedSince = SystemClock.elapsedRealtime()
            STATUS_DISCONNECTED, STATUS_FAILED -> connectedSince = 0L
        }
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .apply { detail?.let { putExtra(EXTRA_DETAIL, it) } })
        TileService.requestListeningState(
            this,
            ComponentName(this, MsnGuardTileService::class.java),
        )
    }

    private fun sendTraffic(tx: Long, rx: Long, monthTx: Long, monthRx: Long) {
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_TRAFFIC_TX, tx)
            .putExtra(EXTRA_TRAFFIC_RX, rx)
            .putExtra(EXTRA_TRAFFIC_SPEED_TX, currentSpeedTx)
            .putExtra(EXTRA_TRAFFIC_SPEED_RX, currentSpeedRx)
            .putExtra(EXTRA_TRAFFIC_MONTH_TX, monthTx)
            .putExtra(EXTRA_TRAFFIC_MONTH_RX, monthRx))
    }

    /**
     * Broadcasts the core-measured exit address to the UI.
     *
     * Address only. The country is a geolocation question, which the UI answers
     * over whatever link it has — the answer for a given address is the same
     * either way, so it does not need to be asked from inside the tunnel.
     */
    private fun sendExitIp(ip: String) {
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_EXIT_IP, ip))
    }

    // --------------------------------------------------------------- traffic

    private fun updateTrafficNotification(tx: Long, rx: Long) {
        val now = SystemClock.elapsedRealtime()
        // The core's counters are per-tunnel locals, and the core reconnects on
        // its own (the MASQUE and WireGuard reconnect loops both re-enter
        // `tun::bridge`) without the service being told. When that happens the
        // numbers arriving here go backwards, and every derived figure below —
        // the speed delta and the monthly delta — would compute a large negative
        // or absurd value from a mismatched baseline. Rebase instead of trying to
        // subtract across the discontinuity.
        if (tx < prevTx || rx < prevRx || tx < accountedTx || rx < accountedRx) {
            prevTx = 0
            prevRx = 0
            accountedTx = 0
            accountedRx = 0
            prevSpeedSampleMs = 0
            currentSpeedTx = 0
            currentSpeedRx = 0
        }
        if (now - lastTrafficSampleMs < TRAFFIC_SAMPLE_MIN_MS) return

        val elapsed = now - prevSpeedSampleMs
        if (elapsed > 0 && prevSpeedSampleMs > 0) {
            currentSpeedTx = ((tx - prevTx) * 1000) / elapsed
            currentSpeedRx = ((rx - prevRx) * 1000) / elapsed
        }
        prevTx = tx
        prevRx = rx
        prevSpeedSampleMs = now

        val (monthTx, monthRx) = recordMonthlyTraffic(
            (tx - accountedTx).coerceAtLeast(0),
            (rx - accountedRx).coerceAtLeast(0),
        )
        accountedTx = tx
        accountedRx = rx
        sendTraffic(tx, rx, monthTx, monthRx)

        if (now - lastTrafficFlushMs >= TRAFFIC_FLUSH_MS) {
            flushMonthlyTraffic()
            lastTrafficFlushMs = now
        }

        // Rebuilding and posting the notification is not free: it crosses into
        // system_server and re-lays out the shade row. At one update a second for
        // hours that adds up, and the text only carries byte counters — nobody is
        // reading them per-second from a collapsed notification. Every few seconds
        // conveys the same thing.
        if (now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_MS) {
            postNotification()
            lastNotificationUpdateMs = now
        }
        lastTrafficSampleMs = now
    }

    /**
     * Adds this sample to the monthly totals, in memory.
     *
     * The disk write is deliberately not here — see [flushMonthlyTraffic].
     */
    private fun recordMonthlyTraffic(tx: Long, rx: Long): Pair<Long, Long> {
        // First sample of this process, or the month rolled over mid-session.
        loadMonthlyTotals()
        monthTxTotal += tx
        monthRxTotal += rx
        return monthTxTotal to monthRxTotal
    }

    private fun currentMonthKey(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    /** Loads the persisted monthly totals into memory, once per month key. */
    private fun loadMonthlyTotals() {
        val month = currentMonthKey()
        if (monthKey == month) return
        val prefs = getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE)
        val stored = prefs.getString(TRAFFIC_MONTH, null)
        monthTxTotal = if (stored == month) prefs.getLong(TRAFFIC_TX, 0) else 0
        monthRxTotal = if (stored == month) prefs.getLong(TRAFFIC_RX, 0) else 0
        monthKey = month
    }

    /**
     * Clears everything that describes the *current session's* traffic.
     *
     * Called at the start of every tunnel. The core's counters are locals inside
     * `tun::bridge`, so each new tunnel restarts them at zero; every mirror of
     * them here has to restart too.
     *
     * This is what the connect/disconnect/connect failure came down to. The
     * activity's verification gate takes `rxAtStart = trafficRx` when the
     * transport reports CONNECTED and then waits for `trafficRx` to reach
     * `rxAtStart + VERIFY_MIN_RX_BYTES`. `trafficRx` is fed straight from
     * `currentRx` here, and neither was ever reset, so on the second connect of
     * a process the gate demanded that a counter starting from zero exceed the
     * *previous* session's final total. It never could, so verification always
     * timed out after 18s and the UI reported "handshake succeeded but nothing
     * passes" for a tunnel that was working.
     *
     * The monthly totals are deliberately NOT cleared: they are cumulative
     * across sessions. Only the per-session deltas reset, and `accountedTx/Rx`
     * going to zero is what keeps the monthly accounting correct — the next
     * sample's delta is measured from zero, matching the core's fresh counter.
     */
    private fun resetSessionTraffic() {
        // The month totals are read lazily on the first sample; make sure they are
        // loaded before broadcasting, or a reset before any traffic would tell the
        // UI the month total is zero and the traffic screen would blank out.
        loadMonthlyTotals()
        currentTx = 0
        currentRx = 0
        prevTx = 0
        prevRx = 0
        currentSpeedTx = 0
        currentSpeedRx = 0
        accountedTx = 0
        accountedRx = 0
        // Zeroed, not set to `now`: these are throttle stamps, and a fresh
        // session should publish its first sample immediately rather than wait
        // out a window inherited from the tunnel that just died.
        prevSpeedSampleMs = 0
        lastTrafficSampleMs = 0
        lastNotificationUpdateMs = 0
        // The UI keeps its own mirrors, and it cannot know the core restarted
        // counting unless it is told. Without this broadcast the activity would
        // hold the old totals until the first traffic sample of the new session,
        // and the verification baseline is taken before that arrives.
        sendTraffic(0, 0, monthTxTotal, monthRxTotal)
    }

    /**
     * Writes the in-memory monthly totals to disk.
     *
     * Called on a slow timer from the traffic path and unconditionally on
     * teardown, so an ordinary disconnect always persists an exact figure.
     */
    private fun flushMonthlyTraffic() {
        val month = monthKey ?: return
        getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE).edit()
            .putString(TRAFFIC_MONTH, month)
            .putLong(TRAFFIC_TX, monthTxTotal)
            .putLong(TRAFFIC_RX, monthRxTotal)
            .apply()
    }

    // ---------------------------------------------------------- notification

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "VPN Service",
            NotificationManager.IMPORTANCE_LOW,
        ))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MSN-GUARD")
            .setContentText("Connecting...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // Declare the type explicitly where the platform supports it. The manifest
        // already declares dataSync, but stating it at the call site is what makes
        // a mismatch fail loudly at startForeground() instead of silently at some
        // later restriction check.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(currentTx, currentRx))
    }

    private fun notification(tx: Long, rx: Long): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = Intent(this, MsnGuardVpnService::class.java).apply {
            action = ACTION_DISCONNECT
            putExtra(EXTRA_CONFIG, storedConfig ?: "")
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val reconnectIntent = Intent(this, MsnGuardVpnService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this, 2, reconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MSN-GUARD")
            .setContentText(
                "VPN: $currentProtocol • ${ByteFormat.bytes(tx)}↑ ${ByteFormat.bytes(rx)}↓" +
                    " • ${ByteFormat.speed(currentSpeedTx)}↑ ${ByteFormat.speed(currentSpeedRx)}↓"
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .addAction(android.R.drawable.ic_menu_revert, "Reconnect", reconnectPendingIntent)

        // The exit IP and the latency were dead data: ACTION_NOTIFICATION_HEALTH
        // set both fields and rebuilt this notification, which never referenced
        // either one. They are the two things a user actually wants from the shade
        // without opening the app, so they go in the subtext.
        val health = listOf(currentVpnIp, currentPing).filter { it.isNotBlank() }
        if (health.isNotEmpty()) builder.setSubText(health.joinToString(" · "))

        return builder.build()
    }
}
