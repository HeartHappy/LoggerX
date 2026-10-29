package com.hearthappy.loggerx.core

import android.util.Log
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.db.LogDbManager
import com.hearthappy.loggerx.db.QueryPageResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object DataQueryService {
    /**
     * 异步日志查询参数。
     *
     * @property time 日期或时间前缀筛选条件，为 null 时不限时间。
     * @property tag 日志标签精确筛选条件，为 null 时不限标签。
     * @property level 日志等级精确筛选条件，为 null 时不限等级。
     * @property method 方法名模糊筛选条件，为 null 时不限方法。
     * @property isImage true 仅查询图片日志，false 排除图片日志，null 不限制。
     * @property keyword 日志正文模糊搜索关键字，为 null 时不搜索。
     * @property sortAsc 是否按时间与主键升序排列；默认倒序显示最新日志。
     * @property page 页码，从 1 开始；未使用锚点分页时生效。
     * @property pageSize 每页最大记录数。
     * @property anchorTime 锚点分页的上一页末条时间，为 null 时使用页码分页。
     * @property anchorId 锚点分页的上一页末条主键，需与 anchorTime 同时提供。
     * @property includeImagePayload 预留的图片内容加载开关；当前查询结果仅返回文件路径。
     */
    data class QueryRequest(
        val time: String? = null,
        val tag: String? = null,
        val level: String? = null,
        val method: String? = null,
        val isImage: Boolean? = null,
        val keyword: String? = null,
        val sortAsc: Boolean = false,
        val page: Int = 1,
        val pageSize: Int = 100,
        val anchorTime: String? = null,
        val anchorId: Int? = null,
        val includeImagePayload: Boolean = false
    )

    /**
     * 异步查询进度。
     *
     * @property queryId 查询任务唯一标识。
     * @property percent 当前进度百分比，范围为 0 到 100。
     * @property stage 当前执行阶段名称。
     * @property cancelable 当前任务是否允许取消。
     * @property fromCache 进度对应的结果是否来自内存缓存。
     */
    data class QueryProgress(
        val queryId: String,
        val percent: Int,
        val stage: String,
        val cancelable: Boolean = true,
        val fromCache: Boolean = false
    )

    /**
     * 异步查询成功结果。
     *
     * @property queryId 查询任务唯一标识。
     * @property pageResult 分页数据与下一页锚点信息。
     * @property fromCache 结果是否来自内存缓存。
     */
    data class QueryResult(
        val queryId: String,
        val pageResult: QueryPageResult,
        val fromCache: Boolean
    )

    interface QueryListener {
        fun onProgress(progress: QueryProgress) {}
        fun onSuccess(result: QueryResult) {}
        fun onError(queryId: String, throwable: Throwable) {}
        fun onCancelled(queryId: String) {}
    }

    class QueryHandle internal constructor(
        val queryId: String,
        private val cancelled: AtomicBoolean,
        private val backgroundContinue: AtomicBoolean
    ) {
        fun cancel() {
            cancelled.set(true)
        }

        fun setBackgroundContinue(enabled: Boolean) {
            backgroundContinue.set(enabled)
        }
    }

    private data class Session(
        val queryId: String,
        val scopeTag: String,
        val request: QueryRequest,
        val listeners: CopyOnWriteArrayList<QueryListener>,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val backgroundContinue: AtomicBoolean = AtomicBoolean(true),
        @Volatile var lastProgress: QueryProgress? = null,
        @Volatile var lastResult: QueryResult? = null
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val cache = LruTtlCache<String, QueryPageResult>(64, 60_000L)

    fun invalidateCache() {
        cache.clear()
    }

    fun queryAsync(scopeTag: String, request: QueryRequest, listener: QueryListener): QueryHandle {
        val queryId = UUID.randomUUID().toString()
        val session = Session(
            queryId = queryId,
            scopeTag = scopeTag,
            request = request,
            listeners = CopyOnWriteArrayList<QueryListener>().apply { add(listener) }
        )
        sessions[queryId] = session
        val handle = QueryHandle(queryId, session.cancelled, session.backgroundContinue)
        emitProgress(session, 0, "queued")
        AsyncTaskExecutor.execute {
            runQuery(session)
        }
        return handle
    }

    fun subscribe(queryId: String, listener: QueryListener): Boolean {
        val session = sessions[queryId] ?: return false
        session.listeners += listener
        session.lastProgress?.let(listener::onProgress)
        session.lastResult?.let(listener::onSuccess)
        return true
    }

    fun unsubscribe(queryId: String, listener: QueryListener) {
        sessions[queryId]?.listeners?.remove(listener)
    }

    private fun runQuery(session: Session) {
        val cacheKey = buildCacheKey(session.scopeTag, session.request)
        try {
            emitProgress(session, 5, "checking-cache")
            if (session.cancelled.get()) {
                notifyCancelled(session)
                return
            }
            val cached = cache.get(cacheKey)
            if (cached != null) {
                emitProgress(session, 100, "done", fromCache = true)
                val result = QueryResult(session.queryId, cached, true)
                session.lastResult = result
                session.listeners.forEach { it.onSuccess(result) }
                cleanupIfNeeded(session)
                return
            }
            emitProgress(session, 20, "planning")
            if (session.cancelled.get()) {
                notifyCancelled(session)
                return
            }
            emitProgress(session, 45, "querying")
            val pageResult = LogDbManager.queryLogsPageAdvanced(
                scopeTag = session.scopeTag,
                time = session.request.time,
                tag = session.request.tag,
                level = session.request.level,
                method = session.request.method,
                isImage = session.request.isImage,
                keyword = session.request.keyword,
                isAsc = session.request.sortAsc,
                page = session.request.page,
                limit = session.request.pageSize,
                anchorTime = session.request.anchorTime,
                anchorId = session.request.anchorId)
            if (session.cancelled.get()) {
                notifyCancelled(session)
                return
            }
            emitProgress(session, 85, "building-result")
            if (pageResult.totalCount > 0 || pageResult.rows.isNotEmpty()) {
                cache.put(cacheKey, pageResult)
            }
            emitProgress(session, 100, "done")
            val result = QueryResult(session.queryId, pageResult, false)
            session.lastResult = result
            session.listeners.forEach { it.onSuccess(result) }
        } catch (throwable: Throwable) {
            Log.e(LoggerX.TAG, "DataQueryService failed: ${throwable.message}")
            session.listeners.forEach { it.onError(session.queryId, throwable) }
        } finally {
            cleanupIfNeeded(session)
        }
    }

    private fun emitProgress(session: Session, percent: Int, stage: String, fromCache: Boolean = false) {
        val normalized = (percent / 5) * 5
        val progress = QueryProgress(
            queryId = session.queryId,
            percent = normalized.coerceIn(0, 100),
            stage = stage,
            fromCache = fromCache
        )
        session.lastProgress = progress
        session.listeners.forEach { it.onProgress(progress) }
    }

    private fun notifyCancelled(session: Session) {
        session.listeners.forEach { it.onCancelled(session.queryId) }
        cleanupIfNeeded(session)
    }

    private fun cleanupIfNeeded(session: Session) {
        if (!session.backgroundContinue.get()) {
            sessions.remove(session.queryId)
        }
    }

    private fun buildCacheKey(scopeTag: String, request: QueryRequest): String {
        return listOf(
            scopeTag,
            request.time,
            request.tag,
            request.level,
            request.method,
            request.isImage?.toString(),
            request.keyword,
            request.sortAsc.toString(),
            request.page.toString(),
            request.pageSize.toString(),
            request.anchorTime,
            request.anchorId?.toString(),
            request.includeImagePayload.toString()
        ).joinToString("|")
    }
}
