package com.hearthappy.loggerx.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.Date

/**
 * Created Date: 2025/12/17/周三
 * @author ChenRui
 * ClassDescription：上下文采集器（自动获取Activity/类/方法/日期）
 */
object LogContextCollector {
    private var currentActivity: WeakReference<Activity>? = null

    /**
     * 初始化：在Application中调用，注册Activity生命周期以获取当前Activity
     */
    internal fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 获取当前Activity名称（无则返回UnknownActivity）
     */
    internal fun getCurrentActivityName(): String {
        return currentActivity?.get()?.javaClass?.simpleName ?: "UnknownActivity"
    }

    /**
     * 获取当前日期（格式化）
     */
    internal fun getCurrentDate(): String {
        return LogFormatter.DATE_FORMAT.format(Date())
    }

    internal fun getContext(): Context {
        return ContextHolder.getAppContext()
    }

    /**
     * 获取调用栈信息（类名、方法名、行号）：自动跳过日志框架自身的栈帧
     */
    internal fun getStackTraceInfo(): StackTraceInfo {
        val stackTrace = Thread.currentThread().stackTrace // 跳过日志框架的栈帧，定位到业务代码的调用点
        val targetIndex = findTargetStackTraceIndex(stackTrace)
        val targetElement = stackTrace.getOrNull(targetIndex) ?: return StackTraceInfo()
        
        // 解析类名：处理内部类（如 MainActivity$logRunnable$1 等）
        var className = targetElement.className.substringAfterLast(".")
        val innerClassIndex = className.indexOf('$')
        if (innerClassIndex > 0) {
            className = className.substring(0, innerClassIndex)
        }
        
        return StackTraceInfo(
            className = className, // 简化类名（去掉包名及内部类后缀）
            methodName = targetElement.methodName, 
            lineNumber = targetElement.lineNumber
        )
    }

    /**
     * 找到业务代码的栈帧索引（跳过日志工具类的栈）
     */
    private fun findTargetStackTraceIndex(stackTrace: Array<StackTraceElement>): Int {
        val loggerPackage = "com.hearthappy.loggerx"
        var foundLogClass = false
        
        for (i in stackTrace.indices) {
            val element = stackTrace[i]
            val className = element.className
            
            // 判断是否是 LoggerX 框架内部的类（包括代理类、输出类、核心采集类等）
            val isLogClass = className.startsWith("$loggerPackage.core") || 
                             className.startsWith("$loggerPackage.db") ||
                             className.startsWith("$loggerPackage.LoggerX") ||
                             className.startsWith("$loggerPackage.interceptor")
            
            if (isLogClass) {
                foundLogClass = true
            } else if (foundLogClass) {
                // 当我们已经进入过日志库，然后遇到第一个不是日志库内部类的栈时
                // 这就是外部调用者（即使它的包名也是 com.hearthappy.loggerx，例如 app module 的 MainActivity）
                return i
            }
        }
        
        // 兜底逻辑：如果前面的逻辑失效，寻找第一个不属于框架内部核心逻辑的栈
        for (i in stackTrace.indices) {
            val className = stackTrace[i].className
            if (!className.startsWith("$loggerPackage.core") && 
                !className.startsWith("$loggerPackage.db") &&
                !className.startsWith("$loggerPackage.LoggerX") &&
                !className.startsWith("java.") && 
                !className.startsWith("android.") && 
                !className.startsWith("dalvik.")) {
                return i
            }
        }
        
        return -1
    }

    /**
     * 调用栈信息数据类
     */
    data class StackTraceInfo(val className: String = "UnknownClass", val methodName: String = "unknownMethod", val lineNumber: Int = 0)
}