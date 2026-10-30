package com.hearthappy.loggerx.core

import com.hearthappy.loggerx.interceptor.LogInterceptor

/**
 * Created Date: 2025/12/17/周三
 * @author ChenRui
 * ClassDescription：日志输出器
 */
class LogOutputter(val scope: LogScope, private val logInterceptor: LogInterceptor) {

    private val logImpl by lazy { LogImpl(scope, logInterceptor) }

    /**
     * 输出日志（自动处理上下文+格式化）
     */
    internal fun output(level: LogLevel, message: String, throwable: Throwable? = null) {
        logImpl.log(level.level, message, throwable)
    }

    internal fun json(json: String?) {
        logImpl.json(json)
    }

    internal fun xml(xml: String?) {
        logImpl.xml(xml)
    }
}