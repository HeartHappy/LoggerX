package com.hearthappy.loggerx.catcher

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.db.LogDbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object CrashUploadDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val uploadedIdsFlow = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    val uploadedIds: SharedFlow<Int> = uploadedIdsFlow.asSharedFlow()

    @Volatile
    private var callback: CrashUploadCallback? = null

    fun setCallback(callback: CrashUploadCallback?) {
        this.callback = callback
        if (callback != null) dispatchPending()
    }

    fun dispatchPending() {
        if (callback == null) return
        scope.launch {
            for (row in LogDbManager.queryUnuploadedCatchLogs()) {
                upload(row)
            }
        }
    }

    fun request(logId: Int) {
        if (callback == null) {
            Log.w(LoggerX.TAG, "catch upload ignored, callback is not set")
            return
        }
        scope.launch {
            val row = LogDbManager.queryCatchLog(logId) ?: return@launch
            upload(row)
        }
    }

    private suspend fun upload(row: Map<String, Any>) {
        val request = row.toUploadRequest() ?: return
        val current = callback ?: return
        if (!inFlight.add(request.id)) return
        try {
            val accepted = runCatching { current.onUploadRequired(request) }
                .onFailure { Log.e(LoggerX.TAG, "crash upload callback failed: ${it.message}", it) }
                .getOrDefault(false)
            if (accepted && LogDbManager.markCatchUploaded(request.id)) {
                uploadedIdsFlow.emit(request.id)
            }
        } finally {
            inFlight.remove(request.id)
        }
    }

    private fun Map<String, Any>.toUploadRequest(): CrashUploadRequest? {
        if (this[LoggerX.COLUMN_UPLOADED]?.toString()?.toIntOrNull() == 1) return null
        val id = this[LoggerX.COLUMN_ID]?.toString()?.toIntOrNull() ?: return null
        val eventId = this[LoggerX.COLUMN_EVENT_ID]?.toString().orEmpty()
        val report = File(this[LoggerX.COLUMN_FILE_PATH]?.toString().orEmpty())
        if (eventId.isBlank() || !report.isFile) return null
        return CrashUploadRequest(
            id = id,
            eventId = eventId,
            time = this[LoggerX.COLUMN_TIME]?.toString().orEmpty(),
            source = this[LoggerX.COLUMN_TAG]?.toString().orEmpty(),
            errorType = this[LoggerX.COLUMN_METHOD]?.toString().orEmpty(),
            message = this[LoggerX.COLUMN_MESSAGE]?.toString().orEmpty(),
            reportFile = report
        )
    }
}
