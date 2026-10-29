package com.hearthappy.loggerx.image

import android.widget.ImageView

interface ILogImageLoader {
    fun loadThumbnail(imageView: ImageView, path: String)

    fun loadOriginal(imageView: ImageView, path: String)
}

/**
 * 图片加载器诊断统计快照。
 *
 * @property memoryHits 内存缓存命中次数。
 * @property diskHits 磁盘缓存命中次数。
 * @property decodeRequests 图片解码请求总数。
 * @property decodeSuccess 图片解码成功次数。
 * @property decodeFailure 图片解码失败次数。
 * @property averageDecodeMs 平均单次解码耗时，单位毫秒。
 * @property peakDecodedBytes 单张图片解码后占用内存的峰值字节数。
 */
data class LogImageLoadStatsSnapshot(
    val memoryHits: Long,
    val diskHits: Long,
    val decodeRequests: Long,
    val decodeSuccess: Long,
    val decodeFailure: Long,
    val averageDecodeMs: Double,
    val peakDecodedBytes: Long
)

interface ILogImageLoaderDiagnostics {
    fun snapshot(): LogImageLoadStatsSnapshot
}
