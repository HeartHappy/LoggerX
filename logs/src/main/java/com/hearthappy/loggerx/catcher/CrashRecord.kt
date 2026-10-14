package com.hearthappy.loggerx.catcher

import org.json.JSONObject

internal enum class CrashKind {
    JVM,
    NATIVE,
    ANR,
    CAUGHT,
    EXIT,
    NATIVE_CONTEXT
}

internal data class CrashRecord(
    val source: CrashSource,
    val kind: CrashKind,
    val occurredAt: Long,
    val sessionId: String,
    val processName: String,
    val threadName: String,
    val errorType: String,
    val errorMessage: String,
    val stackTrace: String,
    val details: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_VERSION, FORMAT_VERSION)
            put(KEY_SOURCE, source.name)
            put(KEY_KIND, kind.name)
            put(KEY_OCCURRED_AT, occurredAt)
            put(KEY_SESSION_ID, sessionId)
            put(KEY_PROCESS_NAME, processName)
            put(KEY_THREAD_NAME, threadName)
            put(KEY_ERROR_TYPE, errorType)
            put(KEY_ERROR_MESSAGE, errorMessage)
            put(KEY_STACK_TRACE, stackTrace)
            put(KEY_DETAILS, details)
        }.toString()
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SOURCE = "source"
        private const val KEY_KIND = "kind"
        private const val KEY_OCCURRED_AT = "occurred_at"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_PROCESS_NAME = "process_name"
        private const val KEY_THREAD_NAME = "thread_name"
        private const val KEY_ERROR_TYPE = "error_type"
        private const val KEY_ERROR_MESSAGE = "error_message"
        private const val KEY_STACK_TRACE = "stack_trace"
        private const val KEY_DETAILS = "details"

        fun fromJson(json: String): CrashRecord {
            val value = JSONObject(json)
            return CrashRecord(
                source = enumValueOrDefault(value.optString(KEY_SOURCE), CrashSource.EXTERNAL),
                kind = enumValueOrDefault(value.optString(KEY_KIND), CrashKind.EXIT),
                occurredAt = value.optLong(KEY_OCCURRED_AT, System.currentTimeMillis()),
                sessionId = value.optString(KEY_SESSION_ID),
                processName = value.optString(KEY_PROCESS_NAME),
                threadName = value.optString(KEY_THREAD_NAME),
                errorType = value.optString(KEY_ERROR_TYPE),
                errorMessage = value.optString(KEY_ERROR_MESSAGE),
                stackTrace = value.optString(KEY_STACK_TRACE),
                details = value.optString(KEY_DETAILS)
            )
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
            return enumValues<T>().firstOrNull { it.name == value } ?: default
        }
    }
}
