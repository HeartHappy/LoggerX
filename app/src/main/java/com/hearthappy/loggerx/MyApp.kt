package com.hearthappy.loggerx

import android.app.Application
import com.hearthappy.basic.tools.screenadaptation.ScreenAdaptHelper
import com.hearthappy.loggerx.catcher.CrashSource
import com.hearthappy.loggerx.core.CatchConfig
import com.hearthappy.loggerx.interceptor.LogInterceptorAdapter
import com.tencent.bugly.crashreport.CrashReport

class MyApp: Application() {

    override fun onCreate() {
        super.onCreate() //日志框架初始化
        LoggerX.init(this)
        LoggerX.registerScope(LogInterceptorAdapter(), CUSTOM_SCOPE)
        ScreenAdaptHelper.setup(this) // 开启按日期自动清理，保留 7 天数据
        LoggerX.enableAutoClean(7)
        LoggerX.enableAutoCleanByLowMemory(100.0)
        // 开启按文件大小自动清理，最大 1MB，每次清理 0.2MB
        //        LoggerX.enableAutoClean(1.0, 0.5)


        //崩溃日志捕获
        val userStrategy = CrashReport.UserStrategy(this@MyApp)
        userStrategy.crashHandleCallback = object : CrashReport.CrashHandleCallback() {
            override fun onCrashHandleStart(crashType: Int,
                errorType: String?,
                errorMessage: String?,
                errorStack: String?): Map<String?, String?> {
                val source = when (crashType) {
                    0 -> CrashSource.BUGLY_JAVA
                    1 -> CrashSource.BUGLY_CAUGHT
                    2 -> CrashSource.BUGLY_NATIVE
                    else -> CrashSource.EXTERNAL
                }
                LoggerX.reportExternalCrash(
                    source = source,
                    crashType = crashType,
                    errorType = errorType,
                    errorMessage = errorMessage,
                    errorStack = errorStack,
                    extras = mapOf("provider" to "Bugly")
                )
                return emptyMap()
            }
        }
        CrashReport.initCrashReport(this@MyApp, "e564c7798c", false, userStrategy)
        LoggerX.enableCatch(
            CatchConfig(
                enabled = true,
                captureJvmCrashes = true,
                captureSystemExitInfo = true,
                captureNativeCallContext = true
            )
        )
    }

    companion object {
        val CUSTOM_SCOPE = LoggerX.createScope("CustomScope")
    }
}
