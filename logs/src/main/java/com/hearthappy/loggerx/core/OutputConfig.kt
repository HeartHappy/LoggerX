package com.hearthappy.loggerx.core


/**
 * Created Date: 2026/3/20
 * @author ChenRui
 * ClassDescription：输出配置
 *
 */
data class OutputConfig(
    var isLog: Boolean = true,
    var isWriteDatabase: Boolean = true,
    var storageDirPath: String? = null, //文件存储目录
    var catchConfig: CatchConfig = CatchConfig()
)

data class CatchConfig(
    val enabled: Boolean = false,
    val captureJvmCrashes: Boolean = true,
    val captureSystemExitInfo: Boolean = true,
    val captureNativeCallContext: Boolean = true,
    val maxReportBytes: Int = 1024 * 1024,
    val maxPendingReports: Int = 32
)
