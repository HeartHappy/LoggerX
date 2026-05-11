package com.hearthappy.loggerx.core

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.db.LogDbManager
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class ImagePreviewData(val filePath: String, val mimeType: String)

data class FileLogEntry(val id: Int, val time: String, val level: String, val tag: String, val method: String, val message: String, val filePath: String)

/**
 * Scope代理类：封装不同等级的日志调用
 */
class LogScopeProxy(private val scope: String): LogScope {
    fun v(message: String) = output(LogLevel.VERBOSE, message)

    fun d(message: String) = output(LogLevel.DEBUG, message)

    fun i(message: String) = output(LogLevel.INFO, message)

    fun w(message: String) = output(LogLevel.WARN, message)

    fun e(message: String, throwable: Throwable? = null) = output(LogLevel.ERROR, message, throwable)

    fun wtf(message: String) = output(LogLevel.ASSERT, message)

    fun json(json: String?) = output(LogLevel.DEBUG, json ?: "Empty JSON")

    fun xml(xml: String?) = output(LogLevel.DEBUG, xml ?: "Empty XML")

    fun image(imageFilePath: String, message: String = "file-log"): FileLogEntry {
        return writeFileLog(FileLogStorageManager.validateSource(imageFilePath), message)
    }

    fun image(imageFile: File, message: String = "file-log"): FileLogEntry {
        return writeFileLog(FileLogStorageManager.validateSource(imageFile), message)
    }

    private fun output(level: LogLevel, message: String, throwable: Throwable? = null) {
        LogOutputterManager.getOutputter(scope).output(level, message, throwable)
    }

    fun queryLogs(time: String? = null, tag: String? = null, level: String? = null, method: String? = null, isImage: Boolean? = null, keyword: String? = null, isAsc: Boolean = false, page: Int = 1, limit: Int? = 100, includeImagePayload: Boolean = false): List<Map<String, Any>> {
        return LogDbManager.queryLogsAdvanced(scope, time, tag, level, method, isImage, keyword, isAsc, page, limit)
    }

    fun queryLogsAsync(time: String? = null, tag: String? = null, level: String? = null, method: String? = null, isImage: Boolean? = null, keyword: String? = null, isAsc: Boolean = false, page: Int = 1, limit: Int = 100, anchorTime: String? = null, anchorId: Int? = null, includeImagePayload: Boolean = false, listener: DataQueryService.QueryListener): DataQueryService.QueryHandle {
        return DataQueryService.queryAsync(scopeTag = scope, request = DataQueryService.QueryRequest(time = time, tag = tag, level = level, method = method, isImage = isImage, keyword = keyword, sortAsc = isAsc, page = page, pageSize = limit, anchorTime = anchorTime, anchorId = anchorId, includeImagePayload = includeImagePayload), listener = listener)
    }

    fun getDistinctValues(columnName: String): List<String> {
        return LogDbManager.getDistinctValues(scope, columnName)
    }

    override fun delete(time: String): Int {
        return LogDbManager.deleteLogs(scope, time)
    }

    override fun delete(): Int {
        return LogDbManager.deleteLogs(scope, null)
    }


    fun deleteLogs(time: String? = null): Int {
        return LogDbManager.deleteLogs(scope, time)
    }

    fun clearAllLogs(): Boolean {
        return LogDbManager.clearAllLogs()
    }

    fun loadImagePreviewData(logId: Int): ImagePreviewData? {
        return LogDbManager.loadImagePreviewData(scope, logId)
    }

    fun doExportAndShare(exportAll: Boolean = false, limit: Int = 1000, format: LogExportManager.ExportFormat = LogExportManager.ExportFormat.CSV, onProgress: ((Int) -> Unit)? = null) {
        LogExportManager.exportScopeAndShare(scopeTag = scope, exportAll = exportAll, limit = limit, format = format, onProgress = onProgress)
    }

    override fun doExportAndShare() {
        LogExportManager.exportScopeAndShare(scope, true, Int.MAX_VALUE, LogExportManager.ExportFormat.CSV) { progress -> }
    }

    /**
     * 导出单个作用域的日志文件
     * @param exportAll 是否导出所有记录（true：不限制数量，false：按 limit 导出）
     * @param limit 导出的条数限制（仅在 exportAll 为 false 时生效）
     * @param format 导出格式（CSV/TXT）
     * @param onProgress 导出进度回调 (0..100)
     * @param onFileReady 文件就绪回调，接收导出的文件
     */
    override fun exportFile(exportAll: Boolean, limit: Int, format: LogExportManager.ExportFormat, onProgress: ((Int) -> Unit)?, onFileReady: ((File?) -> Unit)?) {
        Thread {
            val file = LogExportManager.exportScopePaged(scopeTag = scope, exportAll = exportAll, limit = limit, format = format, onProgress = onProgress)
            onFileReady?.invoke(file)
        }.start()
    }

    override fun getTag(): String {
        return scope
    }

    override fun getProxy(): LogScopeProxy {
        return this
    }

    private fun writeFileLog(sourceFile: File, message: String): FileLogEntry {
        val startNs = System.nanoTime()
        val stackTraceInfo = LogContextCollector.getStackTraceInfo()
        val classTag = stackTraceInfo.className
        val methodName = LogFormatter.format(stackTraceInfo)
        return try {
            val row = LogDbManager.insertFileLog(scopeTag = scope, level = LogLevel.INFO.value, classTag = classTag, method = methodName, message = message, sourceFile = sourceFile)
            val totalMs = nsToMs(System.nanoTime() - startNs)
            recordFileWritePerf(totalMs, true)
            FileLogEntry(id = row[LoggerX.COLUMN_ID]?.toString()?.toIntOrNull() ?: -1, time = row[LoggerX.COLUMN_TIME]?.toString().orEmpty(), level = row[LoggerX.COLUMN_LEVEL]?.toString().orEmpty(), tag = row[LoggerX.COLUMN_TAG]?.toString().orEmpty(), method = row[LoggerX.COLUMN_METHOD]?.toString().orEmpty(), message = row[LoggerX.COLUMN_MESSAGE]?.toString().orEmpty(), filePath = row[LoggerX.COLUMN_FILE_PATH]?.toString().orEmpty())
        } catch (e: FileLogWriteException) {
            val totalMs = nsToMs(System.nanoTime() - startNs)
            recordFileWritePerf(totalMs, false)
            Log.e(LoggerX.TAG, "File write failed: ${e.userMessage}", e)
            // 返回一个默认的无效Entry，避免抛出异常导致应用程序崩溃
            return FileLogEntry(id = -1, time = "", level = LogLevel.INFO.value, tag = classTag, method = methodName, message = "File write failed: ${e.userMessage}", filePath = "")
        }
    }

    private fun recordFileWritePerf(totalMs: Long, success: Boolean) {
        fileWriteCount.incrementAndGet()
        if (!success) {
            fileWriteFailedCount.incrementAndGet()
        }
        fileWriteTotalMs.addAndGet(totalMs)
        fileWriteMaxMs.updateAndGet { old -> maxOf(old, totalMs) }
        if (totalMs > 200L) {
            fileWriteOver200MsCount.incrementAndGet()
            Log.w(LoggerX.TAG, "File log write slow: total=${totalMs}ms")
        } else {
            Log.i(LoggerX.TAG, "File log write perf: total=${totalMs}ms")
        }
    }

    private fun nsToMs(ns: Long): Long = ns / 1_000_000L

    companion object {
        private val fileWriteCount = AtomicLong(0)
        private val fileWriteFailedCount = AtomicLong(0)
        private val fileWriteOver200MsCount = AtomicLong(0)
        private val fileWriteTotalMs = AtomicLong(0)
        private val fileWriteMaxMs = AtomicLong(0)
    }
}
