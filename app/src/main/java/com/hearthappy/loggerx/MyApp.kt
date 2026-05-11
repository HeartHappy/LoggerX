package com.hearthappy.loggerx

import android.app.Application
import com.hearthappy.basic.tools.screenadaptation.ScreenAdaptHelper
import com.hearthappy.loggerx.interceptor.LogInterceptorAdapter

class MyApp: Application() {

    override fun onCreate() {
        super.onCreate() //日志框架初始化
        LoggerX.init(this)
        LoggerX.registerScope(LogInterceptorAdapter(), CUSTOM_SCOPE)
        ScreenAdaptHelper.setup(this) // 开启按日期自动清理，保留 7 天数据
        LoggerX.enableAutoClean(7)
        LoggerX.enableAutoCleanByLowMemory(55.96*1024)
        // 开启按文件大小自动清理，最大 1MB，每次清理 0.2MB
        //        LoggerX.enableAutoClean(1.0, 0.5)
    }

    companion object {
        val CUSTOM_SCOPE = LoggerX.createScope("CustomScope")
    }
}
