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

    data class QueryProgress(
        val queryId: String,
        val percent: Int,
        val stage: String,
        val cancelable: Boolean = true,
        val fromCache: Boolean = false
    )

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
            cache.put(cacheKey, pageResult)
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
