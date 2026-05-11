package com.hearthappy.loggerx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hearthappy.loggerx.core.LogExportManager
import com.hearthappy.loggerx.db.LogDbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class LogDbManagerAndroidTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        baseDir = File(context.filesDir, "loggerx-test").apply { mkdirs() }
        LoggerX.init(context, com.hearthappy.loggerx.core.OutputConfig(storageDirPath = baseDir.absolutePath))
        LoggerX.clear()
    }

    @Test
    fun distinctValues_shouldReturnEmpty_whenTableNotExists() {
        val values = LogDbManager.getDistinctValues("NeverWrittenScope", LoggerX.COLUMN_TAG)
        assertTrue(values.isEmpty())
    }

    @Test
    fun queryLogs_shouldReturnEmpty_whenTableNotExists() {
        val logs = LogDbManager.queryLogsAdvanced(scopeTag = "NeverWrittenScope")
        assertTrue(logs.isEmpty())
    }

    @Test
    fun insertThenDistinct_shouldWork_afterTableCreated() {
        val scope = "RetryScope"
        LogDbManager.insertLog(scope, "DEBUG", "Main", "m()", "msg")
        val levels = LogDbManager.getDistinctValues(scope, LoggerX.COLUMN_LEVEL)
        assertEquals(listOf("DEBUG"), levels)
    }

    @Test
    fun fileLog_shouldPersistAbsolutePathForImageFile() {
        val proxy = LoggerX.createScope("FileScope")
        val imageFile = fakeBitmapFile("image_scope.png", 320, 320, Bitmap.CompressFormat.PNG)
        val entry = proxy.image(imageFile, message = "img")
        assertTrue(entry.filePath.isNotBlank())
        assertTrue(File(entry.filePath).exists())
        val logs = proxy.queryLogs(limit = 20)
        assertTrue(logs.any { it[LoggerX.COLUMN_IS_IMAGE]?.toString() == "1" })
        assertTrue(logs.any { it[LoggerX.COLUMN_FILE_PATH]?.toString() == entry.filePath })
    }

    @Test
    fun fileLog_shouldLoadPreviewData() {
        val scope = "PreviewScope"
        val proxy = LoggerX.createScope(scope)
        val imageFile = fakeBitmapFile("preview_scope.jpg", 720, 480, Bitmap.CompressFormat.JPEG)
        val entry = proxy.image(imageFile, message = "preview")
        val id = LogDbManager.queryLogsAdvanced(scope, limit = 1).first()[LoggerX.COLUMN_ID].toString().toInt()
        val loaded = proxy.loadImagePreviewData(id)
        assertNotNull(loaded)
        assertEquals(entry.filePath, loaded!!.filePath)
        assertTrue(File(loaded.filePath).exists())
    }

    @Test
    fun fileLog_shouldHandle1000RowsPerformance() {
        val proxy = LoggerX.createScope("PressureScope")
        val imageFile = fakeBitmapFile("pressure_scope.jpg", 128, 128, Bitmap.CompressFormat.JPEG)
        val start = System.currentTimeMillis()
        repeat(1000) {
            proxy.image(imageFile, message = "img-$it")
        }
        val writeElapsed = System.currentTimeMillis() - start
        val loadStart = System.currentTimeMillis()
        val logs = proxy.queryLogs(limit = 1000)
        val loadElapsed = System.currentTimeMillis() - loadStart
        assertEquals(1000, logs.size)
        assertTrue(writeElapsed < 120_000)
        assertTrue(loadElapsed < 5_000)
    }

    @Test
    fun queryLogs_shouldReturnAllRowsWithinSameSecondInStableOrder() {
        val scope = "SecondCollisionScope"
        repeat(8) { index ->
            LogDbManager.insertLog(scope, "INFO", "Tag", "method()", "same-second-$index")
        }
        val logs = LogDbManager.queryLogsAdvanced(scopeTag = scope, limit = 20)
        assertEquals(8, logs.size)
        assertEquals("same-second-7", logs.first()[LoggerX.COLUMN_MESSAGE])
        assertEquals("same-second-0", logs.last()[LoggerX.COLUMN_MESSAGE])
    }

    @Test
    fun queryLogsPage_shouldNotMissRowsWhenMultipleRowsShareSameTimestamp() {
        val scope = "PagedSecondCollisionScope"
        repeat(8) { index ->
            LogDbManager.insertLog(scope, "INFO", "Tag", "method()", "paged-$index")
        }
        val firstPage = LogDbManager.queryLogsPageAdvanced(scopeTag = scope, page = 1, limit = 5)
        val secondPage = LogDbManager.queryLogsPageAdvanced(scopeTag = scope, page = 2, limit = 5)
        val merged = (firstPage.rows + secondPage.rows)
            .map { it[LoggerX.COLUMN_MESSAGE].toString() }
            .toSet()
        assertEquals(8, merged.size)
        assertTrue(merged.contains("paged-7"))
        assertTrue(merged.contains("paged-0"))
    }

    @Test
    fun queryLogsPage_withAnchor_shouldStayStableWhenNewRowsInserted() {
        val scope = "AnchorPagingScope"
        repeat(8) { index ->
            LogDbManager.insertLog(scope, "INFO", "Tag", "method()", "anchor-$index")
        }
        val firstPage = LogDbManager.queryLogsPageAdvanced(scopeTag = scope, page = 1, limit = 5)
        LogDbManager.insertLog(scope, "INFO", "Tag", "method()", "anchor-new")
        val secondPage = LogDbManager.queryLogsPageAdvanced(
            scopeTag = scope,
            page = 2,
            limit = 5,
            anchorTime = firstPage.nextAnchorTime,
            anchorId = firstPage.nextAnchorId
        )
        val merged = (firstPage.rows + secondPage.rows)
            .map { it[LoggerX.COLUMN_MESSAGE].toString() }
            .toSet()
        assertEquals(8, merged.size)
        assertFalse(merged.contains("anchor-new"))
        assertTrue(merged.contains("anchor-7"))
        assertTrue(merged.contains("anchor-0"))
    }

    @Test
    fun upgrade_shouldDropLegacyImgChunkTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val dbPath = context.getDatabasePath("hearthappy_logs.db")
        if (dbPath.exists()) {
            dbPath.delete()
        }
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS Common_log(id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT, level TEXT, tag TEXT, method TEXT, message TEXT, file_path TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS Common_log_img_chunk(id INTEGER PRIMARY KEY AUTOINCREMENT, chunk TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS Error_log_img_chunk(id INTEGER PRIMARY KEY AUTOINCREMENT, chunk TEXT)")
            db.version = 6
        }
        LoggerX.init(context, com.hearthappy.loggerx.core.OutputConfig(storageDirPath = baseDir.absolutePath))
        val helper = com.hearthappy.loggerx.db.LogDbHelper(context)
        helper.writableDatabase.use { db ->
            val legacyTables = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ? ESCAPE '\\'",
                arrayOf("%\\_log\\_img\\_chunk")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    legacyTables += cursor.getString(0)
                }
            }
            assertTrue(legacyTables.isEmpty())
            assertEquals(7, db.version)
        }
    }

    @Test
    fun deleteLogs_shouldRemoveCopiedFiles() {
        val proxy = LoggerX.createScope("DeleteScope")
        val imageFile = fakeBitmapFile("delete_scope.jpg", 256, 256, Bitmap.CompressFormat.JPEG)
        val entry = proxy.image(imageFile, message = "delete")
        assertTrue(File(entry.filePath).exists())
        val rows = proxy.deleteLogs()
        assertTrue(rows > 0)
        assertFalse(File(entry.filePath).exists())
    }

    @Test
    fun export_shouldEmbedFileBase64DataUri() {
        val proxy = LoggerX.createScope("ExportScope")
        val imageFile = fakeBitmapFile("export_scope.png", 64, 64, Bitmap.CompressFormat.PNG)
        val entry = proxy.image(imageFile, message = "export")
        val logs = proxy.queryLogs(limit = 10)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val exportFile = LogExportManager.export(context, "ExportScope", logs)
        assertNotNull(exportFile)
        val text = exportFile!!.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("file_base64"))
        assertTrue(text.contains("data:image/png;base64,"))
        val copiedBytes = File(entry.filePath).readBytes()
        val encoded = Base64.encodeToString(copiedBytes, Base64.NO_WRAP)
        assertTrue(text.contains(encoded))
        exportFile.delete()
    }

    private fun fakeBitmapBytes(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        return out.toByteArray()
    }

    private fun fakeBitmapFile(fileName: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val file = File(baseDir, fileName)
        FileOutputStream(file).use { output ->
            output.write(fakeBitmapBytes(width, height, format))
            output.flush()
        }
        return file
    }
}
