package com.hearthappy.loggerx.catcher

import android.app.Application
import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.CatchConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object CrashEmergencyStore {
    data class PendingRecord(val file: File, val record: CrashRecord)

    private lateinit var pendingDir: File
    private lateinit var reportDir: File
    private lateinit var nativeDir: File
    private var maxReportBytes: Int = 1024 * 1024
    private var maxPendingReports: Int = 32

    @Synchronized
    fun initialize(application: Application, config: CatchConfig) {
        val root = File(application.noBackupFilesDir, "loggerx/catch")
        pendingDir = ensureDirectory(File(root, "pending"))
        reportDir = ensureDirectory(File(root, "reports"))
        nativeDir = ensureDirectory(File(root, "native"))
        updateConfig(config)
        trimPendingFiles()
    }

    fun nativeCrashDirectory(): File = nativeDir

    @Synchronized
    fun updateConfig(config: CatchConfig) {
        maxReportBytes = config.maxReportBytes.coerceIn(64 * 1024, 4 * 1024 * 1024)
        maxPendingReports = config.maxPendingReports.coerceIn(4, 128)
    }

    @Synchronized
    fun writePending(record: CrashRecord, prefix: String = "crash"): File? {
        if (!::pendingDir.isInitialized) return null
        return runCatching {
            trimPendingFiles()
            val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val finalFile = File(pendingDir, "${safePrefix}_${record.occurredAt}_${UUID.randomUUID()}.json")
            val tempFile = File(pendingDir, ".${finalFile.name}.tmp")
            writeDurably(tempFile, serializePending(record))
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            finalFile
        }.onFailure {
            Log.e(LoggerX.TAG, "writePending crash report failed: ${it.message}")
        }.getOrNull()
    }

    @Synchronized
    fun loadPending(): List<PendingRecord> {
        if (!::pendingDir.isInitialized) return emptyList()
        return pendingDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    PendingRecord(file, CrashRecord.fromJson(file.readText(StandardCharsets.UTF_8)))
                }.onFailure {
                    Log.e(LoggerX.TAG, "read pending crash report failed: ${file.name}: ${it.message}")
                    file.delete()
                }.getOrNull()
            }
            .toList()
    }

    @Synchronized
    fun deletePending(fileName: String) {
        if (!::pendingDir.isInitialized) return
        pendingDir.listFiles()?.firstOrNull { it.name == fileName }?.delete()
    }

    @Synchronized
    fun archive(eventId: String, content: String): File {
        val target = File(reportDir, "$eventId.txt")
        if (!target.exists()) {
            val temp = File(reportDir, ".$eventId.tmp")
            writeDurably(temp, limitUtf8(content))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        return target
    }

    private fun writeDurably(file: File, content: String) {
        FileOutputStream(file).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun limitUtf8(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxReportBytes) return value
        val suffix = "\n\n[LoggerX: report truncated at $maxReportBytes bytes]"
        val suffixBytes = suffix.toByteArray(StandardCharsets.UTF_8)
        val kept = bytes.copyOf((maxReportBytes - suffixBytes.size).coerceAtLeast(0))
        return String(kept, StandardCharsets.UTF_8) + suffix
    }

    private fun serializePending(record: CrashRecord): String {
        val original = record.toJson()
        if (original.toByteArray(StandardCharsets.UTF_8).size <= maxReportBytes) return original
        val reduced = record.copy(
            errorMessage = limitField(record.errorMessage, maxReportBytes / 10),
            stackTrace = limitField(record.stackTrace, maxReportBytes / 2),
            details = limitField(record.details, maxReportBytes / 5)
        ).toJson()
        if (reduced.toByteArray(StandardCharsets.UTF_8).size <= maxReportBytes) return reduced
        return record.copy(
            errorMessage = limitField(record.errorMessage, 4096),
            stackTrace = limitField(record.stackTrace, maxReportBytes / 4),
            details = "[LoggerX: emergency details truncated]"
        ).toJson()
    }

    private fun limitField(value: String, byteLimit: Int): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= byteLimit) return value
        return String(bytes.copyOf(byteLimit.coerceAtLeast(0)), StandardCharsets.UTF_8) + "\n[truncated]"
    }

    private fun trimPendingFiles() {
        val files = pendingDir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedByDescending { it.lastModified() }
        for (file in files.drop(maxPendingReports - 1)) file.delete()
        for (file in pendingDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }) file.delete()
    }

    private fun ensureDirectory(directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create crash directory: ${directory.absolutePath}")
        }
        return directory
    }
}
