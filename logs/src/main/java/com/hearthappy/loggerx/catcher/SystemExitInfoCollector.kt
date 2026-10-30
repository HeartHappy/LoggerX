package com.hearthappy.loggerx.catcher

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.hearthappy.loggerx.core.CatchConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object SystemExitInfoCollector {
    data class Result(val records: List<CrashRecord>, val newestTimestamp: Long)

    fun collect(application: Application, afterTimestamp: Long, config: CatchConfig): Result {
        if (!config.captureSystemExitInfo) {
            return Result(emptyList(), afterTimestamp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return collectApi30(application, afterTimestamp, config.maxReportBytes)
        }
        return TombstoneCollector.collect(application, afterTimestamp, config.maxReportBytes)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectApi30(application: Application, afterTimestamp: Long, maxReportBytes: Int): Result {
        val activityManager = application.getSystemService(ActivityManager::class.java)
            ?: return Result(emptyList(), afterTimestamp)
        val records = mutableListOf<CrashRecord>()
        var newestTimestamp = afterTimestamp
        val exitReasons = activityManager.getHistoricalProcessExitReasons(application.packageName, 0, 32)
            .asSequence()
            .filter { it.timestamp > afterTimestamp }
            .sortedBy { it.timestamp }
            .toList()
        for (exitInfo in exitReasons) {
            newestTimestamp = maxOf(newestTimestamp, exitInfo.timestamp)
            val source = when (exitInfo.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashSource.SYSTEM_NATIVE
                ApplicationExitInfo.REASON_ANR -> CrashSource.SYSTEM_ANR
                ApplicationExitInfo.REASON_CRASH -> CrashSource.SYSTEM_EXIT
                else -> null
            } ?: continue
            val trace = runCatching {
                exitInfo.traceInputStream?.use { readLimited(it, maxReportBytes) }.orEmpty()
            }.getOrDefault("")
            val kind = when (source) {
                CrashSource.SYSTEM_NATIVE -> CrashKind.NATIVE
                CrashSource.SYSTEM_ANR -> CrashKind.ANR
                CrashSource.SYSTEM_EXIT -> CrashKind.JVM
                else -> CrashKind.EXIT
            }
            records += CrashRecord(
                source = source,
                kind = kind,
                occurredAt = exitInfo.timestamp,
                sessionId = "",
                processName = exitInfo.processName.orEmpty(),
                threadName = "",
                errorType = reasonName(exitInfo.reason),
                errorMessage = exitInfo.description.orEmpty(),
                stackTrace = trace,
                details = buildString {
                    appendLine("status=${exitInfo.status}")
                    appendLine("importance=${exitInfo.importance}")
                    appendLine("pss=${exitInfo.pss}")
                    appendLine("rss=${exitInfo.rss}")
                    append("packageUid=${exitInfo.packageUid}")
                }
            )
        }
        return Result(records, newestTimestamp)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            else -> "REASON_$reason"
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val safeLimit = maxBytes.coerceIn(64 * 1024, 4 * 1024 * 1024)
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream(minOf(safeLimit, 64 * 1024))
        while (output.size() < safeLimit) {
            val count = input.read(buffer, 0, minOf(buffer.size, safeLimit - output.size()))
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val text = if (isLikelyText(bytes)) {
            String(bytes, StandardCharsets.UTF_8)
        } else {
            formatBinaryTombstone(bytes)
        }
        return if (output.size() >= safeLimit) "$text\n\n[LoggerX: system trace truncated]" else text
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return false
        val printable = bytes.count { byte ->
            val value = byte.toInt() and 0xff
            value == 9 || value == 10 || value == 13 || value in 32..126
        }
        return printable.toDouble() / bytes.size >= 0.85
    }

    private fun formatBinaryTombstone(bytes: ByteArray): String {
        val strings = linkedSetOf<String>()
        val current = StringBuilder()
        fun flush() {
            val value = current.toString().trim()
            if (value.length >= 4) strings += value
            current.setLength(0)
        }
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value == 9 || value == 10 || value == 13 || value in 32..126) {
                current.append(value.toChar())
            } else {
                flush()
            }
        }
        flush()
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return buildString {
            appendLine("Android system tombstone is protobuf encoded.")
            appendLine("raw_size=${bytes.size}")
            appendLine("raw_sha256=$sha256")
            appendLine("printable tombstone fields:")
            for (value in strings) appendLine(value)
        }
    }
}
