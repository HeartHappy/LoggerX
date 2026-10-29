package com.hearthappy.loggerx.listener

import com.hearthappy.loggerx.model.CrashUploadRequest
import com.hearthappy.loggerx.model.LoggerXLogEntry


/** 宿主完成上传后返回 true，LoggerX 会把对应 CATCH 记录标记为已上传。 */
fun interface CrashListener {
    suspend fun onCrash(request: CrashUploadRequest): Boolean
}


/** 日志成功插入数据库后触发；不影响日志写入结果。 */
fun interface LoggerXInsertListener {
    fun onLogInserted(entry: LoggerXLogEntry)
}
