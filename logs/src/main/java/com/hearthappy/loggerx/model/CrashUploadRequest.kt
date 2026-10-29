package com.hearthappy.loggerx.model

import java.io.File

/**
 * 一条尚未上传的 CATCH 报告。
 *
 * @property id CATCH 表中的日志主键。
 * @property eventId 崩溃事件唯一标识，服务端可使用该值进行幂等去重。
 * @property time 实际崩溃时间，格式与 LoggerX 日志时间字段一致。
 * @property source 崩溃来源，例如 JAVA_FATAL、NATIVE_CRASH 或 ANR。
 * @property errorType 异常类名、Native signal 或系统退出原因。
 * @property message LoggerX 生成的崩溃摘要与调用栈预览。
 * @property reportFile 合并后的完整 text/plain 崩溃报告文件。
 */
data class CrashUploadRequest(
    val id: Int,
    val eventId: String,
    val time: String,
    val source: String,
    val errorType: String,
    val message: String,
    val reportFile: File
)
