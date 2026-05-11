package com.hearthappy.loggerx.db

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.ContextHolder
import com.hearthappy.loggerx.core.DataQueryService
import com.hearthappy.loggerx.core.FileLogStorageManager
import com.hearthappy.loggerx.core.FileLogWriteException
import com.hearthappy.loggerx.core.ImagePreviewData
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object LogDbManager {


    private val dbHelper = LogDbHelper(ContextHolder.getAppContext())
    private val database = dbHelper.writableDatabase
    private val existedTables = HashSet<String>()
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

    private fun getTableName(scopeTag: String): String {
        val cleanTag = scopeTag.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return "${cleanTag}_log"
    }

    @Synchronized private fun ensureTable(tableName: String) {
        if (existedTables.contains(tableName)) return
        dbHelper.createLogTable(database, tableName)
        existedTables.add(tableName)
    }

    fun getDbFileSize(): Double = dbHelper.getDbFileSize()

    @Synchronized
    fun insertLog(scopeTag: String, level: String, classTag: String, method: String, message: String) {
        val tableName = getTableName(scopeTag)
        try {
            ensureTable(tableName)
            database.insertOrThrow(tableName, null, ContentValues().apply {
                put(LoggerX.COLUMN_LEVEL, level)
                put(LoggerX.COLUMN_TAG, classTag)
                put(LoggerX.COLUMN_METHOD, method)
                put(LoggerX.COLUMN_MESSAGE, message)
                put(LoggerX.COLUMN_FILE_PATH, "")
            })
            notifyDataChanged()
        } catch (e: Exception) {
            Log.e(LoggerX.TAG, "insertLog failed: ${e.message}")
        }
    }

    @Synchronized
    fun insertFileLog(scopeTag: String, level: String, classTag: String, method: String, message: String, sourceFile: File): Map<String, Any> {
        val tableName = getTableName(scopeTag)
        ensureTable(tableName)
        val managedFile = FileLogStorageManager.persistSource(scopeTag, sourceFile)
        return try {
            val rowId = database.insertOrThrow(tableName, null, ContentValues().apply {
                put(LoggerX.COLUMN_LEVEL, level)
                put(LoggerX.COLUMN_TAG, classTag)
                put(LoggerX.COLUMN_METHOD, method)
                put(LoggerX.COLUMN_MESSAGE, message)
                put(LoggerX.COLUMN_FILE_PATH, managedFile.file.absolutePath)
            })
            notifyDataChanged()
            queryLogById(tableName, rowId.toInt()) ?: throw FileLogWriteException("写入文件日志失败，请稍后重试")
        } catch (e: Exception) {
            managedFile.file.delete()
            Log.e(LoggerX.TAG, "insertFileLog failed: ${e.message}")
            throw e as? FileLogWriteException ?: FileLogWriteException("写入文件日志失败，请稍后重试", e)
        }
    }

    fun loadImageBase64(scopeTag: String, logId: Int): String? {
        val file = queryStoredFile(scopeTag, logId) ?: return null
        return runCatching {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.onFailure {
            Log.e(LoggerX.TAG, "loadImageBase64 failed: ${it.message}")
        }.getOrNull()
    }

    fun loadImagePreviewData(scopeTag: String, logId: Int): ImagePreviewData? {
        val file = queryStoredFile(scopeTag, logId) ?: return null
        return ImagePreviewData(filePath = file.absolutePath, mimeType = FileLogStorageManager.detectMimeType(file))
    }

    fun loadImageMimeType(scopeTag: String, logId: Int): String? {
        val file = queryStoredFile(scopeTag, logId) ?: return null
        return FileLogStorageManager.detectMimeType(file)
    }

    fun queryLogsAdvanced(scopeTag: String, time: String? = null, tag: String? = null, level: String? = null, method: String? = null, isImage: Boolean? = null, keyword: String? = null, isAsc: Boolean = false, page: Int = 1, limit: Int? = 100): List<Map<String, Any>> {
        if (limit == null) {
            return queryLogsAllAdvanced(scopeTag = scopeTag, time = time, tag = tag, level = level, method = method, isImage = isImage, keyword = keyword, isAsc = isAsc)
        }
        return queryLogsPageAdvanced(scopeTag = scopeTag, time = time, tag = tag, level = level, method = method, isImage = isImage, keyword = keyword, isAsc = isAsc, page = page, limit = limit).rows
    }

    fun queryLogsPageAdvanced(scopeTag: String, time: String? = null, tag: String? = null, level: String? = null, method: String? = null, isImage: Boolean? = null, keyword: String? = null, isAsc: Boolean = false, page: Int = 1, limit: Int = 100, maxPageBytes: Int = 1024 * 1024, anchorTime: String? = null, anchorId: Int? = null): QueryPageResult {
        val tableName = getTableName(scopeTag)
        if (!tableExists(tableName)) {
            return QueryPageResult(emptyList(), 0, page, limit, null, 0, false, emptyList())
        }
        val whereParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        val usingAnchor = !anchorTime.isNullOrBlank() && anchorId != null
        time?.let {
            whereParts += "s.${LoggerX.COLUMN_TIME} LIKE ?"
            args += "$it%"
        }
        tag?.let {
            whereParts += "s.${LoggerX.COLUMN_TAG} = ?"
            args += it
        }
        level?.let {
            whereParts += "s.${LoggerX.COLUMN_LEVEL} = ?"
            args += it
        }
        method?.let {
            whereParts += "s.${LoggerX.COLUMN_METHOD} LIKE ?"
            args += "%$it%"
        }
        keyword?.let {
            whereParts += "s.${LoggerX.COLUMN_MESSAGE} LIKE ?"
            args += "%$it%"
        }
        isImage?.let {
            whereParts += if (it) {
                "IFNULL(s.${LoggerX.COLUMN_FILE_PATH}, '') <> ''"
            } else {
                "IFNULL(s.${LoggerX.COLUMN_FILE_PATH}, '') = ''"
            }
        }
        if (usingAnchor) {
            whereParts += if (isAsc) {
                "(s.${LoggerX.COLUMN_TIME} > ? OR (s.${LoggerX.COLUMN_TIME} = ? AND s.${LoggerX.COLUMN_ID} > ?))"
            } else {
                "(s.${LoggerX.COLUMN_TIME} < ? OR (s.${LoggerX.COLUMN_TIME} = ? AND s.${LoggerX.COLUMN_ID} < ?))"
            }
            args += anchorTime.orEmpty()
            args += anchorTime.orEmpty()
            args += anchorId.toString()
        }
        val where = if (whereParts.isEmpty()) "" else "WHERE ${whereParts.joinToString(" AND ")}"
        val order = if (isAsc) "ASC" else "DESC"
        val offset = maxOf(0, (page - 1) * limit)
        val sqlLimit = (limit.coerceAtLeast(1) + 1)
        val limitSql = if (usingAnchor) {
            "LIMIT $sqlLimit"
        } else {
            "LIMIT $sqlLimit OFFSET $offset"
        }
        val sql = """
            SELECT
                s.${LoggerX.COLUMN_ID},
                s.${LoggerX.COLUMN_TIME},
                s.${LoggerX.COLUMN_LEVEL},
                s.${LoggerX.COLUMN_TAG},
                s.${LoggerX.COLUMN_METHOD},
                s.${LoggerX.COLUMN_MESSAGE},
                s.${LoggerX.COLUMN_FILE_PATH}
            FROM $tableName s
            $where
            ORDER BY s.${LoggerX.COLUMN_TIME} $order, s.${LoggerX.COLUMN_ID} $order
            $limitSql
        """.trimIndent()
        val countSql = """
            SELECT COUNT(*)
            FROM $tableName s
            $where
        """.trimIndent()
        return runCatching {
            val result = mutableListOf<Map<String, Any>>()
            var bytes = 0
            var hasMore = false
            val db = database
            val totalCount = db.rawQuery(countSql, args.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    if (result.size >= limit) {
                        hasMore = true
                        break
                    }
                    val row = cursorToMap(cursor)
                    val rowBytes = estimateRowBytes(row)
                    if (result.isNotEmpty() && bytes + rowBytes > maxPageBytes) {
                        hasMore = true
                        break
                    }
                    result += row
                    bytes += rowBytes
                }
            }
            val loadedCount = offset + result.size
            val lastRow = result.lastOrNull()
            QueryPageResult(
                rows = result,
                totalCount = totalCount,
                page = page,
                limit = limit,
                nextPage = if (!usingAnchor && (hasMore || loadedCount < totalCount) && result.isNotEmpty()) page + 1 else null,
                approxBytes = bytes,
                hasMore = if (usingAnchor) hasMore else (hasMore || loadedCount < totalCount),
                queryPlan = emptyList(),
                nextAnchorTime = lastRow?.get(LoggerX.COLUMN_TIME)?.toString(),
                nextAnchorId = lastRow?.get(LoggerX.COLUMN_ID)?.toString()?.toIntOrNull()
            )
        }.getOrElse {
            Log.e(LoggerX.TAG, "queryLogsPageAdvanced failed: ${it.message}")
            QueryPageResult(emptyList(), 0, page, limit, null, 0, false, emptyList())
        }
    }

    private fun queryLogsAllAdvanced(scopeTag: String, time: String? = null, tag: String? = null, level: String? = null, method: String? = null, isImage: Boolean? = null, keyword: String? = null, isAsc: Boolean = false): List<Map<String, Any>> {
        val tableName = getTableName(scopeTag)
        if (!tableExists(tableName)) return emptyList()
        val whereParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        time?.let {
            whereParts += "s.${LoggerX.COLUMN_TIME} LIKE ?"
            args += "$it%"
        }
        tag?.let {
            whereParts += "s.${LoggerX.COLUMN_TAG} = ?"
            args += it
        }
        level?.let {
            whereParts += "s.${LoggerX.COLUMN_LEVEL} = ?"
            args += it
        }
        method?.let {
            whereParts += "s.${LoggerX.COLUMN_METHOD} LIKE ?"
            args += "%$it%"
        }
        keyword?.let {
            whereParts += "s.${LoggerX.COLUMN_MESSAGE} LIKE ?"
            args += "%$it%"
        }
        isImage?.let {
            whereParts += if (it) {
                "IFNULL(s.${LoggerX.COLUMN_FILE_PATH}, '') <> ''"
            } else {
                "IFNULL(s.${LoggerX.COLUMN_FILE_PATH}, '') = ''"
            }
        }
        val where = if (whereParts.isEmpty()) "" else "WHERE ${whereParts.joinToString(" AND ")}"
        val order = if (isAsc) "ASC" else "DESC"
        val sql = """
            SELECT
                s.${LoggerX.COLUMN_ID},
                s.${LoggerX.COLUMN_TIME},
                s.${LoggerX.COLUMN_LEVEL},
                s.${LoggerX.COLUMN_TAG},
                s.${LoggerX.COLUMN_METHOD},
                s.${LoggerX.COLUMN_MESSAGE},
                s.${LoggerX.COLUMN_FILE_PATH}
            FROM $tableName s
            $where
            ORDER BY s.${LoggerX.COLUMN_TIME} $order, s.${LoggerX.COLUMN_ID} $order
        """.trimIndent()
        return runCatching {
            val result = mutableListOf<Map<String, Any>>()
            database.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result += cursorToMap(cursor)
                }
            }
            result
        }.getOrElse {
            Log.e(LoggerX.TAG, "queryLogsAllAdvanced failed: ${it.message}")
            emptyList()
        }
    }

    fun getDistinctValues(scopeTag: String, columnName: String): List<String> {
        val tableName = getTableName(scopeTag)
        if (!tableExists(tableName)) return emptyList()
        val sql = when (columnName) {
            LoggerX.COLUMN_TIME -> "SELECT DISTINCT substr(${LoggerX.COLUMN_TIME},1,10) FROM $tableName ORDER BY ${LoggerX.COLUMN_TIME} DESC"
            LoggerX.COLUMN_METHOD -> """
                SELECT DISTINCT CASE
                    WHEN instr(${LoggerX.COLUMN_METHOD}, '$') > 0 THEN substr(${LoggerX.COLUMN_METHOD},1,instr(${LoggerX.COLUMN_METHOD}, '$') - 1)
                    WHEN instr(${LoggerX.COLUMN_METHOD}, '(') > 0 THEN substr(${LoggerX.COLUMN_METHOD},1,instr(${LoggerX.COLUMN_METHOD}, '(') - 1)
                    ELSE ${LoggerX.COLUMN_METHOD}
                END FROM $tableName
            """.trimIndent()
            LoggerX.COLUMN_IS_IMAGE -> """
                SELECT DISTINCT CASE WHEN IFNULL(${LoggerX.COLUMN_FILE_PATH}, '') <> '' THEN '1' ELSE '0' END FROM $tableName
            """.trimIndent()
            else -> "SELECT DISTINCT $columnName FROM $tableName"
        }
        return runCatching {
            val values = mutableListOf<String>()
            database.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let(values::add)
                }
            }
            values
        }.getOrElse {
            Log.e(LoggerX.TAG, "getDistinctValues failed: ${it.message}")
            emptyList()
        }
    }

    fun deleteLogs(scopeTag: String, timeFormat: String?): Int {
        val tableName = getTableName(scopeTag)
        if (!tableExists(tableName)) return 0
        val where = if (timeFormat.isNullOrBlank()) null else "${LoggerX.COLUMN_TIME} < ?"
        val whereArgs = if (timeFormat.isNullOrBlank()) null else arrayOf(timeFormat)
        val filePaths = queryFilePaths(tableName, where, whereArgs)
        if (filePaths.isEmpty() && timeFormat.isNullOrBlank()) {
            val deletedRows = database.delete(tableName, null, null)
            if (deletedRows > 0) {
                notifyDataChanged()
            }
            return deletedRows
        }
        val deletedRows = runInTransaction {
            database.delete(tableName, where, whereArgs)
        }
        deleteStoredFiles(filePaths)
        if (deletedRows > 0) {
            notifyDataChanged()
        }
        return deletedRows
    }

    fun clearAllLogs(): Boolean {
        return runCatching {
            getAllLogTables().forEach { table ->
                val filePaths = queryFilePaths(table, null, null)
                runInTransaction {
                    database.delete(table, null, null)
                }
                deleteStoredFiles(filePaths)
            }
            notifyDataChanged()
            true
        }.getOrElse {
            Log.e(LoggerX.TAG, "clearAllLogs failed: ${it.message}")
            false
        }
    }

    private var autoCleanFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var autoCleanBySizeFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var autoCleanByLowMemoryFuture: java.util.concurrent.ScheduledFuture<*>? = null

    fun startAutoCleanByDate(retentionDays: Int) {
        if (retentionDays <= 0) return
        autoCleanFuture?.cancel(false)
        autoCleanFuture = scheduledExecutor.scheduleWithFixedDelay({
            runCatching { performCleanupByDateRange(retentionDays) }.onFailure { Log.e(LoggerX.TAG, "Auto clean by date failed: ${it.message}") }
        }, 0, 24, TimeUnit.HOURS)
    }

    fun startAutoCleanBySize(maxSizeMb: Double, cleanSizeMb: Double) {
        if (maxSizeMb <= 0 || cleanSizeMb <= 0) return
        autoCleanBySizeFuture?.cancel(false)
        autoCleanBySizeFuture = scheduledExecutor.scheduleWithFixedDelay({
            runCatching { performCleanupBySize(maxSizeMb, cleanSizeMb) }.onFailure { Log.e(LoggerX.TAG, "Auto clean by size failed: ${it.message}") }
        }, 0, 24, TimeUnit.HOURS)
    }

    fun startAutoCleanByLowMemory(minFreeSpaceMb: Double) {
        if (minFreeSpaceMb <= 0) return
        autoCleanByLowMemoryFuture?.cancel(false)
        autoCleanByLowMemoryFuture = scheduledExecutor.scheduleWithFixedDelay({
            runCatching { performCleanupByLowMemory(minFreeSpaceMb) }.onFailure { Log.e(LoggerX.TAG, "Auto clean by low memory failed: ${it.message}") }
        }, 0, 30, TimeUnit.MINUTES)
    }

    private fun performCleanupByDateRange(days: Int) {
        getAllLogTables().forEach { tableName ->
            val distinctDates = mutableListOf<String>()
            val sql = "SELECT DISTINCT substr(${LoggerX.COLUMN_TIME}, 1, 10) as log_date FROM $tableName ORDER BY log_date ASC"
            database.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let(distinctDates::add)
                }
            }
            if (distinctDates.size > days) {
                val cutoffDate = distinctDates[distinctDates.size - days - 1]
                deleteLogsByDate(tableName, cutoffDate)
            }
        }
    }

    private fun deleteLogsByDate(tableName: String, cutoffDate: String) {
        val where = "substr(${LoggerX.COLUMN_TIME},1,10) <= ?"
        val args = arrayOf(cutoffDate)
        val filePaths = queryFilePaths(tableName, where, args)
        runInTransaction {
            database.delete(tableName, where, args)
        }
        deleteStoredFiles(filePaths)
        notifyDataChanged()
    }

    private fun performCleanupBySize(maxSizeMb: Double, cleanSizeMb: Double) {
        val dbFile = ContextHolder.getAppContext().getDatabasePath(LogDbHelper.DB_NAME)
        if (!dbFile.exists()) return
        var currentSizeMb = dbFile.length().toDouble() / (1024.0 * 1024.0)
        if (currentSizeMb <= maxSizeMb) return
        val target = maxSizeMb - cleanSizeMb
        var loop = 0
        while (currentSizeMb > target && loop < 10) {
            loop++
            getAllLogTables().forEach { table ->
                val ids = mutableListOf<Int>()
                val paths = mutableListOf<String>()
                database.query(table, arrayOf(LoggerX.COLUMN_ID, LoggerX.COLUMN_FILE_PATH), null, null, null, null, "${LoggerX.COLUMN_TIME} ASC", "500").use { cursor ->
                    while (cursor.moveToNext()) {
                        ids += cursor.getInt(0)
                        cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(paths::add)
                    }
                }
                if (ids.isNotEmpty()) {
                    val placeholders = ids.joinToString(",") { "?" }
                    runInTransaction {
                        database.delete(table, "${LoggerX.COLUMN_ID} IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
                    }
                    deleteStoredFiles(paths)
                    notifyDataChanged()
                }
            }
            currentSizeMb = dbFile.length().toDouble() / (1024.0 * 1024.0)
        }
    }

    private fun performCleanupByLowMemory(minFreeSpaceMb: Double) {
        val dbFile = ContextHolder.getAppContext().getDatabasePath(LogDbHelper.DB_NAME)
        val dir = dbFile.parentFile ?: return
        if (!dir.exists()) return

        var freeSpaceMb = dir.usableSpace.toDouble() / (1024.0 * 1024.0)
        var loop = 0 // Limit loop to avoid infinite loop
        while (freeSpaceMb < minFreeSpaceMb && loop < 30) {
            loop++
            var globalOldestDate: String? = null
            val tables = getAllLogTables()

            // Find the globally oldest date across all log tables
            tables.forEach { tableName ->
                val sql = "SELECT min(substr(${LoggerX.COLUMN_TIME}, 1, 10)) FROM $tableName"
                database.rawQuery(sql, null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val date = cursor.getString(0)
                        if (date != null) {
                            if (globalOldestDate == null || date < globalOldestDate!!) {
                                globalOldestDate = date
                            }
                        }
                    }
                }
            }

            // If we found an oldest date, delete all records from that date
            if (globalOldestDate != null) {
                tables.forEach { tableName ->
                    deleteLogsByDate(tableName, globalOldestDate)
                } // Update free space
                freeSpaceMb = dir.usableSpace.toDouble() / (1024.0 * 1024.0)
            } else { // No more logs to delete
                break
            }
        }
    }

    private fun queryStoredFile(scopeTag: String, logId: Int): File? {
        val tableName = getTableName(scopeTag)
        if (!tableExists(tableName)) return null
        val filePath = queryFilePathById(tableName, logId) ?: return null
        return runCatching { FileLogStorageManager.validateStoredFile(filePath) }.onFailure { Log.e(LoggerX.TAG, "queryStoredFile failed: ${it.message}") }.getOrNull()
    }

    private fun queryFilePathById(tableName: String, logId: Int): String? {
        return runCatching {
            database.query(tableName, arrayOf(LoggerX.COLUMN_FILE_PATH), "${LoggerX.COLUMN_ID}=?", arrayOf(logId.toString()), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryLogById(tableName: String, logId: Int): Map<String, Any>? {
        return runCatching {
            database.query(tableName, arrayOf(LoggerX.COLUMN_ID, LoggerX.COLUMN_TIME, LoggerX.COLUMN_LEVEL, LoggerX.COLUMN_TAG, LoggerX.COLUMN_METHOD, LoggerX.COLUMN_MESSAGE, LoggerX.COLUMN_FILE_PATH), "${LoggerX.COLUMN_ID}=?", arrayOf(logId.toString()), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) cursorToMap(cursor) else null
            }
        }.getOrNull()
    }

    private fun queryFilePaths(tableName: String, where: String?, args: Array<String>?): List<String> {
        return runCatching {
            val paths = mutableListOf<String>()
            database.query(tableName, arrayOf(LoggerX.COLUMN_FILE_PATH), where, args, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
                }
            }
            paths
        }.getOrElse {
            Log.e(LoggerX.TAG, "queryFilePaths failed: ${it.message}")
            emptyList()
        }
    }

    private fun deleteStoredFiles(filePaths: Collection<String>) {
        filePaths.forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && !file.delete()) {
                    Log.w(LoggerX.TAG, "delete stored file failed: $path")
                }
            }.onFailure {
                Log.w(LoggerX.TAG, "delete stored file error: ${it.message}")
            }
        }
    }

    private fun cursorToMap(c: Cursor): Map<String, Any> {
        val filePath = c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_FILE_PATH)).orEmpty()
        return mutableMapOf<String, Any>(LoggerX.COLUMN_ID to c.getInt(c.getColumnIndexOrThrow(LoggerX.COLUMN_ID)), LoggerX.COLUMN_TIME to c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_TIME)).orEmpty(), LoggerX.COLUMN_LEVEL to c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_LEVEL)).orEmpty(), LoggerX.COLUMN_TAG to c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_TAG)).orEmpty(), LoggerX.COLUMN_METHOD to c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_METHOD)).orEmpty(), LoggerX.COLUMN_MESSAGE to c.getString(c.getColumnIndexOrThrow(LoggerX.COLUMN_MESSAGE)).orEmpty(), LoggerX.COLUMN_FILE_PATH to filePath, LoggerX.COLUMN_IS_IMAGE to if (filePath.isNotBlank()) 1 else 0)
    }

    private fun estimateRowBytes(row: Map<String, Any>): Int {
        return row.entries.sumOf { (key, value) ->
            key.length * 2 + value.toString().length * 2
        }.coerceAtLeast(64)
    }

    private fun tableExists(tableName: String): Boolean {
        if (existedTables.contains(tableName)) return true
        val exists = runCatching {
            database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
        }.getOrDefault(false)
        if (exists) {
            ensureTable(tableName)
        }
        return exists
    }

    private fun getAllLogTables(): List<String> {
        val tables = mutableListOf<String>()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_log'", null).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        return tables
    }

    private fun notifyDataChanged() {
        DataQueryService.invalidateCache()
    }

    private inline fun runInTransaction(block: () -> Int): Int {
        database.beginTransaction()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            runCatching { database.endTransaction() }
        }
    }
}
