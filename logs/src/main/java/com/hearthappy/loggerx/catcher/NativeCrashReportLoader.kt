package com.hearthappy.loggerx.catcher

import java.io.File

internal object NativeCrashReportLoader {
    private const val MAX_BYTES = 256 * 1024

    fun load(directory: File): List<CrashReportImporter.Input> {
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("n_") && it.name.endsWith(".txt") }
        val inputs = mutableListOf<CrashReportImporter.Input>()
        for (file in files) {
            val text = runCatching { readLimited(file) }.getOrNull()
            val record = text?.let { NativeCrashReportParser.parse(file.name, it, file.lastModified()) }
            if (record == null) {
                file.delete()
                continue
            }
            inputs += CrashReportImporter.Input(record, file)
        }
        return inputs
    }

    private fun readLimited(file: File): String {
        file.inputStream().use { input ->
            val buffer = ByteArray(MAX_BYTES)
            val count = input.read(buffer)
            if (count <= 0) return ""
            return String(buffer, 0, count, Charsets.UTF_8)
        }
    }
}
