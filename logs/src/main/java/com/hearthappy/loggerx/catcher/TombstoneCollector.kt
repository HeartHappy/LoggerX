package com.hearthappy.loggerx.catcher

import android.app.Application
import android.util.Log
import com.hearthappy.loggerx.LoggerX
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object TombstoneCollector {
    private const val TOMBSTONE_DIR = "/data/tombstones"
    private const val FILE_MARKER = "-----LOGGERX_TOMBSTONE "
    private const val ROOT_TIMEOUT_MS = 1500L
    private val tombstoneName = Regex("""^tombstone_\d{2}(\.pb)?$""")
    private val suCandidates = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su"
    )

    fun collect(application: Application, afterTimestamp: Long, maxReportBytes: Int): SystemExitInfoCollector.Result {
        return runCatching {
            val packageName = application.packageName
            val records = readDirect(packageName, afterTimestamp, maxReportBytes)
                ?: readWithRoot(packageName, afterTimestamp, maxReportBytes)
            val newest = records.maxOfOrNull { it.occurredAt } ?: afterTimestamp
            SystemExitInfoCollector.Result(records, maxOf(newest, afterTimestamp))
        }.getOrElse { throwable ->
            Log.w(LoggerX.TAG, "tombstone collect skipped: ${throwable.javaClass.simpleName}")
            SystemExitInfoCollector.Result(emptyList(), afterTimestamp)
        }
    }

    private fun readDirect(packageName: String, afterTimestamp: Long, maxReportBytes: Int): List<CrashRecord>? {
        val directory = File(TOMBSTONE_DIR)
        if (!directory.isDirectory || !directory.canRead()) return null
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return null
        val tombstones = files.filter { it.isFile && tombstoneName.matches(it.name) }
        if (tombstones.isEmpty()) return emptyList()
        val records = mutableListOf<CrashRecord>()
        var failures = 0
        for (file in tombstones) {
            val text = runCatching { readLimited(file, maxReportBytes) }.getOrElse {
                failures++
                null
            } ?: continue
            val record = TombstoneParser.toRecord(file.name, text, packageName, file.lastModified(), maxReportBytes) ?: continue
            if (record.occurredAt > afterTimestamp) records += record
        }
        if (records.isEmpty() && failures == tombstones.size) return null
        return records
    }

    private fun readWithRoot(packageName: String, afterTimestamp: Long, maxReportBytes: Int): List<CrashRecord> {
        val su = findSu()
        val script = dumpScript()
        val limit = dumpLimit(maxReportBytes)
        val dump = exec(listOf(su, "-c", script), ROOT_TIMEOUT_MS, limit)
            ?: exec(listOf(su, "0", "/system/bin/sh", "-c", script), ROOT_TIMEOUT_MS, limit)
            ?: return emptyList()
        val records = mutableListOf<CrashRecord>()
        for (part in dump.split(FILE_MARKER)) {
            val lineBreak = part.indexOf('\n')
            if (lineBreak <= 0) continue
            val name = part.substring(0, lineBreak).trim()
            if (!tombstoneName.matches(name)) continue
            val body = part.substring(lineBreak + 1)
            val record = TombstoneParser.toRecord(name, body, packageName, 0L, maxReportBytes) ?: continue
            if (record.occurredAt > afterTimestamp) records += record
        }
        return records
    }

    private fun findSu(): String {
        return suCandidates.firstOrNull { path ->
            runCatching {
                val file = File(path)
                file.isFile && file.canExecute()
            }.getOrDefault(false)
        } ?: "su"
    }

    private fun dumpScript(): String {
        return "for f in $TOMBSTONE_DIR/tombstone_*; do " +
            "[ -f \"\$f\" ] || continue; " +
            "echo \"$FILE_MARKER\${f##*/}\"; " +
            "cat \"\$f\"; " +
            "echo; done"
    }

    private fun exec(argv: List<String>, timeoutMs: Long, maxBytes: Int): String? {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (io: IOException) {
            Log.w(LoggerX.TAG, "su unavailable, tombstone root read skipped")
            return null
        } catch (security: SecurityException) {
            Log.w(LoggerX.TAG, "su blocked, tombstone root read skipped")
            return null
        }
        val output = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val buffer = ByteArray(8192)
                val input = process.inputStream
                while (output.size() < maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
                runCatching { input.close() }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = waitFor(process, timeoutMs)
        reader.join(1000)
        if (!finished) process.destroy()
        val text = output.toString(StandardCharsets.UTF_8.name())
        if (text.contains(FILE_MARKER)) return text
        if (!finished) return null
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        process.destroy()
        if (exitCode != 0) return null
        return text.takeIf { it.isNotBlank() }
    }

    private fun waitFor(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    process.destroy()
                    return false
                }
            }
        }
        process.destroy()
        return false
    }

    private fun readLimited(file: File, maxBytes: Int): String {
        val limit = maxBytes.coerceIn(64 * 1024, 4 * 1024 * 1024)
        file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun dumpLimit(maxReportBytes: Int): Int {
        return maxReportBytes.coerceIn(64 * 1024, 1024 * 1024) * 8
    }
}
