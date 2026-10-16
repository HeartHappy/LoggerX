package com.hearthappy.loggerx.preview

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Highlights Java/Kotlin call sites inside CATCH messages so package-qualified frames stand out
 * from native stacks and report metadata.
 */
internal object CatchCallChainHighlighter {
    private val CALL_CHAIN = Regex(
        """(?<![.\w$])(?:[A-Za-z_][\w$]*\.){2,}[A-Za-z_][\w$]*(?=\()|(?<![.\w$])(?:[A-Za-z_]\w*\.){2,}[A-Z][\w$]*"""
    )

    fun findRanges(message: String): List<IntRange> {
        if (message.isEmpty()) return emptyList()
        return CALL_CHAIN.findAll(message).map { it.range }.toList()
    }

    fun highlight(message: String, color: Int): CharSequence {
        val ranges = findRanges(message)
        if (ranges.isEmpty()) return message
        return SpannableString(message).apply {
            for (range in ranges) {
                val end = range.last + 1
                setSpan(ForegroundColorSpan(color), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
