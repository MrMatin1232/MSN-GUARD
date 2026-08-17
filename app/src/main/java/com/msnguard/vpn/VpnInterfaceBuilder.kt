package com.msnguard.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.InetAddress

/**
 * Everything that decides what the Android TUN interface looks like.
 *
 * WHY THIS IS ITS OWN FILE. These were private members of MsnGuardVpnService,
 * which is a lifecycle class: it owns threads, a foreground notification, the
 * Psiphon controller and the traffic accounting. Interface construction is a
 * different job with a different failure mode — get it wrong and the device
 * blackholes — and it needs nothing from the service except a Context, so it is
 * separable and now separated. The service passes itself in explicitly.
 *
 * Every rule below is load-bearing; the comments explain which failure each one
 * prevents. Read them before reordering anything.
 */

private const val LOG_TAG = "MsnGuardVpnBuilder"

/** Public resolvers forced ahead of anything the carrier or config supplies. */
private val FORCED_DNS = listOf("1.1.1.1", "8.8.8.8")

/**
 * Applies the user's split-tunnelling choice.
 *
 * INCLUDE mode deliberately does NOT call addDisallowedApplication: our own
 * package is excluded by default when an allow-list is in use, and mixing
 * addAllowedApplication with addDisallowedApplication on one Builder throws.
 *
 * Every other mode MUST exclude our own package, otherwise the core's control
 * sockets route into the tunnel they are trying to build and connect deadlocks.
 */
internal fun VpnService.Builder.applySplitTunneling(service: VpnService): VpnService.Builder {
    val settings = SplitTunnelSettings(service)
    val mode = settings.mode()
    val packages = settings.packages()
    val ownPackage = service.packageName

    if (mode == SplitTunnelSettings.Mode.ALL) {
        addDisallowedApplication(ownPackage)
        return this
    }

    if (packages.isEmpty()) {
        check(mode != SplitTunnelSettings.Mode.INCLUDE) {
            "No apps selected for tunnel. Connection aborted for safety."
        }
        // EXCLUDE with an empty list: nothing to exclude beyond ourselves.
        addDisallowedApplication(ownPackage)
        return this
    }

    var addedCount = 0
    packages.forEach { pkg ->
        try {
            when (mode) {
                SplitTunnelSettings.Mode.INCLUDE -> {
                    addAllowedApplication(pkg)
                    addedCount++
                }
                SplitTunnelSettings.Mode.EXCLUDE -> {
                    if (pkg != ownPackage) {
                        addDisallowedApplication(pkg)
                        addedCount++
                    }
                }
                SplitTunnelSettings.Mode.ALL -> Unit // handled above
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG, "Split tunnel skipped missing app: $pkg")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to add $pkg to split tunnel: ${e.message}")
        }
    }

    if (mode == SplitTunnelSettings.Mode.INCLUDE && addedCount == 0) {
        error("Selected apps are no longer installed. Connection aborted.")
    }

    if (mode == SplitTunnelSettings.Mode.EXCLUDE) {
        addDisallowedApplication(ownPackage)
    }

    ConnectionLog.record("Split tunnel ${mode.label.lowercase()}: $addedCount app(s)")
    return this
}

internal fun VpnService.Builder.applyLanAccess(
    service: VpnService,
    addresses: NativeCore.TunnelAddresses,
): VpnService.Builder {
    if (!lanBypassEnabled(service)) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        ConnectionLog.record("LAN access uses system local routes on Android 12 and older")
        return this
    }
    val ranges = mutableListOf(
        "10.0.0.0/8",
        "192.168.0.0/16",
        "fc00::/7",
        "fe80::/10",
    )
    // WARP / Zero Trust device and gateway addresses live in 172.16.0.0/12.
    // Excluding that range would leak org DNS/gateway onto the LAN, so it is only
    // bypassed when we are not on a WARP CGNAT identity.
    if (!isWarpCgnat(addresses)) {
        ranges.add(1, "172.16.0.0/12")
    }
    ranges.forEach { cidr ->
        val (address, prefix) = cidr.split('/')
        excludeRoute(IpPrefix(InetAddress.getByName(address), prefix.toInt()))
    }
    ConnectionLog.record("LAN routes bypass the VPN")
    return this
}

/**
 * Upstream v0.8.0 renamed the LAN preference from `lan_sharing` to `lan_bypass`
 * and migrates the old value on first read. Kept verbatim so the service and the
 * activity agree on which key is authoritative.
 */
internal fun lanBypassEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    if (!prefs.contains("lan_bypass") && prefs.getBoolean("lan_sharing", false)) {
        prefs.edit().putBoolean("lan_bypass", true).apply()
        return true
    }
    return prefs.getBoolean("lan_bypass", false)
}

/**
 * Adds the tunnel's own addresses and default routes.
 *
 * Replaces a hardcoded /32 + /128 pair: v0.8.0 identities can carry a real
 * prefix length, and a WARP identity without a v6 address must not get a v6
 * default route — doing so blackholes every v6 lookup on the device.
 */
internal fun VpnService.Builder.applyTunnelAddresses(
    addresses: NativeCore.TunnelAddresses,
): VpnService.Builder {
    val v4 = parseTunnelAddress(addresses.ipv4, 32)
        ?: error("Zero Trust identity has no usable IPv4 address")
    addAddress(v4.first, v4.second)
    addRoute("0.0.0.0", 0)
    val v6 = parseTunnelAddress(addresses.ipv6, 128)
    if (v6 != null) {
        addAddress(v6.first, v6.second)
        addRoute("::", 0)
    }
    return this
}

internal fun VpnService.Builder.applyGatewayProxy(
    config: String,
    addresses: NativeCore.TunnelAddresses,
): VpnService.Builder {
    if (!JSONObject(config).optBoolean("gateway", false)) return this
    val parsed = parseSocketAddress(addresses.gatewayProxy) ?: return this
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setHttpProxy(ProxyInfo.buildDirectProxy(parsed.first, parsed.second))
        ConnectionLog.record("Zero Trust gateway ${parsed.first}:${parsed.second}")
    } else {
        ConnectionLog.record("Gateway filtering in VPN mode needs Android 10 or newer")
    }
    return this
}

/**
 * Forces public resolvers and filters carrier DNS out entirely.
 *
 * This is load-bearing for Psiphon. Carrier DNS on Iranian mobile networks is
 * both censored and rejected by Psiphon's SOCKS5 (reply 5), so public resolvers
 * go first and any carrier-supplied server is dropped rather than merely appended
 * after. Upstream instead uses 1.1.1.1/1.0.0.1 only as a *fallback* when the
 * config lists nothing, which lets carrier DNS through.
 */
internal fun VpnService.Builder.applyDns(
    config: String,
    addresses: NativeCore.TunnelAddresses,
): VpnService.Builder {
    FORCED_DNS.forEach { addDnsServer(InetAddress.getByName(it)) }

    // Advertise a v6 resolver when the identity has a v6 address, otherwise
    // v6-only lookups have nowhere to go.
    if (addresses.ipv6.isNotBlank()) {
        runCatching { addDnsServer(InetAddress.getByName("2606:4700:4700::1111")) }
    }

    // Anything the config adds on top (non-Psiphon protocols).
    JSONObject(config).optString("dns_servers")
        .split(',', ';', ' ', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { entry ->
            val address = when {
                entry.startsWith('[') -> entry.substringAfter('[').substringBefore(']')
                entry.count { it == ':' } == 1 -> entry.substringBefore(':')
                else -> entry
            }
            runCatching { InetAddress.getByName(address) }.getOrNull()
        }
        .distinct()
        .filter { it.hostAddress !in FORCED_DNS }
        .forEach { addDnsServer(it) }

    ConnectionLog.record("DNS forced to public resolvers, carrier DNS excluded")
    return this
}

/** `1.2.3.4` or `1.2.3.4/24`; falls back to [defaultPrefix] when none is given. */
internal fun parseTunnelAddress(raw: String, defaultPrefix: Int): Pair<InetAddress, Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val host = trimmed.substringBefore('/')
    val prefix = trimmed.substringAfter('/', missingDelimiterValue = "")
        .toIntOrNull() ?: defaultPrefix
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    val maxPrefix = if (address.address.size == 4) 32 else 128
    return address to prefix.coerceIn(0, maxPrefix)
}

/** `host:port` or `[v6]:port`. */
internal fun parseSocketAddress(raw: String): Pair<String, Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith('[')) {
        val host = trimmed.substringAfter('[').substringBefore(']')
        val port = trimmed.substringAfter("]:", "").toIntOrNull() ?: return null
        host to port
    } else {
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0) return null
        val host = trimmed.substring(0, separator)
        val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
        host to port
    }
}

/** True when the identity looks like a WARP CGNAT address (172.16.0.0/12). */
internal fun isWarpCgnat(addresses: NativeCore.TunnelAddresses): Boolean {
    val host = addresses.ipv4.substringBefore('/').trim()
    val octets = host.split('.')
    if (octets.size == 4 && octets[0] == "172") {
        val second = octets[1].toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    return addresses.gatewayProxy.contains("172.16.") ||
        addresses.gatewayProxy.contains("172.17.") ||
        addresses.gatewayProxy.contains("172.18.")
}
