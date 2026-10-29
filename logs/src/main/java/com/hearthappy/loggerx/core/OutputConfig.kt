package com.hearthappy.loggerx.core


/**
 * LoggerX 全局输出配置。
 *
 * @property isLog 是否输出到 Android Logcat，默认开启。
 * @property isWriteDatabase 是否将日志写入 LoggerX 数据库，默认开启。
 * @property storageDirPath 文件日志及导出文件的根目录绝对路径；为 null 时使用应用内部默认目录。
 * @property catchConfig 崩溃捕获配置。
 */
data class OutputConfig(
    var isLog: Boolean = true,
    var isWriteDatabase: Boolean = true,
    var storageDirPath: String? = null,
    var catchConfig: CatchConfig = CatchConfig()
)

/**
 * CATCH 崩溃捕获配置。
 *
 * @property enabled 是否启用崩溃捕获；关闭后不会产生新的崩溃记录，但历史 CATCH 日志仍可查询。
 * @property captureJvmCrashes 是否捕获 Java/Kotlin 未处理异常，并在写入应急文件后继续转交原异常处理器。
 * @property captureSystemExitInfo 是否在 Android 11 及以上通过 ApplicationExitInfo 补采系统崩溃、ANR 与 tombstone 信息。
 * @property captureNativeCallContext 是否允许 traceNativeCall 在进入 JNI 前保存 Java/Kotlin 调用链。
 * @property maxReportBytes 单个应急记录或完整崩溃报告允许写入的最大字节数；有效范围为 64 KiB 到 4 MiB。
 * @property maxPendingReports 最多保留的待归档应急记录数量；有效范围为 4 到 128。
 */
data class CatchConfig(
    val enabled: Boolean = false,
    val captureJvmCrashes: Boolean = true,
    val captureSystemExitInfo: Boolean = true,
    val captureNativeCallContext: Boolean = true,
    val maxReportBytes: Int = 1024 * 1024,
    val maxPendingReports: Int = 32
)
