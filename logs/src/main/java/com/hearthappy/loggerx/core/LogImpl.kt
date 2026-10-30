package com.hearthappy.loggerx.core

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.db.LogDbManager
import com.hearthappy.loggerx.interceptor.LogInterceptor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.transform.OutputKeys
import javax.xml.transform.Source
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

internal class LogImpl(private var scope: LogScope, private val interceptor: LogInterceptor): ILog {

    override fun d(message: String, vararg args: Any?) {
        log(Log.DEBUG, null, message, args)
    }

    override fun d(obj: Any?) {
        log(Log.DEBUG, null, Utils.toString(obj))
    }

    override fun e(message: String, vararg args: Any?) {
        e(null, message, *args)
    }

    override fun e(throwable: Throwable?, message: String, vararg args: Any?) {
        log(Log.ERROR, throwable, message, args)
    }

    override fun w(message: String, vararg args: Any?) {
        log(Log.WARN, null, message, args)
    }

    override fun i(message: String, vararg args: Any?) {
        log(Log.INFO, null, message, args)
    }

    override fun v(message: String, vararg args: Any?) {
        log(Log.VERBOSE, null, message, args)
    }

    override fun wtf(message: String, vararg args: Any?) {
        log(Log.ASSERT, null, message, args)
    }

    override fun json(json: String?) {
        json?.let { j ->
            try {
                val result = j.trim { it <= ' ' }
                if (result.startsWith("{")) {
                    val jsonObject = JSONObject(result)
                    d(unescapeSolidus(jsonObject.toString(JSON_INDENT)))
                    return
                }
                if (result.startsWith("[")) {
                    val jsonArray = JSONArray(result)
                    d(unescapeSolidus(jsonArray.toString(JSON_INDENT)))
                    return
                }
                e("Invalid Json")
            } catch (e: JSONException) {
                e("Invalid Json:${e.message}")
            }
        } ?: d("Empty/Null json content")
    }

    override fun xml(xml: String?) {
        xml?.let {
            try {
                val xmlInput: Source = StreamSource(StringReader(it))
                val xmlOutput = StreamResult(StringWriter())
                val factory = TransformerFactory.newInstance()
                runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                // Android SDK 的 XMLConstants 没有这两个 API 24 字段，用属性名字符串，旧版本解析器不支持时直接跳过。
                runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
                val transformer = factory.newTransformer()
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                transformer.transform(xmlInput, xmlOutput)
                d(xmlOutput.writer.toString().replaceFirst(">".toRegex(), ">\n"))
            } catch (e: TransformerException) {
                e("Invalid xml:${e.message}")
            }
        } ?: d("Empty/Null xml content")
    }

    @Synchronized override fun log(level: Int, message: String?, throwable: Throwable?) {
        val msg = message ?: return
        val stackTraceInfo = LogContextCollector.getStackTraceInfo()
        val logLevel = Utils.logLevel(level)
        val tag: String = formatTag(stackTraceInfo.className)
        val methodName = LogFormatter.format(stackTraceInfo)
        val logMsg = throwable?.run { "$msg : $NEW_LINE" + Utils.getStackTraceString(this) } ?: msg
        if (interceptor.isDebug()) {
            when (level) {
                Log.VERBOSE -> Log.v(tag, msg)
                Log.DEBUG -> Log.d(tag, msg)
                Log.INFO -> Log.i(tag, msg)
                Log.WARN -> Log.w(tag, msg)
                Log.ERROR -> Log.e(tag, msg)
                Log.ASSERT -> Log.wtf(tag, msg)
            }
        }
        if (interceptor.isWriteDatabase()) {
            LogDbManager.insertLog(scopeTag = scope.getTag(), level = logLevel, classTag = tag, method = methodName, message = logMsg)
        }
    }


    @Synchronized
    private fun log(priority: Int, throwable: Throwable?, msg: String, vararg args: Any) {
        val message = createMessage(msg, *args)
        log(priority, message, throwable)
    }

    /**
     * org.json 会把 `/` 转义成 `\/`。JSON 里这组字符只表示斜杠，还原后 URL 保持 `https://`。
     * 内容里真正的反斜杠仍是 `\\`，不会被这次替换吃掉。
     */
    private fun unescapeSolidus(json: String): String = json.replace("\\/", "/")

    private fun formatTag(tag: String?): String {
        return tag?.run { this } ?: LoggerX.TAG
    }

    private fun createMessage(message: String, vararg args: Any): String {
        return if (args.isEmpty()) message else String.format(message, *args)
    }

    companion object {
        private const val JSON_INDENT = 2
        private val NEW_LINE = System.lineSeparator()

    }
}
