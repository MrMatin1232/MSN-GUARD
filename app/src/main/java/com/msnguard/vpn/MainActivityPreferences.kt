package com.msnguard.vpn

import android.content.Context
import com.msnguard.vpn.MainActivity.Protocol
import com.msnguard.vpn.MainActivity.ScanTarget
import com.msnguard.vpn.MainActivity.ScanMode
import com.msnguard.vpn.MainActivity.MasqueTransport
import com.msnguard.vpn.MainActivity.EndpointDiscovery
import com.msnguard.vpn.MainActivity.ObfuscationProfile
import com.msnguard.vpn.MainActivity.TlsCurvePreset
import com.msnguard.vpn.MainActivity.LogLevel
import com.msnguard.vpn.MainActivity.PerfProfile
import com.msnguard.vpn.MainActivity.H2Fragmentation
import com.msnguard.vpn.MainActivity.Companion.SETTINGS
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_SCAN
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_SCAN_MODE
import com.msnguard.vpn.MainActivity.Companion.ENDPOINT_DISCOVERY
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_MASQUE_TRANSPORT
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_PROFILE
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JC
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JMIN
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JMAX
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_I1
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_I2
import com.msnguard.vpn.MainActivity.Companion.MANUAL_ENDPOINT
import com.msnguard.vpn.MainActivity.Companion.RETRY_OBFUSCATION
import com.msnguard.vpn.MainActivity.Companion.TLS_CURVE_PRESET
import com.msnguard.vpn.MainActivity.Companion.WIREGUARD_DATA_CHECK
import com.msnguard.vpn.MainActivity.Companion.KILL_SWITCH
import com.msnguard.vpn.MainActivity.Companion.LAN_SHARING
import com.msnguard.vpn.MainActivity.Companion.LAN_BYPASS
import com.msnguard.vpn.MainActivity.Companion.DEFAULT_PROTOCOL
import com.msnguard.vpn.MainActivity.Companion.LOG_LEVEL
import com.msnguard.vpn.MainActivity.Companion.PERF_PROFILE
import com.msnguard.vpn.MainActivity.Companion.H2_FRAGMENTATION

/**
 * Typed access to the settings [android.content.SharedPreferences].
 *
 * Every reader here is total: an unknown or corrupt stored value falls back to
 * the documented default rather than throwing.
 */

internal fun MainActivity.defaultScan(): ScanTarget {
    val name = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString(DEFAULT_SCAN, ScanTarget.IPV4.coreName)
    return ScanTarget.entries.firstOrNull { it.coreName == name } ?: ScanTarget.IPV4
}

internal fun MainActivity.defaultScanMode(): ScanMode {
    val name = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString(DEFAULT_SCAN_MODE, ScanMode.BALANCED.coreName)
    return ScanMode.entries.firstOrNull { it.coreName == name } ?: ScanMode.BALANCED
}

internal fun MainActivity.defaultEndpointDiscovery(): EndpointDiscovery {
    val name = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getString(ENDPOINT_DISCOVERY, EndpointDiscovery.CACHE.coreName)
    return EndpointDiscovery.entries.firstOrNull { it.coreName == name } ?: EndpointDiscovery.CACHE
}

internal fun MainActivity.defaultMasqueTransport(): MasqueTransport {
    val name = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getString(DEFAULT_MASQUE_TRANSPORT, MasqueTransport.H3.coreName)
    return MasqueTransport.entries.firstOrNull { it.coreName == name } ?: MasqueTransport.H3
}

internal fun MainActivity.preferences() = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

internal fun MainActivity.obfuscationProfile(): ObfuscationProfile = preferences()
    .getString(OBFUSCATION_PROFILE, ObfuscationProfile.BALANCED.coreName)
    ?.let { name -> ObfuscationProfile.entries.firstOrNull { it.coreName == name } }
    ?: ObfuscationProfile.BALANCED

internal fun MainActivity.manualEndpoint(): String? = preferences().getString(MANUAL_ENDPOINT, null)?.takeIf(String::isNotBlank)

internal fun MainActivity.retryObfuscationProfiles(): Boolean = preferences().getBoolean(RETRY_OBFUSCATION, true)

internal fun MainActivity.advancedObfuscationSummary(): String =
    listOf(OBFUSCATION_JC, OBFUSCATION_JMIN, OBFUSCATION_JMAX, OBFUSCATION_I1, OBFUSCATION_I2)
        .any { preferences().getString(it, "").orEmpty().isNotBlank() }
        .let { if (it) "Custom" else "Preset" }

internal fun MainActivity.tlsCurvePreset(): TlsCurvePreset = preferences()
    .getString(TLS_CURVE_PRESET, TlsCurvePreset.CHROME.coreName)
    ?.let { name -> TlsCurvePreset.entries.firstOrNull { it.coreName == name } }
    ?: TlsCurvePreset.CHROME

internal fun MainActivity.wireGuardDataCheck(): Boolean = preferences().getBoolean(WIREGUARD_DATA_CHECK, true)

internal fun MainActivity.killSwitchEnabled(): Boolean = preferences().getBoolean(KILL_SWITCH, false)

internal fun MainActivity.lanSharingEnabled(): Boolean = preferences().getBoolean(LAN_SHARING, false)

internal fun MainActivity.lanBypassEnabled(): Boolean {
    if (!preferences().contains(LAN_BYPASS) && lanSharingEnabled()) {
        preferences().edit().putBoolean(LAN_BYPASS, true).apply()
        return true
    }
    return preferences().getBoolean(LAN_BYPASS, false)
}

internal fun MainActivity.savedProtocol(): Protocol {
    val name = preferences().getString(DEFAULT_PROTOCOL, Protocol.MASQUE.coreName)
    return Protocol.entries.firstOrNull { it.coreName == name && it.androidAvailable } ?: Protocol.MASQUE
}

internal fun MainActivity.logLevel(): LogLevel = preferences()
    .getString(LOG_LEVEL, LogLevel.INFO.coreName)
    ?.let { name -> LogLevel.entries.firstOrNull { it.coreName == name } }
    ?: LogLevel.INFO

internal fun MainActivity.perfProfile(): PerfProfile = preferences()
    .getString(PERF_PROFILE, PerfProfile.AUTO.coreName)
    ?.let { name -> PerfProfile.entries.firstOrNull { it.coreName == name } }
    ?: PerfProfile.AUTO

internal fun MainActivity.h2Fragmentation(): H2Fragmentation = preferences()
    .getString(H2_FRAGMENTATION, H2Fragmentation.ON.coreName)
    ?.let { name -> H2Fragmentation.entries.firstOrNull { it.coreName == name } }
    ?: H2Fragmentation.ON

/**
 * Psiphon's local SOCKS port, fixed.
 *
 * Still needed by openTunnelConnection(): in Psiphon VPN mode tun2socks and
 * the health check both dial this listener. No longer user-configurable —
 * the TUN is created before Psiphon starts, so the port must be known up
 * front.
 */
internal fun MainActivity.socksPort(): Int = CoreConfig.SOCKS_PORT

internal fun MainActivity.splitTunnelSummary(): String {
    val settings = SplitTunnelSettings(this)
    val count = settings.packages().size
    return when (settings.mode()) {
        SplitTunnelSettings.Mode.ALL -> "All apps use MSN-GUARD"
        SplitTunnelSettings.Mode.INCLUDE -> "Only $count selected app${if (count == 1) "" else "s"}"
        SplitTunnelSettings.Mode.EXCLUDE -> "Exclude $count selected app${if (count == 1) "" else "s"}"
    }
}
