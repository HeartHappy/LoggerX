package com.hearthappy.loggerx.core

import android.util.Base64
import android.util.Log
import com.hearthappy.loggerx.LoggerX
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

internal object FileLogStorageManager {
    private const val DEFAULT_ROOT_DIR = "loggerx"
    private const val STORED_FILES_DIR = "files"
    private const val EXPORT_FILES_DIR = "exports"
    private const val PATH_LENGTH_LIMIT = 512

    data class ManagedFile(
        val file: File,
        val mimeType: String
    )

    fun validateSource(path: String?): File {
        val trimmed = path?.trim()
        if (trimmed.isNullOrEmpty()) {
            throw FileLogWriteException("文件路径不能为空")
        }
        if (trimmed.contains('\u0000')) {
            throw FileLogWriteException("文件路径格式不合法")
        }
        return validateSource(File(trimmed))
    }

    fun validateSource(file: File?): File {
        if (file == null) {
            throw FileLogWriteException("文件不能为空")
        }
        val absoluteFile = runCatching { file.absoluteFile }.getOrElse {
            throw FileLogWriteException("文件路径格式不合法", it)
        }
        if (absoluteFile.absolutePath.length > PATH_LENGTH_LIMIT) {
            throw FileLogWriteException("文件路径过长，请缩短后重试")
        }
        if (!absoluteFile.exists()) {
            throw FileLogWriteException("文件不存在: ${absoluteFile.absolutePath}")
        }
        if (!absoluteFile.isFile) {
            throw FileLogWriteException("仅支持写入普通文件")
        }
        if (absoluteFile.length() <= 0L) {
            throw FileLogWriteException("文件内容为空，无法写入日志")
        }
        if (!absoluteFile.canRead()) {
            throw FileLogWriteException("没有文件读取权限: ${absoluteFile.absolutePath}")
        }
        return absoluteFile
    }

    fun persistSource(scopeTag: String, sourceFile: File): ManagedFile {
        val validFile = validateSource(sourceFile)
        val targetDir = ensureScopeDir(scopeTag)
        val extension = validFile.extension.takeIf { it.isNotBlank() }?.lowercase(Locale.US)
        val targetName = buildString {
            append(System.currentTimeMillis())
            append("_")
            append(UUID.randomUUID().toString().replace("-", ""))
            if (!extension.isNullOrBlank()) {
                append(".")
                append(extension)
            }
        }
        val targetFile = File(targetDir, targetName)
        if (targetFile.absolutePath.length > PATH_LENGTH_LIMIT) {
            throw FileLogWriteException("目标文件路径过长，请调整存储目录配置")
        }
        try {
            FileInputStream(validFile).channel.use { input ->
                FileOutputStream(targetFile).channel.use { output ->
                    output.transferFrom(input, 0, input.size())
                    output.force(true)
                }
            }
        } catch (security: SecurityException) {
            targetFile.delete()
            Log.e(LoggerX.TAG, "persistSource permission denied: ${security.message}")
            throw FileLogWriteException("没有权限写入日志文件目录", security)
        } catch (io: IOException) {
            targetFile.delete()
            val userMessage = when {
                io.message.orEmpty().contains("ENOSPC", ignoreCase = true) -> "磁盘空间不足，无法保存日志文件"
                io.message.orEmpty().contains("too long", ignoreCase = true) -> "目标路径过长，无法保存日志文件"
                else -> "保存日志文件失败，请稍后重试"
            }
            Log.e(LoggerX.TAG, "persistSource failed: ${io.message}")
            throw FileLogWriteException(userMessage, io)
        }
        if (!targetFile.exists() || targetFile.length() <= 0L) {
            targetFile.delete()
            throw FileLogWriteException("保存日志文件失败，请稍后重试")
        }
        return ManagedFile(targetFile, detectMimeType(targetFile))
    }

    fun resolveStoredFile(filePath: String?): File? {
        if (filePath.isNullOrBlank()) return null
        return runCatching { File(filePath).absoluteFile }.getOrNull()
    }

    fun validateStoredFile(filePath: String?): File {
        val file = resolveStoredFile(filePath) ?: throw FileLogWriteException("文件路径为空")
        if (file.absolutePath.length > PATH_LENGTH_LIMIT) {
            throw FileLogWriteException("文件路径过长，无法读取")
        }
        if (!file.exists()) {
            throw FileLogWriteException("文件不存在: ${file.absolutePath}")
        }
        if (!file.isFile) {
            throw FileLogWriteException("目标路径不是有效文件")
        }
        if (!file.canRead()) {
            throw FileLogWriteException("没有文件读取权限: ${file.absolutePath}")
        }
        if (file.length() <= 0L) {
            throw FileLogWriteException("文件内容为空，无法读取")
        }
        return file
    }

    fun resolveExportDir(): File {
        return ensureDirectory(File(resolveStorageRoot(), EXPORT_FILES_DIR))
    }

    fun resolveStorageRoot(): File {
        val configuredPath = LoggerX.outputConfig.storageDirPath?.trim().orEmpty()
        val root = if (configuredPath.isNotBlank()) {
            val configuredDir = File(configuredPath)
            if (!configuredDir.isAbsolute) {
                throw FileLogWriteException("storageDirPath 必须为绝对路径")
            }
            configuredDir
        } else {
            File(ContextHolder.getAppContext().filesDir, DEFAULT_ROOT_DIR)
        }
        return ensureDirectory(root)
    }

    fun encodeAsDataUri(filePath: String?): String {
        val file = validateStoredFile(filePath)
        val mimeType = detectMimeType(file)
        val body = FileInputStream(file).use { stream ->
            Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
        }
        return "data:$mimeType;base64,$body"
    }

    fun detectMimeType(file: File): String {
        return when (file.extension.lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "txt", "log", "tombstone" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    private fun ensureScopeDir(scopeTag: String): File {
        val sanitizedScope = scopeTag.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return ensureDirectory(File(File(resolveStorageRoot(), STORED_FILES_DIR), sanitizedScope))
    }

    private fun ensureDirectory(dir: File): File {
        try {
            if (dir.exists() && !dir.isDirectory) {
                throw FileLogWriteException("存储目录无效: ${dir.absolutePath}")
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw FileLogWriteException("创建存储目录失败: ${dir.absolutePath}")
            }
            val probeFile = File(dir, ".rw_probe_${System.nanoTime()}")
            FileOutputStream(probeFile).use { }
            if (!probeFile.delete()) {
                probeFile.deleteOnExit()
            }
        } catch (security: SecurityException) {
            Log.e(LoggerX.TAG, "ensureDirectory permission denied: ${security.message}")
            throw FileLogWriteException("没有权限访问日志文件目录", security)
        } catch (io: IOException) {
            Log.e(LoggerX.TAG, "ensureDirectory failed: ${io.message}")
            val userMessage = when {
                io.message.orEmpty().contains("ENOSPC", ignoreCase = true) -> "磁盘空间不足，无法创建日志目录"
                io.message.orEmpty().contains("too long", ignoreCase = true) -> "目录路径过长，无法创建日志目录"
                else -> "日志目录不可用，请检查存储配置"
            }
            throw FileLogWriteException(userMessage, io)
        }
        return dir
    }
}
