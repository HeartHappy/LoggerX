package com.hearthappy.loggerx.db

/**
 * 日志分页查询结果。
 *
 * @property rows 当前页日志数据。
 * @property totalCount 符合筛选条件的总记录数。
 * @property page 当前页码，从 1 开始。
 * @property limit 请求的每页最大记录数。
 * @property nextPage 页码分页模式下的下一页页码；没有更多数据时为 null。
 * @property approxBytes 当前页数据的近似内存字节数。
 * @property hasMore 是否仍有更多数据可加载。
 * @property queryPlan 查询计划诊断信息；未启用诊断时为空列表。
 * @property nextAnchorTime 锚点分页时下一次请求使用的末条时间。
 * @property nextAnchorId 锚点分页时下一次请求使用的末条主键。
 */
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
