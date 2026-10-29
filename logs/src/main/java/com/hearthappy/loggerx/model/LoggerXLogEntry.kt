package com.hearthappy.loggerx.model

/**
 * 一条已经成功写入 LoggerX 数据库的日志。
 *
 * @property id 当前作用域日志表中的主键。
 * @property scope 日志所属作用域，例如 Common、Error 或 Catch。
 * @property time 日志入库时间；CATCH 日志使用实际崩溃时间。
 * @property level 日志等级，例如 DEBUG、INFO、WARN 或 ERROR。
 * @property tag 产生日志的类标签；CATCH 日志中表示崩溃来源。
 * @property method 产生日志的方法；CATCH 日志中表示异常类型或 signal。
 * @property message 日志正文或崩溃摘要。
 * @property filePath 关联附件的绝对路径；普通文本日志为空字符串。
 * @property fileMime 关联附件 MIME 类型；无附件时为空字符串。
 * @property eventId CATCH 崩溃事件唯一标识；非 CATCH 日志为空字符串。
 */
data class LoggerXLogEntry(
    val id: Int,
    val scope: String,
    val time: String,
    val level: String,
    val tag: String,
    val method: String,
    val message: String,
    val filePath: String,
    val fileMime: String,
    val eventId: String
)
