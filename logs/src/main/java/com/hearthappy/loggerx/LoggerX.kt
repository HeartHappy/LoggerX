package com.hearthappy.loggerx

import android.app.Application
import android.content.Context
import com.hearthappy.loggerx.core.ContextHolder
import com.hearthappy.loggerx.core.CatchConfig
import com.hearthappy.loggerx.core.LogExportManager
import com.hearthappy.loggerx.core.LogOutputter
import com.hearthappy.loggerx.core.LogOutputterManager
import com.hearthappy.loggerx.core.LogScope
import com.hearthappy.loggerx.core.LogScopeProxy
import com.hearthappy.loggerx.core.OutputConfig
import com.hearthappy.loggerx.db.LogDbManager
import com.hearthappy.loggerx.interceptor.LogInterceptor
import com.hearthappy.loggerx.catcher.CrashCaptureManager
import com.hearthappy.loggerx.catcher.CrashSource
import com.hearthappy.loggerx.catcher.CrashUploadDispatcher
import com.hearthappy.loggerx.listener.CrashListener
import com.hearthappy.loggerx.listener.LoggerXInsertListener
import com.hearthappy.loggerx.listener.LoggerXListenerDispatcher
import java.io.File

/**
 * Created Date: 2023/9/20
 * @author ChenRui
 * ClassDescription： 日志框架
 *   极简日志调用入口（对外暴露）
 *   用法：
 *   LoggerX.COMMON.d("普通日志")
 *   LoggerX.IMPORTANT.e("重要错误日志", Throwable("异常"))
 *   LoggerX.KERNEL.i("核心日志")
 *   LoggerX.ERROR.w("错误域警告日志") // 新增的Error域
 *   支持：
 *   1、拦截器：拦截日志，自定义写入方式
 *   2、查询日志：支持高级查询、智能去重查询
 *   3、删除指定Scope的所有数据，删除旧数据
 *   4、自动清理日志：支持按时间清理和按文件大小清理
 */
class LoggerX {
    companion object {
        internal const val TAG = "LoggerX"

        //字段常量
        internal const val COLUMN_ID = "id"
        internal const val COLUMN_TIME = "time"
        internal const val COLUMN_LEVEL = "level"
        internal const val COLUMN_TAG = "tag"
        internal const val COLUMN_METHOD = "method"
        internal const val COLUMN_MESSAGE = "message"
        internal const val COLUMN_IS_IMAGE = "is_image" // virtual output column
        internal const val COLUMN_FILE_PATH = "file_path"
        internal const val COLUMN_FILE_MIME = "file_mime"
        internal const val COLUMN_EVENT_ID = "event_id"
        internal const val COLUMN_UPLOADED = "uploaded"

        // ========== 内置Scope（直接调用） ==========
        val COMMON = LogScopeProxy("Common")
        val IMPORTANT = LogScopeProxy("Important")
        val KERNEL = LogScopeProxy("Kernel")
        val ERROR = LogScopeProxy("Error")
        val CATCH = LogScopeProxy("Catch")

        @Volatile internal var outputConfig: OutputConfig = OutputConfig()

        // ========== 自定义Scope（扩展用） ==========
        /**
         * 注册一个或多个自定义日志作用域。
         *
         * 相同标签再次注册时会替换原有输出器。应在首次使用对应作用域前完成注册。
         *
         * @param logInterceptor 自定义作用域使用的日志输出与数据库写入拦截器。
         * @param scopes 需要注册的日志作用域。
         */
        fun registerScope(logInterceptor: LogInterceptor, vararg scopes: LogScope) {
            for (scope in scopes) {
                LogOutputterManager.registerOutputter(scope.getTag(), LogOutputter(scope = scope, logInterceptor))
            }
        }

        /**
         * 创建自定义作用域代理。
         *
         * 创建代理不会自动注册输出器，使用前还需通过 [registerScope] 注册。
         *
         * @param customScope 作用域标签，不能为空白字符串；该标签也用于生成日志表名。
         * @return 可用于记录、查询、删除和导出该作用域日志的代理。
         * @throws IllegalArgumentException 当 [customScope] 为空白字符串时抛出。
         */
        fun createScope(customScope: String): LogScopeProxy {
            return LogOutputterManager.create(customScope)
        }

        /**
         * 初始化 LoggerX。
         *
         * 应在 `Application.onCreate()` 中、首次使用任何 LoggerX API 前调用。初始化会注册内置作用域，
         * 并根据 [OutputConfig.catchConfig] 恢复上次进程遗留的崩溃应急记录。
         *
         * @param context Application 或能够获取 Application 的 Context。
         * @param outputConfig 日志输出、数据库写入、文件目录及崩溃捕获配置。
         * @throws IllegalArgumentException 当无法从 [context] 获取 Application 时抛出。
         *
         */
        fun init(context: Context, outputConfig: OutputConfig = OutputConfig()) {
            val application = (context.applicationContext as? Application) ?: (context as? Application) ?: throw IllegalArgumentException("LoggerX.init requires an Application context")
            ContextHolder.init(application)
            this.outputConfig = outputConfig
            val catchConfig = outputConfig.catchConfig
            LogOutputterManager.initDefaultOutputters(outputConfig)
            CrashCaptureManager.initialize(application, catchConfig)
            if(catchConfig.enabled){
                enableCatch(catchConfig)
            }
        }


        /**
         * 关闭 LoggerX 自动崩溃采集链路。
         *
         * 已保存的应急文件和历史 CATCH 日志不会被删除。
         */
        fun disableCatch() {
            CrashCaptureManager.disable()
        }


        /**
         * 立即扫描并归档待处理崩溃记录。
         *
         * 通常由 [init] 自动执行；该函数适用于外部崩溃回调写入应急文件后主动触发归档。
         * 同一崩溃事件会按 eventId 幂等写入 CATCH 表。
         *
         * @return 本次成功归档的崩溃事件数量；正在执行恢复时返回 0。
         */
        fun recoverCatchLogs(): Int = CrashCaptureManager.recover()

        /**
         * 注册 LoggerX 统一监听。
         *
         * setCrashListener 注册后会立即回调全部未上传的 CATCH 记录。
         * setLoggerXListener 在日志成功插入数据库后异步回调，默认不监听。
         * 再次调用会替换之前注册的监听器。
         *
         * @param setCrashListener CATCH 报告上传监听；返回 true 时对应记录会标记为已上传。
         * @param setLoggerXListener 日志成功写入数据库后的监听；在 IO 协程执行，默认 null。
         */
        fun setListener(
            setCrashListener: CrashListener,
            setLoggerXListener: LoggerXInsertListener? = null
        ) {
            CrashUploadDispatcher.setListener(setCrashListener)
            LoggerXListenerDispatcher.setLogInsertListener(setLoggerXListener)
        }

        /**
         * 请求上传指定 CATCH 日志。
         *
         * 请求会在后台执行，并回调 [setListener] 注册的崩溃监听。未注册监听、记录不存在或已经上传时不会重复上传。
         *
         * @param logId CATCH 表中的日志主键。
         */
        fun requestCatchUpload(logId: Int) {
            CrashUploadDispatcher.request(logId)
        }

        /**
         * 接收 Bugly 等外部崩溃组件捕获的异常信息。
         *
         * 崩溃现场仅同步写入轻量应急文件，不直接操作 SQLite；下次 [init] 或调用 [recoverCatchLogs]
         * 时会合并并写入 CATCH 表。未启用 CATCH 时忽略该请求。
         *
         * @param source 崩溃信息来源。
         * @param crashType 外部组件定义的崩溃类型编号，可为 null。
         * @param errorType 异常类名、Native signal 或外部错误类型。
         * @param errorMessage 异常摘要。
         * @param errorStack 外部组件提供的完整堆栈。
         * @param extras 附加诊断字段；建议仅传入可安全持久化的非敏感字符串。
         */
        fun reportExternalCrash(source: CrashSource, crashType: Int? = null, errorType: String? = null, errorMessage: String? = null, errorStack: String? = null, extras: Map<String, String> = emptyMap()) {
            CrashCaptureManager.reportExternalCrash(source, crashType, errorType, errorMessage, errorStack, extras)
        }

        /**
         * 主动记录一条已捕获的非致命异常到 CATCH 作用域。
         *
         * 该函数不会重新抛出 [throwable]，也不会终止当前进程。记录会先写入应急文件并立即尝试归档。
         *
         * @param throwable 需要记录的异常对象。
         * @param message 业务补充说明，默认使用异常消息。
         */
        fun recordException(throwable: Throwable, message: String = throwable.message.orEmpty()) {
            CrashCaptureManager.recordCaughtException(throwable, message)
        }

        /**
         * 在执行 Native/JNI 调用前保存当前 Java/Kotlin 调用链。
         *
         * [block] 正常返回或抛出 JVM 异常时会清除临时上下文；若进程在 Native 层直接崩溃，
         * 临时上下文会保留并在下次启动时与 Bugly或系统 tombstone 合并。
         *
         * @param block 需要跟踪的同步 Native/JNI 调用。
         * @return [block] 的返回值。
         * @throws Throwable 透明传播 [block] 抛出的异常。
         */
        fun <T> traceNativeCall(block: () -> T): T {
            val token = CrashCaptureManager.beginNativeCall()
            return try {
                block()
            } finally {
                CrashCaptureManager.endNativeCall(token)
            }
        }

        /**
         * 获取当前已注册的全部作用域标签。
         *
         * 返回顺序与预览页面页签顺序一致，但调用方不应依赖固定下标。
         *
         * @return 当前作用域标签快照。
         */
        fun getScopes(): List<String> {
            return LogOutputterManager.getScopes().toList()
        }

        /**
         * 获取当前已注册的全部日志输出器。
         *
         * 主要供 LoggerX 内置预览界面使用，业务代码通常应直接使用作用域代理。
         *
         * @return 当前输出器快照。
         */
        fun getOutputters(): List<LogOutputter> {
            return LogOutputterManager.getOutputters().toList()
        }


        /**
         * 清空所有作用域的数据库日志，并删除日志关联的持久化附件。
         *
         * @return 全部清理成功返回 true；任一数据库操作失败时返回 false。
         */
        fun clear(): Boolean {
            return LogDbManager.clearAllLogs()
        }

        /**
         * 导出并分享所有作用域的日志文件
         * @param exportAll 是否导出所有记录（true：不限制数量，false：按 limit 导出）
         * @param limit 每个作用域导出的条数限制（仅在 exportAll 为 false 时生效）
         * @param format 导出格式（CSV/TXT）
         * @param onProgress 总体导出进度回调 (0..100)
         * @return 无返回值；导出与分享在后台执行。
         */
        fun exportAndShareAll(exportAll: Boolean = true, limit: Int = 1000, format: LogExportManager.ExportFormat = LogExportManager.ExportFormat.CSV, onProgress: ((Int) -> Unit)? = null) {
            LogExportManager.exportAll(exportAll, limit, format, onProgress)
        }

        /**
         * 导出所有作用域的日志文件，并通过回调接收文件列表
         * @param exportAll 是否导出所有记录（true：不限制数量，false：按 limit 导出）
         * @param limit 每个作用域导出的条数限制（仅在 exportAll 为 false 时生效）
         * @param format 导出格式（CSV/TXT）
         * @param onProgress 总体导出进度回调 (0..100)
         * @param onFilesReady 文件列表就绪回调，接收导出的所有文件列表
         * @return 无返回值；文件生成后通过 [onFilesReady] 返回结果。
         */
        fun exportAllFiles(exportAll: Boolean = true, limit: Int = 1000, format: LogExportManager.ExportFormat = LogExportManager.ExportFormat.CSV, onProgress: ((Int) -> Unit)? = null, onFilesReady: ((List<File>) -> Unit)? = null) {
            LogExportManager.exportAllFiles(exportAll, limit, format, onProgress, onFilesReady)
        }

        /**
         * 获取 LoggerX SQLite 数据库文件当前大小。
         *
         * @return 数据库文件大小，单位 MB；数据库尚未创建时返回 0.0。
         */
        fun getDbFileSize(): Double {
            return LogDbManager.getDbFileSize()
        }

        /**
         * 开启数据库日志自动清理功能
         *
         * 清理任务按日期删除超过保留期限的日志及其附件。
         *
         * @param retentionDays 保留天数，超过该天数的数据将被清理
         */
        fun enableAutoClean(retentionDays: Int) {
            LogDbManager.startAutoCleanByDate(retentionDays)
        }

        /**
         * 开启数据库日志自动清理功能（按文件大小）
         *
         * 当数据库大于 [maxSizeMb] 时，从最旧日志开始清理，直至至少释放 [cleanSizeMb]。
         *
         * @param maxSizeMb 数据库文件最大允许大小 (MB)
         * @param cleanSizeMb 每次清理尝试减少的大小 (MB)
         */
        fun enableAutoClean(maxSizeMb: Double, cleanSizeMb: Double) {
            LogDbManager.startAutoCleanBySize(maxSizeMb, cleanSizeMb)
        }

        /**
         * 开启按设备可用存储空间自动清理日志。
         *
         * 当应用文件目录可用空间低于阈值时，按日期删除最旧日志及其附件。
         * 该功能检测的是磁盘空间，不是运行内存。
         *
         * @param minFreeSpaceMb 最小可用空间界限 (MB)，当可用空间低于该值时，自动清理最老一天的数据
         */
        fun enableAutoCleanByLowMemory(minFreeSpaceMb: Double = 100.0) {
            LogDbManager.startAutoCleanByLowMemory(minFreeSpaceMb)
        }


        /**
         * 启用或更新 CATCH 崩溃捕获。
         *
         * 启用后可捕获 JVM 未处理异常，并按配置补采系统退出信息及 Native 调用上下文。
         * 历史 CATCH 日志不受开关状态影响。
         *
         * @param config 崩溃捕获配置，默认启用全部受支持的捕获能力。
         * @throws IllegalStateException 当 LoggerX 尚未通过 [init] 初始化时抛出。
         */
        private fun enableCatch(config: CatchConfig) {
            CrashCaptureManager.enable(config)
        }
        /**
         * 查询当前进程是否已启用 CATCH 崩溃捕获。
         *
         * @return 已启用返回 true，否则返回 false。
         */
        private fun isCatchEnabled(): Boolean = CrashCaptureManager.isEnabled()

    }
}
