package com.hearthappy.loggerx.catcher

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.CatchConfig
import com.hearthappy.loggerx.core.Utils
import com.hearthappy.loggerx.db.LogDbManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.edit

internal object CrashCaptureManager {
    private const val PREFS_NAME = "loggerx_catch"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_EXIT_CHECKPOINT = "exit_checkpoint"

    private var config: CatchConfig = CatchConfig()
    private var installedHandler: CatchExceptionHandler? = null
    private val recovering = AtomicBoolean(false)

    fun initialize(application: Application, config: CatchConfig) {
        CrashEnvironment.initialize(application)
        this.config = config
        CrashEmergencyStore.initialize(application, config)
        LogDbManager.prepareCatchTable()
        recover()
        if (config.enabled) enable(config)
    }

    @Synchronized
    fun enable(config: CatchConfig = CatchConfig(enabled = true)) {
        check(CrashEnvironment.isInitialized()) { "LoggerX.init() must be called before enabling crash capture" }
        this.config = config.copy(enabled = true)
        CrashEmergencyStore.updateConfig(this.config)
        val preferences = preferences()
        preferences.edit(commit = true) {
            putBoolean(PREF_ENABLED, true)
            if (!preferences.contains(PREF_EXIT_CHECKPOINT)) {
                putLong(PREF_EXIT_CHECKPOINT, System.currentTimeMillis())
            }
        }
        if (this.config.captureJvmCrashes && installedHandler == null) {
            val handler = CatchExceptionHandler(Thread.getDefaultUncaughtExceptionHandler())
            installedHandler = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    @Synchronized
    fun disable() {
        if (!CrashEnvironment.isInitialized()) return
        preferences().edit(commit = true) { putBoolean(PREF_ENABLED, false) }
        val handler = installedHandler
        if (handler != null && Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(handler.previous)
        }
        installedHandler = null
        config = config.copy(enabled = false)
    }

    fun isEnabled(): Boolean {
        return CrashEnvironment.isInitialized() && preferences().getBoolean(PREF_ENABLED, config.enabled)
    }

    fun reportExternalCrash(
        source: CrashSource,
        crashType: Int?,
        errorType: String?,
        errorMessage: String?,
        errorStack: String?,
        extras: Map<String, String>
    ) {
        if (!isEnabled()) return
        val details = buildString {
            crashType?.let { appendLine("externalCrashType=$it") }
            for ((key, value) in extras) appendLine("$key=$value")
            append(CrashEnvironment.deviceAndAppDetails())
        }
        CrashEmergencyStore.writePending(
            CrashRecord(
                source = source,
                kind = source.toKind(),
                occurredAt = System.currentTimeMillis(),
                sessionId = CrashEnvironment.sessionId,
                processName = CrashEnvironment.processName(),
                threadName = Thread.currentThread().name,
                errorType = errorType.orEmpty(),
                errorMessage = errorMessage.orEmpty(),
                stackTrace = errorStack.orEmpty(),
                details = details
            ),
            prefix = source.name.lowercase(Locale.US)
        )
    }

    fun recordCaughtException(throwable: Throwable, message: String) {
        if (!isEnabled()) return
        CrashEmergencyStore.writePending(
            throwableRecord(CrashSource.BUGLY_CAUGHT, CrashKind.CAUGHT, Thread.currentThread(), throwable, message),
            prefix = "caught"
        )
        recover()
    }

    fun beginNativeCall(operation: String, metadata: Map<String, String>): NativeCallToken? {
        if (!isEnabled() || !config.captureNativeCallContext) return null
        val thread = Thread.currentThread()
        val stack = thread.stackTrace
            .dropWhile { it.className.startsWith("com.hearthappy.loggerx") || it.className == Thread::class.java.name }
            .joinToString("\n") { "    at $it" }
        val details = buildString {
            for ((key, value) in metadata) appendLine("$key=$value")
            appendLine("state=in_native_call")
            append(CrashEnvironment.deviceAndAppDetails())
        }
        val file = CrashEmergencyStore.writePending(
            CrashRecord(
                source = CrashSource.NATIVE_CALL_CONTEXT,
                kind = CrashKind.NATIVE_CONTEXT,
                occurredAt = System.currentTimeMillis(),
                sessionId = CrashEnvironment.sessionId,
                processName = CrashEnvironment.processName(),
                threadName = thread.name,
                errorType = operation,
                errorMessage = "Native call did not return before the process exited",
                stackTrace = stack,
                details = details
            ),
            prefix = "native_context"
        ) ?: return null
        return NativeCallToken(file.name)
    }

    fun endNativeCall(token: NativeCallToken?) {
        token?.let { CrashEmergencyStore.deletePending(it.recordFileName) }
    }

    fun recover(): Int {
        if (!CrashEnvironment.isInitialized() || !recovering.compareAndSet(false, true)) return 0
        return try {
            val preferences = preferences()
            val checkpoint = preferences.getLong(PREF_EXIT_CHECKPOINT, System.currentTimeMillis())
            val systemResult = if (preferences.getBoolean(PREF_ENABLED, config.enabled)) {
                SystemExitInfoCollector.collect(CrashEnvironment.application, checkpoint, config)
            } else {
                SystemExitInfoCollector.Result(emptyList(), checkpoint)
            }
            val inputs = CrashEmergencyStore.loadPending()
                .map { CrashReportImporter.Input(it.record, it.file) }
                .toMutableList()
            for (record in systemResult.records) inputs += CrashReportImporter.Input(record, null)
            val result = CrashReportImporter.import(inputs)
            if (result.systemRecordsSucceeded && systemResult.newestTimestamp > checkpoint) {
                preferences.edit(commit = true) { putLong(PREF_EXIT_CHECKPOINT, systemResult.newestTimestamp) }
            }
            CrashUploadDispatcher.dispatchPending()
            result.importedCount
        } catch (throwable: Throwable) {
            Log.e(LoggerX.TAG, "recover crash reports failed: ${throwable.message}", throwable)
            0
        } finally {
            recovering.set(false)
        }
    }

    private fun throwableRecord(source: CrashSource, kind: CrashKind, thread: Thread, throwable: Throwable, message: String): CrashRecord {
        return CrashRecord(
            source = source,
            kind = kind,
            occurredAt = System.currentTimeMillis(),
            sessionId = CrashEnvironment.sessionId,
            processName = CrashEnvironment.processName(),
            threadName = "${thread.name}(${thread.id})",
            errorType = throwable.javaClass.name,
            errorMessage = message.ifBlank { throwable.message.orEmpty() },
            stackTrace = Utils.getStackTraceString(throwable),
            details = CrashEnvironment.deviceAndAppDetails()
        )
    }

    private fun CrashSource.toKind(): CrashKind {
        return when (this) {
            CrashSource.BUGLY_JAVA, CrashSource.JVM -> CrashKind.JVM
            CrashSource.BUGLY_NATIVE, CrashSource.SYSTEM_NATIVE -> CrashKind.NATIVE
            CrashSource.BUGLY_ANR, CrashSource.SYSTEM_ANR -> CrashKind.ANR
            CrashSource.BUGLY_CAUGHT -> CrashKind.CAUGHT
            CrashSource.NATIVE_CALL_CONTEXT -> CrashKind.NATIVE_CONTEXT
            else -> CrashKind.EXIT
        }
    }

    private fun preferences() = CrashEnvironment.application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class CatchExceptionHandler(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        private val handling = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (handling.compareAndSet(false, true)) {
                runCatching {
                    CrashEmergencyStore.writePending(
                        throwableRecord(CrashSource.JVM, CrashKind.JVM, thread, throwable, throwable.message.orEmpty()),
                        prefix = "jvm"
                    )
                }.onFailure {
                    Log.e(LoggerX.TAG, "capture uncaught exception failed: ${it.message}")
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
