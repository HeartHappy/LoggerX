package com.hearthappy.loggerx.catcher

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.UUID

internal object CrashEnvironment {
    lateinit var application: Application
        private set
    var sessionId: String = ""
        private set

    fun initialize(application: Application) {
        this.application = application
        sessionId = UUID.randomUUID().toString()
    }

    fun isInitialized(): Boolean = ::application.isInitialized

    fun processName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            .orEmpty()
            .ifBlank { application.packageName }
    }

    fun deviceAndAppDetails(): String {
        val packageInfo = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (packageInfo?.versionCode ?: 0).toLong()
        }
        return buildString {
            appendLine("package=${application.packageName}")
            appendLine("versionName=${packageInfo?.versionName.orEmpty()}")
            appendLine("versionCode=$versionCode")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            append("abi=${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }
}
