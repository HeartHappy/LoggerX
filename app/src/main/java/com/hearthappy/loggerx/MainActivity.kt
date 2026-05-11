package com.hearthappy.loggerx

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hearthappy.loggerx.preview.PreviewLogActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity: AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var logCount = 0

    private val logRunnable = object: Runnable {
        override fun run() {
            val message = "测试日志消息 $logCount - ${System.currentTimeMillis()}"
            LoggerX.COMMON.i("testMethod:$message")
            logCount++
            if (logCount % 100 == 0) {
                Log.d(TAG, "Inserted $logCount logs.")
            }
            handler.postDelayed(this, 100) // 每100毫秒插入一条日志
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 启动循环插入日志
        handler.post(logRunnable)

        val dbFileSize = LoggerX.getDbFileSize()
        LoggerX.COMMON.i("onCreate: $dbFileSize")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(logRunnable)
    }


    companion object {
        private const val TAG = "MainActivity"
    }


    fun outLogAndFile(view: View) { // Log common test messages
        LoggerX.COMMON.d("common test")
        LoggerX.COMMON.d("common onCreate d")
        LoggerX.COMMON.i("common onCreate i")
        LoggerX.COMMON.w("common onCreate w")

        // Log important information
        LoggerX.IMPORTANT.d("important onCreate d 挺不错的，哈哈")

        // Log kernel related events
        LoggerX.KERNEL.d("kernel onCreate d")
        LoggerX.KERNEL.w("kernel onResume w")
        LoggerX.KERNEL.i("kernel onCreate1 i")
        LoggerX.KERNEL.v("kernel onCreate3 v")
        LoggerX.ERROR.e("kernel onCreate2 e", Throwable("runtime error"))

        // Create and log a JSON object
        val jSONObject = JSONObject().apply {
            put("name", "张三")
            put("age", 18)
            put("sex", "男")
        }
        LoggerX.COMMON.json(jSONObject.toString())
        MyApp.CUSTOM_SCOPE.d("自定义的作用域日志  测试！！！")

        // Log image operations
        LoggerX.COMMON.image(copyResourceToTempFile(R.mipmap.test_face, "test_face.png"), message = "裁剪图：")
        LoggerX.COMMON.image(copyResourceToTempFile(R.mipmap.test1, "test1.jpg"), message = "壁纸图1：")
        LoggerX.COMMON.image(copyResourceToTempFile(R.mipmap.test2, "test2.jpg"), message = "壁纸图2：")
    }

    fun deleteLogFile(view: View) {
        val kernelRows = LoggerX.KERNEL.deleteLogs()
        val commonRows = LoggerX.ERROR.deleteLogs()
        Toast.makeText(this, "已删除 KERNEL:$kernelRows, ERROR:$commonRows", Toast.LENGTH_SHORT).show()
    }

    fun deleteAllLogFile(view: View) {
        val clearAll = LoggerX.clear()
        LoggerX.COMMON.d("已清空所有日志")
    }

    fun openFile(view: View) {
        LoggerX.KERNEL.doExportAndShare(exportAll = false, limit = 500)
        Toast.makeText(this, "已从数据库导出最近日志并调用分享", Toast.LENGTH_SHORT).show()
    }

    fun queryDBLogs(view: View) {
        val distinctValues = LoggerX.IMPORTANT.getDistinctValues(LoggerX.COLUMN_METHOD)
        LoggerX.COMMON.d("queryDBLogs: ${distinctValues.toList()}")
        val queryLogs = LoggerX.IMPORTANT.queryLogs(isAsc = false)
        LoggerX.COMMON.d("queryDBLogs: ${queryLogs.toList().joinToString { "$it" }}")
        startActivity(Intent(this, PreviewLogActivity::class.java))
    }

    fun shareFile(view: View) {
        LoggerX.COMMON.i("测试==========")
        LoggerX.COMMON.doExportAndShare()
        LoggerX.exportAndShareAll {
            LoggerX.COMMON.i("shareFile: $it")
        }
    }

    private fun copyResourceToTempFile(resId: Int, fileName: String): File {
        val target = File(externalCacheDir, fileName)
        if (target.exists()) return target
        resources.openRawResource(resId).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        return target
    }

}
