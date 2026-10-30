package com.hearthappy.loggerx.catcher

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import java.io.File

internal object NativeCrashCapture {
    fun install(directory: File, processName: String) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.w(LoggerX.TAG, "native crash directory unavailable: ${directory.absolutePath}")
            return
        }
        val installed = runCatching {
            NativeCrashBridge.install(directory.absolutePath, processName)
        }.getOrElse { throwable ->
            Log.w(LoggerX.TAG, "native crash capture install failed: ${throwable.message}")
            false
        }
        if (!installed) Log.w(LoggerX.TAG, "native crash capture was not installed")
    }

    fun uninstall() {
        runCatching { NativeCrashBridge.uninstall() }
    }
}
