package com.hearthappy.loggerx.listener

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.model.LoggerXLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object LoggerXListenerDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var logInsertListener: LoggerXInsertListener? = null

    fun setLogInsertListener(listener: LoggerXInsertListener?) {
        logInsertListener = listener
    }

    fun notifyLogInserted(scopeTag: String, row: Map<String, Any>) {
        val listener = logInsertListener ?: return
        val entry = row.toLogEntry(scopeTag) ?: return
        scope.launch {
            runCatching { listener.onLogInserted(entry) }
                .onFailure { Log.e(LoggerX.TAG, "LoggerX insert listener failed: ${it.message}", it) }
        }
    }

    private fun Map<String, Any>.toLogEntry(scopeTag: String): LoggerXLogEntry? {
        val id = this[LoggerX.COLUMN_ID]?.toString()?.toIntOrNull() ?: return null
        return LoggerXLogEntry(
            id = id,
            scope = scopeTag,
            time = this[LoggerX.COLUMN_TIME]?.toString().orEmpty(),
            level = this[LoggerX.COLUMN_LEVEL]?.toString().orEmpty(),
            tag = this[LoggerX.COLUMN_TAG]?.toString().orEmpty(),
            method = this[LoggerX.COLUMN_METHOD]?.toString().orEmpty(),
            message = this[LoggerX.COLUMN_MESSAGE]?.toString().orEmpty(),
            filePath = this[LoggerX.COLUMN_FILE_PATH]?.toString().orEmpty(),
            fileMime = this[LoggerX.COLUMN_FILE_MIME]?.toString().orEmpty(),
            eventId = this[LoggerX.COLUMN_EVENT_ID]?.toString().orEmpty()
        )
    }
}
