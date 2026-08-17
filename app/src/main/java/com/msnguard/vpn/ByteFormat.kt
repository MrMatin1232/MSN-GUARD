package com.msnguard.vpn

import java.util.Locale

/**
 * Human-readable byte and byte-rate formatting.
 *
 * Extracted from MsnGuardVpnService, which held these as two private methods on
 * a 1,600-line class. They are pure functions of a Long, they are needed by
 * anything that renders a counter, and being pure they are unit-testable without
 * an Android context.
 *
 * Binary units (1024) deliberately, matching what the traffic screen and the
 * notification have always shown; switching to decimal units would silently
 * change every historical number the user has seen.
 */
internal object ByteFormat {

    private const val KIB = 1_024L
    private const val MIB = 1_048_576L
    private const val GIB = 1_073_741_824L

    fun bytes(value: Long): String = when {
        value < KIB -> "$value B"
        value < MIB -> "${value / KIB} KB"
        value < GIB -> "${value / MIB} MB"
        else -> String.format(Locale.US, "%.2f GB", value / GIB.toDouble())
    }

    fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond < KIB -> "$bytesPerSecond B/s"
        bytesPerSecond < MIB -> "${bytesPerSecond / KIB} KB/s"
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / MIB.toDouble())
    }
}
