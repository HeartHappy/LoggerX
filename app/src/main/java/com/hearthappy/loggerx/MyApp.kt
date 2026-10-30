package com.hearthappy.loggerx

import android.app.Application
import android.util.Log
import com.hearthappy.basic.tools.screenadaptation.ScreenAdaptHelper
import com.hearthappy.loggerx.catcher.CrashSource
import com.hearthappy.loggerx.core.CatchConfig
import com.hearthappy.loggerx.core.OutputConfig
import com.hearthappy.loggerx.interceptor.LogInterceptorAdapter
import com.hearthappy.loggerx.model.CrashUploadRequest
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.delay

class MyApp: Application() {

    override fun onCreate() {
        super.onCreate() //日志框架初始化
        LoggerX.init(this, OutputConfig(catchConfig = CatchConfig(enabled = true)))
        LoggerX.registerScope(LogInterceptorAdapter(), CUSTOM_SCOPE)
        ScreenAdaptHelper.setup(this) // 开启按日期自动清理，保留 7 天数据
        LoggerX.enableAutoClean(7)
        LoggerX.enableAutoCleanByLowMemory(100.0)
        // 开启按文件大小自动清理，最大 1MB，每次清理 0.2MB
        //        LoggerX.enableAutoClean(1.0, 0.5)


        //崩溃日志捕获
//        val userStrategy = CrashReport.UserStrategy(this@MyApp)
//        userStrategy.crashHandleCallback = object : CrashReport.CrashHandleCallback() {
//            override fun onCrashHandleStart(crashType: Int,
//                errorType: String?,
//                errorMessage: String?,
//                errorStack: String?): Map<String?, String?> {
//                val source = when (crashType) {
//                    0 -> CrashSource.BUGLY_JAVA
//                    1 -> CrashSource.BUGLY_CAUGHT
//                    2 -> CrashSource.BUGLY_NATIVE
//                    else -> CrashSource.EXTERNAL
//                }
//                LoggerX.reportExternalCrash(
//                    source = source,
//                    crashType = crashType,
//                    errorType = errorType,
//                    errorMessage = errorMessage,
//                    errorStack = errorStack
//                )
//                return emptyMap()
//            }
//        }


        LoggerX.setListener(
            setCrashListener = { request -> uploadCatchReport(request) },
            setLoggerXListener = { entry ->
                Log.d(TAG, "LoggerX inserted: scope=${entry.scope}, id=${entry.id}, level=${entry.level}, tag=${entry.tag}, message=${entry.message}")
            }
        )
//        CrashReport.initCrashReport(this@MyApp, "e564c7798c", false, userStrategy)
        LoggerX.installNativeCrashCapture()
    }

    // setCrashListener 处理未上传 CATCH；setLoggerXListener 可选，用于监听每条成功入库日志。
    private suspend fun uploadCatchReport(request: CrashUploadRequest): Boolean {
        // TODO: 把 request.reportFile 上传到本项目接口。服务端按 request.eventId 去重。
        delay(2000)
        Log.i(TAG, "upload catch report: eventId=${request.eventId}, file=${request.reportFile.absolutePath}")
        return true
    }

    companion object {
        private const val TAG = "MyApp"
        val CUSTOM_SCOPE = LoggerX.createScope("CustomScope")
    }
}
