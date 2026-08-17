package com.msnguard.vpn

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The in-app event ring buffer, optionally mirrored to a file on disk.
 *
 * WHY THIS IS ITS OWN FILE NOW. It used to sit as a top-level object at the
 * bottom of MsnGuardVpnService.kt, below 1,600 lines of service, which made it
 * read as service-private state. It is not: the activity binds the file sink and
 * renders snapshots, Tun2SocksManager writes to it from the native logger
 * callback, and every Psiphon notice lands here. Four owners, so it lives on its
 * own.
 *
 * The timestamp formatter is allocated once rather than per line. [record] is
 * called on every Psiphon diagnostic notice — dozens per connect attempt — and
 * each `SimpleDateFormat(...)` construction re-parses the pattern and clones a
 * Calendar. SimpleDateFormat is not thread safe, which is fine here because
 * every entry point on this object is `@Synchronized` and there is exactly one
 * instance behind that lock.
 */
object ConnectionLog {
    private const val MAX_ENTRIES = 100
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val entries = ArrayDeque<String>()
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var sink: File? = null

    /**
     * Mirror the ring buffer to [file] so logs survive the process being killed.
     * Capped and self-truncating so it cannot grow unbounded.
     */
    @Synchronized
    fun bind(file: File) {
        sink = file
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            file.delete()
        }
    }

    @Synchronized
    fun record(message: String) {
        val line = "${timestamp.format(Date())}  $message"
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
        runCatching { sink?.appendText(line + "\n") }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
