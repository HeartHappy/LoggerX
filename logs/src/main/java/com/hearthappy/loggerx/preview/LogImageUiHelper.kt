package com.hearthappy.loggerx.preview

import com.hearthappy.loggerx.LoggerX

object LogImageUiHelper {
    fun isImageLog(log: Map<String, Any>): Boolean {
        return resolveMimeType(log).startsWith("image/") || log[LoggerX.COLUMN_IS_IMAGE]?.toString() == "1"
    }

    fun hasAttachment(log: Map<String, Any>): Boolean = resolveFilePath(log).isNotBlank()

    fun isTextAttachment(log: Map<String, Any>): Boolean {
        val mimeType = resolveMimeType(log)
        return mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml"
    }

    fun resolveFilePath(log: Map<String, Any>): String {
        return log[LoggerX.COLUMN_FILE_PATH]?.toString().orEmpty().trim()
    }

    fun resolveMimeType(log: Map<String, Any>): String {
        return log[LoggerX.COLUMN_FILE_MIME]?.toString().orEmpty().trim()
    }
}
