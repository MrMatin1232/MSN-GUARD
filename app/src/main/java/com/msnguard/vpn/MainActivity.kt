package com.msnguard.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    internal lateinit var orbitDial: OrbitDialView
    internal lateinit var connectionTitle: TextView
    internal lateinit var connectionDetail: TextView
    internal lateinit var chipLatency: TextView
    internal lateinit var chipProtocol: TextView
    internal lateinit var tileDown: MetricTile
    internal lateinit var tileUp: MetricTile
    internal lateinit var tileSpeed: MetricTile
    internal lateinit var exitNodeCard: ExitNodeCard
    internal lateinit var transportRail: TransportRail
    internal lateinit var actionBar: OrbitActionBar
    internal lateinit var footerWave: OrbitFooterWave
    internal lateinit var statusLed: View
    internal lateinit var mainRoot: FrameLayout
    internal lateinit var pageHost: FrameLayout
    internal lateinit var appUpdater: AppUpdater
    internal var predictiveBackCallback: Any? = null
    internal var selectedProtocol = Protocol.MASQUE
    internal var pendingConfig: String? = null
    internal var visualState = OrbitDialView.State.DISCONNECTED
    internal var receiverRegistered = false
    internal var autoPingRunning = false
    /**
     * Whether the UI is in the foreground.
     *
     * Everything periodic on this screen — the session timer, the status poll,
     * the auto-ping — exists to keep *visible* widgets truthful. While the app
     * is backgrounded or the screen is off there is nothing to keep truthful,
     * and on a phone that periodic work is the expensive part: each auto-ping is
     * an HTTP request that pulls the radio out of its low-power state, and each
     * timer tick denies the CPU a long idle window.
     *
     * The tunnel itself is unaffected. It lives in the service and the Rust
     * core, which keep their own health checks running; this flag only gates
     * work whose entire purpose is repainting a screen nobody is looking at.
     */
    internal var uiForeground = false
    /** elapsedRealtime at the moment the tunnel came up; 0 when down. */
    internal var sessionStartedAt = 0L
    internal val sessionHandler = Handler(Looper.getMainLooper())
    internal val sessionTicker = object : Runnable {
        override fun run() {
            // Backgrounded means the timer text is not on screen, so ticking it
            // every second is work with no observer. The elapsed time is derived
            // from `sessionStartedAt` on the next resume, so nothing drifts.
            if (sessionStartedAt == 0L || !uiForeground) return
            orbitDial.timerText = formatUptime(android.os.SystemClock.elapsedRealtime() - sessionStartedAt)
            sessionHandler.postDelayed(this, 1_000L)
        }
    }
    internal val autoPingHandler = Handler(Looper.getMainLooper())
    internal val autoPingRunnable = object : Runnable {
        override fun run() {
            // The foreground check is what makes this cheap: a real HTTP request
            // every 5s wakes the radio, and in the background nothing consumes
            // the result. Liveness is not lost — the core's own health check
            // (every 3s, 10s staleness limit) drops a dead tunnel regardless of
            // whether this screen is up.
            if (isTunnelActive() && autoPingRunning && uiForeground) {
                pingConnection()
                autoPingHandler.postDelayed(this, 5000L)
            }
        }
    }
    internal var showingSettings = false
    internal var showingLogs = false
    internal var showingScanner = false
    internal var showingMode = false
    internal var settingsPage: View? = null
    internal var tunnelControlsPage: View? = null
    internal var logsPage: View? = null
    internal var scannerPage: View? = null
    internal var modePage: View? = null
    internal var splitTunnelPage: View? = null
    internal var splitTunnelAppsPage: View? = null
    internal var splitTunnelSummaryButton: OrbitSettingsRow? = null
    internal var splitTunnelDraftMode: SplitTunnelSettings.Mode? = null
    internal var splitTunnelDraftPackages: MutableSet<String>? = null
    internal var trafficMonitorPage: View? = null
    internal var trafficSpeedValue: TextView? = null
    internal var trafficSessionValue: TextView? = null
    internal var trafficMonthValue: TextView? = null
    internal var trafficTx = 0L
    /**
     * Read from a worker thread by [awaitTunnelBytes] while the receiver writes
     * it on the main thread, so the write has to be visible across threads.
     */
    @Volatile internal var trafficRx = 0L
    internal var trafficSpeedTx = 0L
    internal var trafficSpeedRx = 0L
    internal var trafficMonthTx = 0L
    internal var trafficMonthRx = 0L
    @Volatile internal var cachedUserApps: List<ApplicationInfo>? = null
    internal var latencyRequest = 0
    @Volatile internal var pingInFlight = false
    /**
     * Connection verification. STATUS_CONNECTED from the service only means
     * "the transport handshake finished" — on MCI/Hamrah-e-Aval a WireGuard
     * handshake can complete while no payload ever crosses, which is how the UI
     * ended up showing Connected with byte-level counters and no reachable
     * sites. Nothing calls itself Connected until [verifyDataPlane] has pulled a
     * real HTTP response through the tunnel.
     */
    internal var verifyRequest = 0
    @Volatile internal var verifyInFlight = false
    /** Consecutive failed health checks while nominally connected. */
    internal var pingFailureStreak = 0
    /**
     * Set when we tore a tunnel down ourselves because it never passed traffic.
     * The teardown makes the service broadcast DISCONNECTED, which would repaint
     * the screen as a plain "Not connected" and hide the real reason — this flag
     * makes the receiver keep the failure message on screen.
     */
    internal var suppressNextDisconnectedPaint = false
    internal var ipRequest = 0
    @Volatile internal var ipRefreshInFlight = false
    @Volatile internal var ipRefreshPending = false
    /**
     * Exit address as measured by the core from inside the tunnel, and its
     * country. Empty until the core reports one.
     *
     * This is the trustworthy number. [fetchPublicIp] runs in our own process,
     * which `applySplitTunneling()` deliberately keeps off the TUN via
     * `addDisallowedApplication(packageName)`, so its HTTP request exits over the
     * carrier and returns the carrier's address — Iran — while the tunnel really
     * exits elsewhere. Once this is set, the HTTP result is ignored.
     */
    internal var coreExitIp = ""
    internal var coreExitCountry = ""
    /** Generation counter for country lookups, so a stale one cannot repaint. */
    internal var countryRequest = 0
    internal val statusHandler = Handler(Looper.getMainLooper())
    internal val statusPoll = object : Runnable {
        override fun run() {
            if (!uiForeground) return
            renderStatus()
            statusHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }
    internal lateinit var palette: AppAppearance.Palette
    internal val CANVAS get() = palette.canvas
    internal val SURFACE get() = palette.surface
    internal val SURFACE_VARIANT get() = palette.surfaceVariant
    internal val INK get() = palette.ink
    internal val MUTED get() = palette.muted
    internal val DIVIDER get() = palette.divider
    internal val primary get() = palette.primary
    internal val primaryContainer get() = palette.primaryContainer
    internal val connected get() = palette.connected
    internal val connectedContainer get() = palette.connectedContainer
    internal val motionInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    internal val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The core measured the exit address from inside the tunnel. It wins
            // over anything fetchPublicIp() could produce, so handle it first and
            // return: in native TUN mode our own HTTP request bypasses the tunnel.
            intent.getStringExtra(MsnGuardVpnService.EXTRA_EXIT_IP)?.let { ip ->
                if (ip.isNotBlank()) {
                    coreExitIp = ip
                    coreExitCountry = ""
                    // An in-flight HTTP lookup must not overwrite this with the
                    // carrier's address when it finishes late.
                    ipRequest++
                    exitNodeCard.render(ip, null, isTunnelActive())
                    if (isTunnelActive()) updateNotificationHealth(ip = ip)
                    // The country is a property of the address, not of the link the
                    // question travels over, so it can be asked over the carrier.
                    resolveExitCountry(ip)
                }
                return
            }
            if (intent.hasExtra(MsnGuardVpnService.EXTRA_TRAFFIC_TX)) {
                trafficTx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_TX, 0)
                trafficRx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_RX, 0)
                trafficSpeedTx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_SPEED_TX, 0)
                trafficSpeedRx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_SPEED_RX, 0)
                trafficMonthTx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_MONTH_TX, 0)
                trafficMonthRx = intent.getLongExtra(MsnGuardVpnService.EXTRA_TRAFFIC_MONTH_RX, 0)
                renderTrafficMonitor()
                renderHomeMetrics()
                return
            }
            when (intent.getStringExtra(MsnGuardVpnService.EXTRA_STATUS)) {
                MsnGuardVpnService.STATUS_CONNECTING -> showConnecting(intent.getStringExtra(MsnGuardVpnService.EXTRA_DETAIL))
                MsnGuardVpnService.STATUS_STARTING -> showStarting()
                MsnGuardVpnService.STATUS_SCANNING -> showScanning()
                // NOT showConnected(). The service's CONNECTED only means the
                // transport handshake finished; it is not proof that payload
                // crosses. beginVerification() proves it before the UI claims it.
                MsnGuardVpnService.STATUS_CONNECTED -> beginVerification()
                MsnGuardVpnService.STATUS_FAILED -> showFailure(intent.getStringExtra(MsnGuardVpnService.EXTRA_DETAIL))
                MsnGuardVpnService.STATUS_DISCONNECTED -> {
                    // Our own verification teardown produces this broadcast. Keep
                    // the "no traffic passes" message instead of overwriting it
                    // with a generic "Not connected".
                    if (suppressNextDisconnectedPaint) {
                        suppressNextDisconnectedPaint = false
                        setModeEnabled(true)
                    } else {
                        showDisconnected()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No DynamicColors. The approved Orbit palette is fixed, and letting the
        // OS inject Material You colours repainted theme-derived surfaces (ripple
        // tints, dialog backgrounds) in the phone's wallpaper hues, which is
        // exactly the multi-palette behaviour that was removed with the theme
        // picker.
        super.onCreate(savedInstanceState)
        palette = AppAppearance.load(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnBackInvokedCallback { handleBack() }.also { callback ->
                predictiveBackCallback = callback
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback,
                )
            }
        }
        requestNotificationPermission()
        ConnectionLog.bind(File(filesDir, "connection.log"))
        appUpdater = AppUpdater(this)

        // Orbit console. Every control below is built in onCreate so a single
        // pass wires the whole screen; no XML layouts exist in this app.
        orbitDial = OrbitDialView(this, palette).apply {
            setOnClickListener { toggleTunnel() }
        }
        connectionTitle = label(textSize = 21f, color = INK, style = TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
        }
        connectionDetail = label(textSize = 13.5f, color = MUTED).apply { gravity = Gravity.CENTER }
        chipLatency = label("Latency —", 12f, MUTED, TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
            contentDescription = "Ping connection"
            isClickable = true
            isFocusable = true
            setOnClickListener { pingConnection() }
        }
        chipProtocol = label(selectedProtocol.label.uppercase(), 12f, MUTED, TypefaceStyle.MEDIUM).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        selectedProtocol = savedProtocol()
        chipProtocol.text = selectedProtocol.label.uppercase()
        // One accent per tile, as in the approved mock: download mint, upload
        // violet, speed amber. They were all `primary` before, which is why every
        // sparkline looked identical.
        tileDown = MetricTile(this, palette, "↓ DOWN", palette.mint, Sculpt.lighten(palette.mint, 0.30f)) {
            openTrafficMonitorScreen()
        }
        tileUp = MetricTile(this, palette, "↑ UP", palette.violet, Sculpt.lighten(palette.violet, 0.30f)) {
            openTrafficMonitorScreen()
        }
        tileSpeed = MetricTile(this, palette, "SPEED", palette.amber, Sculpt.lighten(palette.amber, 0.30f)) {
            openTrafficMonitorScreen()
        }
        exitNodeCard = ExitNodeCard(this, palette) { refreshPublicIp() }
        transportRail = TransportRail(this, palette, Protocol.entries.map { railLabel(it) }) { index ->
            updateConnectionMode(Protocol.entries[index])
        }
        transportRail.select(Protocol.entries.indexOf(selectedProtocol), animate = false)
        actionBar = OrbitActionBar(this, palette, listOf(
            OrbitActionBar.Entry("LOG", OrbitActionBar.Glyph.LOG) { openLogsScreen() },
            OrbitActionBar.Entry("SPLIT", OrbitActionBar.Glyph.SPLIT) { openSplitTunnelScreen() },
            OrbitActionBar.Entry("SCAN MODE", OrbitActionBar.Glyph.SCAN) { openScannerScreen() },
        ))
        // The dead space under the action bar looked like a rendering bug. It is
        // now a thin signal trace that idles flat and grey, and ripples in the
        // connected accent once traffic is flowing. One 48-point path repainted at
        // 20fps only while connected — no bitmaps, no extra APK weight.
        footerWave = OrbitFooterWave(this, palette, "SECURED BY MSN-GUARD")
        statusLed = View(this).apply {
            background = Sculpt.sculptedBackground(
                resources.displayMetrics.density,
                Sculpt.withAlpha(MUTED, 0.5f),
                999,
            )
        }

        mainRoot = FrameLayout(this).apply { setBackgroundColor(CANVAS) }
        val header = createHeader()
        val console = createConnectionConsole()
        // The console can still scroll, but it is meant not to need it: the dial
        // shrinks first (see [fitConsoleToViewport]) and scrolling is only the
        // last resort on a screen too short even for the smallest dial. Clipping
        // the connect button would be the single worst failure this screen could
        // have, so the ScrollView stays as the safety net.
        val consoleScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Deliberately NOT isFillViewport: see [fitConsoleToViewport], which
            // needs the console's real measured height to know how much room is
            // free.
            // The dial paints its halo and pulse rings outside its own bounds, so
            // every ancestor in the chain has to stop clipping — one clipping
            // parent anywhere above the view is enough to cut the glow off.
            clipChildren = false
            clipToPadding = false
            addView(console, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        mainRoot.clipChildren = false
        mainRoot.addView(consoleScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = dp(52) })
        fitConsoleToViewport(consoleScroll, console)
        mainRoot.addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
            topMargin = dp(10)
        })
        mainRoot.setOnApplyWindowInsetsListener { _, insets ->
            (header.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = insets.systemWindowInsetTop + dp(10)
                header.layoutParams = this
            }
            (consoleScroll.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = insets.systemWindowInsetTop + dp(52)
                bottomMargin = insets.systemWindowInsetBottom
                consoleScroll.layoutParams = this
            }
            insets
        }
        pageHost = FrameLayout(this).apply {
            setBackgroundColor(CANVAS)
            addView(mainRoot, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(pageHost)
        configureSystemBars()
        showOpeningOverlay()
        // Reattach to a tunnel that is already up. Without this the dial opens in
        // the disconnected state while the VPN is running, and the session timer
        // would only start on the next status broadcast. restored = true keeps
        // the elapsed time honest by reading the service's connect timestamp.
        if (TunnelStatus.isActive()) {
            showConnected(restored = true)
            // showConnected(restored) deliberately skips these so a mid-session
            // health check does not re-probe on every ping; on a cold start we do
            // want them, otherwise the IP card and latency stay empty.
            startAutoPing()
            pingConnection()
        }
        refreshPublicIp()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MsnGuardVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        // The session ticker is a Handler post, not tied to a view, so it has to
        // be cancelled by hand. The dial's own animators already stop in
        // onDetachedFromWindow.
        sessionHandler.removeCallbacks(sessionTicker)
        autoPingHandler.removeCallbacks(autoPingRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (predictiveBackCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        uiForeground = true
        // Restart everything the pause stopped. Each of these is idempotent and
        // cheap; the point is that the screen is correct the instant it appears
        // rather than after one poll interval.
        statusHandler.removeCallbacks(statusPoll)
        statusPoll.run()
        if (sessionStartedAt != 0L) {
            sessionHandler.removeCallbacks(sessionTicker)
            sessionHandler.post(sessionTicker)
        }
        if (isTunnelActive() && autoPingRunning) {
            // Fire one immediately: the latency shown on screen was measured
            // before the pause and may be minutes stale.
            autoPingHandler.removeCallbacks(autoPingRunnable)
            autoPingHandler.post(autoPingRunnable)
        }
        renderStatus()
        appUpdater.resumeInstallIfPermitted()
    }

    /**
     * Stops every periodic repaint while the screen is not visible.
     *
     * onPause rather than onStop deliberately: onStop does not fire for a screen
     * merely dimmed or partially covered, and those are exactly the long idle
     * stretches where a 1-second ticker and a 5-second HTTP probe cost the most.
     */
    override fun onPause() {
        uiForeground = false
        statusHandler.removeCallbacks(statusPoll)
        sessionHandler.removeCallbacks(sessionTicker)
        autoPingHandler.removeCallbacks(autoPingRunnable)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            pendingConfig?.let { connect(it) }
        } else if (requestCode == VPN_REQUEST) {
            showDisconnected("VPN permission required")
        }
        pendingConfig = null
    }

    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    internal enum class Protocol(
        val label: String,
        val coreName: String,
        val description: String,
        val androidAvailable: Boolean = true,
    ) {
        MASQUE("MASQUE", "masque", "HTTP/3 tunnel"),
        WIREGUARD("WireGuard", "wireguard", "WireGuard tunnel"),
        WARP_IN_WARP("WARP-on-WARP", "gool", "Double-layer tunnel"),
        PSIPHON("Psiphon", "psiphon", "Anti-censorship tunnel"),
    }

    internal enum class ScanTarget(
        val label: String,
        val coreName: String,
        val description: String,
    ) {
        IPV4("IPv4", "v4", "Scan IPv4 endpoints only"),
        IPV6("IPv6", "v6", "Scan IPv6 endpoints only"),
        BOTH("Both", "both", "Scan IPv4 and IPv6 endpoints"),
    }

    internal enum class ScanMode(
        val label: String,
        val coreName: String,
        val description: String,
    ) {
        TURBO("Turbo", "turbo", "Fastest scan; first verified route wins"),
        BALANCED("Balanced", "balanced", "Default mix of speed and coverage"),
        THOROUGH("Thorough", "thorough", "Deep scan; selects best latency"),
        STEALTH("Stealth", "stealth", "Quiet, patient probing"),
        IRONCLAD("Ironclad", "ironclad", "Strict CONNECT-IP verification before selection"),
    }

    internal enum class MasqueTransport(
        val label: String,
        val coreName: String,
        val description: String,
    ) {
        H3("HTTP/3", "h3", "QUIC first; falls back to HTTP/2 if UDP is blocked"),
        H2("HTTP/2", "h2", "TCP with TLS fragmentation; use on restricted networks"),
    }

    internal enum class EndpointDiscovery(
        val label: String,
        val coreName: String,
        val description: String,
    ) {
        CACHE("Cache & refresh", "cache", "Use verified gateways first, then discover more"),
        FRESH("Fresh scan", "fresh", "Start a new scan every connection"),
    }

    internal enum class ObfuscationProfile(val label: String, val coreName: String, val description: String) {
        OFF("Off", "off", "No traffic-shape padding"),
        LIGHT("Light", "light", "Lower overhead on mild filtering"),
        BALANCED("Balanced", "balanced", "Recommended filtering resistance"),
        AGGRESSIVE("Aggressive", "aggressive", "Highest resistance; slower setup"),
    }

    internal enum class TlsCurvePreset(val label: String, val coreName: String, val description: String) {
        CHROME("Chrome", "chrome", "Chrome TLS curve ordering"),
        COMPATIBILITY("Compatibility", "compatibility", "P-256 and X25519 only"),
    }

    internal enum class LogLevel(val label: String, val coreName: String, val description: String) {
        ERROR("Error", "error", "Only errors"),
        WARN("Warn", "warn", "Warnings and errors"),
        INFO("Info", "info", "Default verbosity"),
        DEBUG("Debug", "debug", "Tunnel internals"),
        TRACE("Trace", "trace", "Full per-packet detail"),
    }

    internal enum class PerfProfile(val label: String, val coreName: String, val description: String) {
        AUTO("Auto", "auto", "Detect hardware and scale accordingly"),
        LOW("Low", "low", "Routers and constrained devices"),
        MEDIUM("Medium", "medium", "Moderate hardware"),
        HIGH("High", "high", "Desktop and powerful devices"),
    }

    internal enum class H2Fragmentation(val label: String, val coreName: String, val description: String) {
        ON("On", "on", "Fragment TLS handshake to evade DPI"),
        OFF("Off", "off", "Standard TLS handshake"),
    }

    internal enum class LogTab(val label: String) {
        ALL("All"),
        APP("App"),
        CORE("Core"),
    }

    internal data class SelectionOption(
        val row: LinearLayout,
        val title: TextView,
        val indicator: TextView,
        val radius: Int,
    )

    internal enum class TypefaceStyle { REGULAR, MEDIUM }

    internal companion object {
        const val VPN_REQUEST = 100
        const val NOTIFICATION_PERMISSION_REQUEST = 101
        const val LOG_REFRESH_MS = 750L
        const val STATUS_POLL_MS = 2_000L
        const val PAGE_ANIMATION_MS = 220L
        const val LOG_CLOSE_ANIMATION_MS = 160L
        const val PING_TIMEOUT_MS = 5_000
        /**
         * How long a freshly handshaken tunnel gets to prove it passes traffic.
         * 18s covers a slow MASQUE gateway pick and Psiphon's own warm-up while
         * still failing fast enough that the user is not staring at a dead dial.
         */
        const val VERIFY_TIMEOUT_MS = 18_000L
        const val VERIFY_RETRY_DELAY_MS = 1_200L
        /**
         * Grace period after the dial goes green before the core's own byte
         * counters are checked. 12s is past the point where a working tunnel has
         * carried something (DNS alone does it) but short enough that the user
         * is not left trusting a dead tunnel. See watchForTunnelBytes.
         */
        const val BYTE_WATCH_MS = 12_000L
        /**
         * Bytes that must cross the TUN before a native tunnel counts as verified.
         *
         * Above the core's own keepalive/health-probe traffic (a DNS query every
         * three seconds, tens of bytes a round) and far below what loading any
         * real page moves, so it separates "the tunnel is alive" from "the
         * tunnel is only talking to itself".
         */
        const val VERIFY_MIN_RX_BYTES = 4_096L
        /**
         * Breathing room kept between the console's bottom edge and the viewport
         * when [fitConsoleToViewport] sizes the dial. Without it the action bar
         * ends up flush against the navigation bar, which reads as clipped even
         * though it is fully on screen.
         */
        const val FIT_SLACK_DP = 6
        /**
         * Consecutive failed health checks tolerated on an established session
         * before the tunnel is declared dead and torn down. Three misses at the
         * 5s auto-ping interval ≈ 15s of genuinely no reachable endpoint, which
         * a transient carrier hiccup does not survive but a blackholed tunnel does.
         */
        const val MAX_PING_FAILURES = 3
        /**
         * Health-check endpoints, tried in order until one answers.
         *
         * Google stays first — it is reachable from Iran and returns an empty
         * 204, which is the cheapest possible probe. The rest exist so a single
         * endpoint having a bad day cannot paint "Connection degraded" over a
         * working tunnel.
         */
        val PING_URLS = arrayOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
            // DNS-free last resort. Every entry above needs a working resolver,
            // so a tunnel that carries packets but has broken DNS would look
            // completely dead and get torn down by the verification gate. A raw
            // IP literal proves the data plane on its own.
            "https://1.1.1.1/cdn-cgi/trace",
        )
        val IP_INFO_URLS = arrayOf(
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://one.one.one.one/cdn-cgi/trace",
            "https://1.1.1.1/cdn-cgi/trace",
            "https://api64.ipify.org",
            "https://api.ipify.org",
        )
        val IP_ADDRESS = Regex("^[0-9A-Fa-f:.]+$")
        /**
         * Geolocation endpoints for an address the core measured. `%s` is the IP.
         *
         * Both are HTTPS, keyless and Cloudflare-fronted (so reachable from Iran),
         * and both were verified from an uncensored host returning IR for
         * 104.28.214.161 and 104.28.214.167 — the real WARP exits that a
         * registration-based lookup wrongly called US.
         */
        val COUNTRY_LOOKUP_URLS = arrayOf(
            "https://get.geojs.io/v1/ip/country/%s.json",
            "https://ipwho.is/%s?fields=country_code",
        )
        /** Matches `"country":"IR"` and `"country_code":"IR"` alike. */
        val COUNTRY_CODE_JSON = Regex("\"country(?:_code)?\"\\s*:\\s*\"([A-Za-z]{2})\"")
        const val IP_TIMEOUT_MS = 5_000
        const val IP_FETCH_ATTEMPTS = 3
        const val IP_RETRY_DELAY_MS = 300L
        const val SETTINGS = "settings"
        const val DEFAULT_SCAN = "default_scan"
        const val DEFAULT_SCAN_MODE = "default_scan_mode"
        const val ENDPOINT_DISCOVERY = "endpoint_discovery"
        const val DEFAULT_MASQUE_TRANSPORT = "default_masque_transport"
        const val OBFUSCATION_PROFILE = "obfuscation_profile"
        const val OBFUSCATION_JC = "obfuscation_jc"
        const val OBFUSCATION_JMIN = "obfuscation_jmin"
        const val OBFUSCATION_JMAX = "obfuscation_jmax"
        const val OBFUSCATION_I1 = "obfuscation_i1"
        const val OBFUSCATION_I2 = "obfuscation_i2"
        const val MANUAL_ENDPOINT = "manual_endpoint"
        const val RETRY_OBFUSCATION = "retry_obfuscation_profiles"
        const val TLS_CURVE_PRESET = "tls_curve_preset"
        const val WIREGUARD_DATA_CHECK = "wireguard_data_check"
        const val KILL_SWITCH = "kill_switch"
        const val LAN_SHARING = "lan_sharing"
        const val LAN_BYPASS = "lan_bypass"
        const val DEFAULT_PROTOCOL = "default_protocol"
        const val LOG_LEVEL = "log_level"
        const val PERF_PROFILE = "perf_profile"
        const val H2_FRAGMENTATION = "h2_fragmentation"
        const val ERROR = 0xFFFFB4AB.toInt()
        const val DISABLED_ALPHA = 0.48f
    }
}
