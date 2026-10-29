package com.hearthappy.loggerx

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import com.hearthappy.basic.AbsBaseActivity
import com.hearthappy.loggerx.app.R
import com.hearthappy.loggerx.app.databinding.ActivityMainBinding
import com.hearthappy.loggerx.preview.PreviewLogActivity
import com.tencent.bugly.crashreport.CrashReport
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity: AbsBaseActivity<ActivityMainBinding>() {
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




    override fun ActivityMainBinding.initData() {
        // 启动循环插入日志
//        handler.post(logRunnable)

        val dbFileSize = LoggerX.getDbFileSize()
        LoggerX.COMMON.i("onCreate: $dbFileSize")
    }

    override fun ActivityMainBinding.initListener() {
    }

    override fun ActivityMainBinding.initView() {

    }

    override fun ActivityMainBinding.initViewModelListener() {
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
        startActivity(Intent(this, PreviewLogActivity::class.java))
    }

    fun shareFile(view: View) {
        LoggerX.COMMON.i("测试==========")
        LoggerX.COMMON.doExportAndShare()
        LoggerX.exportAndShareAll {
            LoggerX.COMMON.i("shareFile: $it")
        }
    }

    fun testJavaCrash(view: View) {
        LoggerX.COMMON.i("即将触发主线程 Java 崩溃")
        handler.postDelayed({
            throw IllegalStateException("LoggerX demo main-thread Java crash")
        }, 300)
    }

    fun testBackgroundCrash(view: View) {
        LoggerX.COMMON.i("即将触发后台线程 Java 崩溃")
        Thread({
            throw IllegalArgumentException("LoggerX demo background-thread Java crash")
        }, "loggerx-crash-worker").start()
    }

    fun testNativeCrash(view: View) {
        LoggerX.COMMON.i("即将通过 Bugly 触发 Native 崩溃")
        LoggerX.traceNativeCall {
            CrashReport.testNativeCrash()
        }
    }

    fun testCaughtException(view: View) {
        val throwable = IllegalStateException("LoggerX demo caught exception")
        LoggerX.recordException(throwable, "Demo 主动记录的非致命异常")
        CrashReport.postCatchedException(throwable)
        Toast.makeText(this, "已写入 CATCH", Toast.LENGTH_SHORT).show()
    }

    fun recoverCrashLogs(view: View) {
        val count = LoggerX.recoverCatchLogs()
        Toast.makeText(this, "本次归档 $count 条 CATCH 日志", Toast.LENGTH_SHORT).show()
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
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(logRunnable)
    }
}
