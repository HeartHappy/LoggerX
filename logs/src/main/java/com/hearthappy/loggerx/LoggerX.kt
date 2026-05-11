package com.hearthappy.loggerx

import android.app.Application
import android.content.Context
import com.hearthappy.loggerx.core.ContextHolder
import com.hearthappy.loggerx.core.LogExportManager
import com.hearthappy.loggerx.core.LogOutputter
import com.hearthappy.loggerx.core.LogOutputterManager
import com.hearthappy.loggerx.core.LogScope
import com.hearthappy.loggerx.core.LogScopeProxy
import com.hearthappy.loggerx.core.OutputConfig
import com.hearthappy.loggerx.db.LogDbManager
import com.hearthappy.loggerx.interceptor.LogInterceptor
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
        const val COLUMN_ID = "id"
        const val COLUMN_TIME = "time"
        const val COLUMN_LEVEL = "level"
        const val COLUMN_TAG = "tag"
        const val COLUMN_METHOD = "method"
        const val COLUMN_MESSAGE = "message"
        const val COLUMN_IS_IMAGE = "is_image" // virtual output column
        const val COLUMN_FILE_PATH = "file_path"

        // ========== 内置Scope（直接调用） ==========
        val COMMON = LogScopeProxy("Common")
        val IMPORTANT = LogScopeProxy("Important")
        val KERNEL = LogScopeProxy("Kernel")
        val ERROR = LogScopeProxy("Error")
        @Volatile
        internal var outputConfig: OutputConfig = OutputConfig()

        // ========== 自定义Scope（扩展用） ==========
        fun registerScope(logInterceptor: LogInterceptor, vararg scopes: LogScope) {
            for (scope in scopes) {
                LogOutputterManager.registerOutputter(scope.getTag(), LogOutputter(scope = scope, logInterceptor))
            }
        }

        fun createScope(customScope: String): LogScopeProxy {
            return LogOutputterManager.create(customScope)
        }

        /**
         * 全局初始化（Application中调用）
         */
        fun init(context: Context, outputConfig: OutputConfig = OutputConfig()) {
            ContextHolder.init(context as Application)
            this.outputConfig = outputConfig
            LogOutputterManager.initDefaultOutputters(outputConfig)
        }

        fun getScopes(): List<String> {
            return LogOutputterManager.getScopes().toList()
        }

        fun getOutputters(): List<LogOutputter> {
            return LogOutputterManager.getOutputters().toList()
        }


        /**
         * 清空所有日志（数据库）
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
         */
        fun exportAndShareAll(
            exportAll: Boolean = true,
            limit: Int = 1000,
            format: LogExportManager.ExportFormat = LogExportManager.ExportFormat.CSV,
            onProgress: ((Int) -> Unit)? = null
        ) {
            LogExportManager.exportAll(exportAll, limit, format, onProgress)
        }

        /**
         * 导出所有作用域的日志文件，并通过回调接收文件列表
         * @param exportAll 是否导出所有记录（true：不限制数量，false：按 limit 导出）
         * @param limit 每个作用域导出的条数限制（仅在 exportAll 为 false 时生效）
         * @param format 导出格式（CSV/TXT）
         * @param onProgress 总体导出进度回调 (0..100)
         * @param onFilesReady 文件列表就绪回调，接收导出的所有文件列表
         */
        fun exportAllFiles(
            exportAll: Boolean = true,
            limit: Int = 1000,
            format: LogExportManager.ExportFormat = LogExportManager.ExportFormat.CSV,
            onProgress: ((Int) -> Unit)? = null,
            onFilesReady: ((List<File>) -> Unit)? = null
        ) {
            LogExportManager.exportAllFiles(exportAll, limit, format, onProgress, onFilesReady)
        }

        fun getDbFileSize(): Double {
            return LogDbManager.getDbFileSize()
        }

        /**
         * 开启数据库日志自动清理功能
         * @param retentionDays 保留天数，超过该天数的数据将被清理
         */
        fun enableAutoClean(retentionDays: Int) {
            LogDbManager.startAutoCleanByDate(retentionDays)
        }

        /**
         * 开启数据库日志自动清理功能（按文件大小）
         * @param maxSizeMb 数据库文件最大允许大小 (MB)
         * @param cleanSizeMb 每次清理尝试减少的大小 (MB)
         */
        fun enableAutoClean(maxSizeMb: Double, cleanSizeMb: Double) {
            LogDbManager.startAutoCleanBySize(maxSizeMb, cleanSizeMb)
        }

        /**
         * 开启数据库日志自动清理功能（按设备可用存储空间/内存）
         * @param minFreeSpaceMb 最小可用空间界限 (MB)，当可用空间低于该值时，自动清理最老一天的数据
         */
        fun enableAutoCleanByLowMemory(minFreeSpaceMb: Double = 100.0) {
            LogDbManager.startAutoCleanByLowMemory(minFreeSpaceMb)
        }

    }
}
