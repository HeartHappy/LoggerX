package com.hearthappy.loggerx.catcher

import com.hearthappy.loggerx.db.LogDbManager
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal object CrashReportImporter {
    private const val MERGE_WINDOW_MS = 20_000L
    private const val NATIVE_CONTEXT_WINDOW_MS = 5 * 60_000L
    private const val SUMMARY_STACK_LINES = 24

    data class Input(val record: CrashRecord, val pendingFile: File?)
    data class Result(val importedCount: Int, val systemRecordsSucceeded: Boolean)

    fun import(inputs: List<Input>): Result {
        var importedCount = 0
        var systemRecordsSucceeded = true
        for (group in groupRelated(inputs)) {
            val imported = importGroup(group)
            if (imported) {
                importedCount++
                for (file in group.mapNotNull { it.pendingFile }) file.delete()
            } else if (group.any { it.pendingFile == null }) {
                systemRecordsSucceeded = false
            }
        }
        return Result(importedCount, systemRecordsSucceeded)
    }

    private fun importGroup(group: List<Input>): Boolean {
        if (group.isEmpty()) return true
        val records = group.map { it.record }.sortedBy { it.occurredAt }
        val occurredAt = records.first().occurredAt
        val session = records.firstOrNull { it.sessionId.isNotBlank() }?.sessionId.orEmpty()
        val kind = records.firstOrNull { it.kind != CrashKind.NATIVE_CONTEXT }?.kind ?: CrashKind.NATIVE_CONTEXT
        val eventId = digest(
            listOf(
                session.ifBlank { records.first().processName },
                kind.name,
                (occurredAt / 10_000L).toString(),
                records.firstOrNull { it.errorType.isNotBlank() }?.errorType.orEmpty()
            ).joinToString("|")
        )
        val reportFile = CrashEmergencyStore.archive(eventId, formatReport(eventId, records))
        val method = records.map { it.errorType }.firstOrNull { it.isNotBlank() } ?: kind.name
        return LogDbManager.insertCatchLog(
            eventId = eventId,
            occurredAt = occurredAt,
            source = sourceTag(kind),
            method = method,
            message = buildSummary(records),
            reportFile = reportFile,
            mimeType = "text/plain"
        )
    }

    private fun groupRelated(inputs: List<Input>): List<List<Input>> {
        val groups = mutableListOf<MutableList<Input>>()
        val primaryInputs = inputs.filter { it.record.kind != CrashKind.NATIVE_CONTEXT }
            .sortedBy { it.record.occurredAt }
        for (input in primaryInputs) {
            val existing = groups.lastOrNull { canMerge(it.first().record, input.record, MERGE_WINDOW_MS) }
            if (existing == null) groups += mutableListOf(input) else existing += input
        }
        val nativeContexts = inputs.filter { it.record.kind == CrashKind.NATIVE_CONTEXT }
            .sortedBy { it.record.occurredAt }
        for (context in nativeContexts) {
            val target = groups
                .filter { group -> group.any { it.record.kind == CrashKind.NATIVE } }
                .minByOrNull { group -> abs(group.first().record.occurredAt - context.record.occurredAt) }
                ?.takeIf { canMerge(it.first().record, context.record, NATIVE_CONTEXT_WINDOW_MS) }
            if (target == null) groups += mutableListOf(context) else target += context
        }
        return groups
    }

    private fun canMerge(first: CrashRecord, second: CrashRecord, windowMs: Long): Boolean {
        if (first.processName.isNotBlank() && second.processName.isNotBlank() && first.processName != second.processName) return false
        if (abs(first.occurredAt - second.occurredAt) > windowMs) return false
        if (first.sessionId.isNotBlank() && second.sessionId.isNotBlank() && first.sessionId != second.sessionId) return false
        return family(first.kind) == family(second.kind) || second.kind == CrashKind.NATIVE_CONTEXT
    }

    private fun family(kind: CrashKind): String {
        return when (kind) {
            CrashKind.JVM, CrashKind.CAUGHT -> "JVM"
            CrashKind.NATIVE, CrashKind.NATIVE_CONTEXT -> "NATIVE"
            CrashKind.ANR -> "ANR"
            CrashKind.EXIT -> "EXIT"
        }
    }

    private fun sourceTag(kind: CrashKind): String {
        return when (kind) {
            CrashKind.JVM -> "JAVA_FATAL"
            CrashKind.NATIVE -> "NATIVE_CRASH"
            CrashKind.ANR -> "ANR"
            CrashKind.CAUGHT -> "CAUGHT_EXCEPTION"
            CrashKind.NATIVE_CONTEXT -> "NATIVE_EMERGENCY"
            CrashKind.EXIT -> "SYSTEM_EXIT"
        }
    }

    private fun buildSummary(records: List<CrashRecord>): String {
        val primary = records.firstOrNull { it.kind != CrashKind.NATIVE_CONTEXT } ?: records.first()
        val stackPreview = primary.stackTrace.lineSequence().take(SUMMARY_STACK_LINES).joinToString("\n")
        return buildString {
            appendLine("Crash source: ${records.joinToString { it.source.name }}")
            appendLine("Process: ${primary.processName}")
            appendLine("Thread: ${primary.threadName}")
            appendLine("Type: ${primary.errorType}")
            if (primary.errorMessage.isNotBlank()) appendLine("Message: ${primary.errorMessage}")
            appendLine("Tap the attachment icon to view the complete offline report.")
            if (stackPreview.isNotBlank()) {
                appendLine()
                append(stackPreview)
            }
        }.trim()
    }

    private fun formatReport(eventId: String, records: List<CrashRecord>): String {
        return buildString {
            appendLine("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")
            appendLine("LoggerX offline crash report")
            appendLine("event_id: $eventId")
            for ((index, record) in records.withIndex()) {
                appendLine()
                appendLine("----- source ${index + 1}: ${record.source} -----")
                appendLine("time: ${formatTimestamp(record.occurredAt)} (${record.occurredAt})")
                appendLine("process: ${record.processName}")
                appendLine("thread: ${record.threadName}")
                appendLine("type: ${record.errorType}")
                appendLine("message: ${record.errorMessage}")
                if (record.details.isNotBlank()) {
                    appendLine()
                    appendLine("details:")
                    appendLine(record.details)
                }
                if (record.stackTrace.isNotBlank()) {
                    appendLine()
                    appendLine("stacktrace:")
                    appendLine(record.stackTrace)
                }
            }
        }
    }

    private fun digest(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    }
}
