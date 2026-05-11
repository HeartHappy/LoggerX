package com.hearthappy.loggerx.db

data class QueryPageResult(
    val rows: List<Map<String, Any>>,
    val totalCount: Int,
    val page: Int,
    val limit: Int,
    val nextPage: Int?,
    val approxBytes: Int,
    val hasMore: Boolean,
    val queryPlan: List<String>,
    val nextAnchorTime: String? = null,
    val nextAnchorId: Int? = null
)
