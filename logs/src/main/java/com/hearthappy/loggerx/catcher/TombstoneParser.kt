package com.hearthappy.loggerx.catcher

import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

internal object TombstoneParser {
    private val processMarker = Regex(""">>>\s*(\S+)\s*<<<""")
    private val cmdline = Regex("""(?m)^Cmdline:\s*(\S+)\s*$""")
    private val threadName = Regex("""(?m)^pid:\s*\d+,\s*tid:\s*\d+,\s*name:\s*(.*?)\s*>>>""")
    private val timestamp = Regex("""Timestamp:\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")
    private val signal = Regex("""signal\s+\d+\s+\(([^)]+)\)""")
    private val abortMessage = Regex("""Abort message:\s+'(.*)'""")

    fun matchesPackage(text: String, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return processNames(text).any { it == packageName || it.startsWith("$packageName:") }
    }

    fun toRecord(fileName: String, text: String, packageName: String, fallbackTime: Long, maxBytes: Int): CrashRecord? {
        if (!matchesPackage(text, packageName)) return null
        val occurredAt = parseTimestamp(text).takeIf { it > 0L } ?: fallbackTime.takeIf { it > 0L } ?: return null
        val signalName = signal.find(text)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "DEBUGGERD" }
        val process = processMarker.find(text)?.groupValues?.getOrNull(1) ?: packageName
        return CrashRecord(
            source = CrashSource.SYSTEM_NATIVE,
            kind = CrashKind.NATIVE,
            occurredAt = occurredAt,
            sessionId = fileName,
            processName = process,
            threadName = threadName.find(text)?.groupValues?.getOrNull(1).orEmpty().trim(),
            errorType = signalName,
            errorMessage = abortMessage.find(text)?.groupValues?.getOrNull(1) ?: signalName,
            stackTrace = truncate(text, maxBytes),
            details = "tombstone=$fileName"
        )
    }

    private fun processNames(text: String): Sequence<String> {
        return processMarker.findAll(text).map { it.groupValues[1] } + cmdline.findAll(text).map { it.groupValues[1] }
    }

    private fun parseTimestamp(text: String): Long {
        val value = timestamp.find(text)?.groupValues?.getOrNull(1) ?: return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    private fun truncate(text: String, maxBytes: Int): String {
        val limit = maxBytes.coerceAtLeast(1)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= limit) return text
        return String(bytes.copyOf(limit), StandardCharsets.UTF_8) + "\n[LoggerX: tombstone truncated]"
    }
}
