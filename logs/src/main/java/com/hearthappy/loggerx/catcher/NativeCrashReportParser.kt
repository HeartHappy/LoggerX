package com.hearthappy.loggerx.catcher

internal object NativeCrashReportParser {
    fun parse(fileName: String, text: String, fallbackTime: Long): CrashRecord? {
        val fields = linkedMapOf<String, String>()
        val stack = StringBuilder()
        var inStack = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (!inStack && line == "backtrace:") {
                inStack = true
                continue
            }
            if (inStack) {
                if (stack.isNotEmpty()) stack.append('\n')
                stack.append(line)
                continue
            }
            val separator = line.indexOf('=')
            if (separator > 0) fields[line.substring(0, separator)] = line.substring(separator + 1)
        }
        val signalName = fields["signal_name"].orEmpty()
        val stackText = stack.toString().trim()
        if (signalName.isBlank() && stackText.isBlank()) return null
        val occurredAt = fields["time_ms"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: fallbackTime.takeIf { it > 0L }
            ?: return null
        val fault = fields["fault"].orEmpty()
        return CrashRecord(
            source = CrashSource.SYSTEM_NATIVE,
            kind = CrashKind.NATIVE,
            occurredAt = occurredAt,
            sessionId = "",
            processName = fields["process"].orEmpty(),
            threadName = fields["thread"].orEmpty().ifBlank { fields["tid"].orEmpty() },
            errorType = signalName.ifBlank { "SIGNAL" },
            errorMessage = if (fault.isNotBlank()) "fault addr $fault" else signalName,
            stackTrace = stackText,
            details = buildString {
                appendLine("native_signal_handler=1")
                appendLine("file=$fileName")
                appendLine("signal=${fields["signal"].orEmpty()}")
                append("code=${fields["code"].orEmpty()}")
            }
        )
    }
}
