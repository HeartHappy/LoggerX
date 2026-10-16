package com.hearthappy.loggerx.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.ContextHolder


/**
 * Created Date: 2026/3/20
 * @author ChenRui
 * ClassDescription：数据库处理
 */
internal class LogDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrateLegacySchema(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
    }

    /**
     * 根据表名创建日志表
     */
    fun createLogTable(db: SQLiteDatabase, tableName: String) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val sql = """
                CREATE TABLE IF NOT EXISTS $tableName (
                    ${LoggerX.COLUMN_ID } INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${LoggerX.COLUMN_TIME} TIMESTAMP DEFAULT (datetime('now', 'localtime')),
                    ${LoggerX.COLUMN_LEVEL} TEXT,
                    ${LoggerX.COLUMN_TAG} TEXT,
                    ${LoggerX.COLUMN_METHOD} TEXT,
                    ${LoggerX.COLUMN_MESSAGE} TEXT,
                    ${LoggerX.COLUMN_FILE_PATH} VARCHAR(512) DEFAULT '',
                    ${LoggerX.COLUMN_FILE_MIME} TEXT DEFAULT '',
                    ${LoggerX.COLUMN_EVENT_ID} TEXT DEFAULT '',
                    ${LoggerX.COLUMN_UPLOADED} INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent()
            db.execSQL(sql)
            ensureScopeTableSchema(db, tableName)
            ensureScopeIndexes(db, tableName)
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun ensureScopeTableSchema(db: SQLiteDatabase, tableName: String) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        val requiredColumns = setOf(
            LoggerX.COLUMN_ID,
            LoggerX.COLUMN_TIME,
            LoggerX.COLUMN_LEVEL,
            LoggerX.COLUMN_TAG,
            LoggerX.COLUMN_METHOD,
            LoggerX.COLUMN_MESSAGE,
            LoggerX.COLUMN_FILE_PATH,
            LoggerX.COLUMN_FILE_MIME,
            LoggerX.COLUMN_EVENT_ID,
            LoggerX.COLUMN_UPLOADED
        )
        val hasLegacyImageColumns = columns.contains("image_payload") ||
            columns.contains("image_chunked") ||
            columns.contains("mime_type") ||
            columns.contains("is_image") ||
            columns.contains("thumbnail") ||
            columns.contains("image_id") ||
            columns.contains("media_type") ||
            columns.contains("compressed_image") ||
            columns.contains("original_size") ||
            columns.contains("compressed_size") ||
            columns.contains("compression_ratio") ||
            columns.contains("checksum_sha256")
        if (!columns.containsAll(requiredColumns) || hasLegacyImageColumns) {
            rebuildScopeTable(db, tableName, columns)
        }
    }

    private fun rebuildScopeTable(db: SQLiteDatabase, tableName: String, existingColumns: Set<String>) {
        val newTable = "${tableName}_new"
        db.execSQL("DROP TABLE IF EXISTS $newTable")
        db.execSQL(
            """
            CREATE TABLE $newTable (
                ${LoggerX.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${LoggerX.COLUMN_TIME} TIMESTAMP DEFAULT (datetime('now', 'localtime')),
                ${LoggerX.COLUMN_LEVEL} TEXT,
                ${LoggerX.COLUMN_TAG} TEXT,
                ${LoggerX.COLUMN_METHOD} TEXT,
                ${LoggerX.COLUMN_MESSAGE} TEXT,
                ${LoggerX.COLUMN_FILE_PATH} VARCHAR(512) DEFAULT '',
                ${LoggerX.COLUMN_FILE_MIME} TEXT DEFAULT '',
                ${LoggerX.COLUMN_EVENT_ID} TEXT DEFAULT '',
                ${LoggerX.COLUMN_UPLOADED} INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        val selectParts = listOf(
            if (existingColumns.contains(LoggerX.COLUMN_ID)) LoggerX.COLUMN_ID else "NULL AS ${LoggerX.COLUMN_ID}",
            if (existingColumns.contains(LoggerX.COLUMN_TIME)) LoggerX.COLUMN_TIME else "datetime('now','localtime') AS ${LoggerX.COLUMN_TIME}",
            if (existingColumns.contains(LoggerX.COLUMN_LEVEL)) LoggerX.COLUMN_LEVEL else "'' AS ${LoggerX.COLUMN_LEVEL}",
            if (existingColumns.contains(LoggerX.COLUMN_TAG)) LoggerX.COLUMN_TAG else "'' AS ${LoggerX.COLUMN_TAG}",
            if (existingColumns.contains(LoggerX.COLUMN_METHOD)) LoggerX.COLUMN_METHOD else "'' AS ${LoggerX.COLUMN_METHOD}",
            if (existingColumns.contains(LoggerX.COLUMN_MESSAGE)) LoggerX.COLUMN_MESSAGE else "'' AS ${LoggerX.COLUMN_MESSAGE}",
            if (existingColumns.contains(LoggerX.COLUMN_FILE_PATH)) LoggerX.COLUMN_FILE_PATH else "'' AS ${LoggerX.COLUMN_FILE_PATH}",
            if (existingColumns.contains(LoggerX.COLUMN_FILE_MIME)) LoggerX.COLUMN_FILE_MIME else if (existingColumns.contains(LoggerX.COLUMN_FILE_PATH)) "CASE WHEN IFNULL(${LoggerX.COLUMN_FILE_PATH}, '') <> '' THEN 'image/*' ELSE '' END AS ${LoggerX.COLUMN_FILE_MIME}" else "'' AS ${LoggerX.COLUMN_FILE_MIME}",
            if (existingColumns.contains(LoggerX.COLUMN_EVENT_ID)) LoggerX.COLUMN_EVENT_ID else "'' AS ${LoggerX.COLUMN_EVENT_ID}",
            if (existingColumns.contains(LoggerX.COLUMN_UPLOADED)) LoggerX.COLUMN_UPLOADED else "0 AS ${LoggerX.COLUMN_UPLOADED}"
        )
        val sql = "INSERT INTO $newTable (${columnsForInsert()}) SELECT ${selectParts.joinToString(",")} FROM $tableName"
        db.execSQL(sql)
        db.execSQL("DROP TABLE IF EXISTS $tableName")
        db.execSQL("ALTER TABLE $newTable RENAME TO $tableName")
        ensureScopeIndexes(db, tableName)
    }

    private fun ensureScopeIndexes(db: SQLiteDatabase, tableName: String) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${tableName}_time_id ON $tableName(${LoggerX.COLUMN_TIME} DESC, ${LoggerX.COLUMN_ID} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${tableName}_file_path ON $tableName(${LoggerX.COLUMN_FILE_PATH})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${tableName}_level_time_id ON $tableName(${LoggerX.COLUMN_LEVEL}, ${LoggerX.COLUMN_TIME} DESC, ${LoggerX.COLUMN_ID} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${tableName}_tag_time_id ON $tableName(${LoggerX.COLUMN_TAG}, ${LoggerX.COLUMN_TIME} DESC, ${LoggerX.COLUMN_ID} DESC)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${tableName}_event_id ON $tableName(${LoggerX.COLUMN_EVENT_ID}) WHERE ${LoggerX.COLUMN_EVENT_ID} <> ''")
    }

    private fun columnsForInsert(): String {
        return listOf(
            LoggerX.COLUMN_ID,
            LoggerX.COLUMN_TIME,
            LoggerX.COLUMN_LEVEL,
            LoggerX.COLUMN_TAG,
            LoggerX.COLUMN_METHOD,
            LoggerX.COLUMN_MESSAGE,
            LoggerX.COLUMN_FILE_PATH,
            LoggerX.COLUMN_FILE_MIME,
            LoggerX.COLUMN_EVENT_ID,
            LoggerX.COLUMN_UPLOADED
        ).joinToString(",")
    }

    private fun migrateLegacySchema(db: SQLiteDatabase) {
        dropLegacyAssociatedTables(db)
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_log'",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }
        tables.forEach { tableName ->
            ensureScopeTableSchema(db, tableName)
            ensureScopeIndexes(db, tableName)
        }
    }

    private fun dropLegacyAssociatedTables(db: SQLiteDatabase) {
        val legacyTables = linkedSetOf("log_image")
        legacyTables += queryTablesByLike(db, "%\\_log\\_img\\_chunk")
        legacyTables += queryTablesByLike(db, "%\\_log\\_image")
        legacyTables.forEach { tableName ->
            db.execSQL("DROP TABLE IF EXISTS $tableName")
        }
    }

    private fun queryTablesByLike(db: SQLiteDatabase, likePattern: String): List<String> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ? ESCAPE '\\'",
            arrayOf(likePattern)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }
        return tables
    }

    /**
     * 获取日志数据库文件的大小（单位：MB）
     */
    fun getDbFileSize(): Double {
        val dbFile = ContextHolder.getAppContext().getDatabasePath(DB_NAME)
        return if (dbFile.exists()) {
            dbFile.length().toDouble() / (1024 * 1024)
        } else 0.0
    }
    companion object {
        const val DB_NAME = "hearthappy_logs.db"
        const val DB_VERSION = 9
    }
}
