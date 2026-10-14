package com.hearthappy.loggerx.core

import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.interceptor.LogInterceptor

internal object LogOutputterManager {

    private val outputterMap = mutableMapOf<String, LogOutputter>()

    /**
     * 简易工厂方法：快速创建自定义Scope（开发者无需实现接口）
     */
    internal fun create(customScope: String): LogScopeProxy {
        require(customScope.isNotBlank()) { "Scope标签不能为空" }
        return LogScopeProxy(customScope)
    }

    /**
     * 初始化默认Scope输出器：新增Scope只需在此添加
     */
    internal fun initDefaultOutputters(outputConfig: OutputConfig) {
        val isLog = outputConfig.isLog
        val isWriteDatabase = outputConfig.isWriteDatabase
        val logInterceptorAdapter = object: LogInterceptor {
            override fun isDebug(): Boolean = isLog
            override fun isWriteDatabase(): Boolean = isWriteDatabase
        }
        outputterMap[LoggerX.COMMON.getTag()] = LogOutputter(scope = LoggerX.COMMON, logInterceptorAdapter)
        outputterMap[LoggerX.IMPORTANT.getTag()] = LogOutputter(scope = LoggerX.IMPORTANT, logInterceptorAdapter)
        outputterMap[LoggerX.KERNEL.getTag()] = LogOutputter(scope = LoggerX.KERNEL, logInterceptorAdapter)
        outputterMap[LoggerX.ERROR.getTag()] = LogOutputter(scope = LoggerX.ERROR, logInterceptorAdapter)
        outputterMap[LoggerX.CATCH.getTag()] = LogOutputter(scope = LoggerX.CATCH, logInterceptorAdapter)
    }


    /**
     * 扩展自定义Scope输出器（动态新增）
     */
    internal fun registerOutputter(scope: String, outputter: LogOutputter) {
        outputterMap[scope] = outputter
    }

    /**
     * 获取Scope对应的输出器
     */
    internal fun getOutputter(scope: String): LogOutputter {
        return outputterMap[scope] ?: throw IllegalArgumentException("未注册的Scope：${scope}")
    }

    internal fun getScopes(): MutableSet<String> {
        return outputterMap.keys
    }

    internal fun getOutputters(): MutableCollection<LogOutputter> {
        return outputterMap.values
    }

}
