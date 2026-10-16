package com.hearthappy.loggerx.catcher

import java.io.File

/**
 * 一条尚未上传的 CATCH 报告。reportFile 是合并后的 text/plain 崩溃报告。
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

/**
 * 宿主实现实际上传。返回 true 后，LoggerX 把该条 CATCH 记为已上传。
 */
fun interface CrashUploadCallback {
    suspend fun onUploadRequired(request: CrashUploadRequest): Boolean
}
