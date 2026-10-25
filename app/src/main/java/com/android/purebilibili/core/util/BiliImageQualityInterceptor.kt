package com.android.purebilibili.core.util

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.allowRgb565
import coil3.memory.MemoryCache
import com.android.purebilibili.core.store.SettingsManager

private val OriginalBiliImage = Extras.Key<Boolean>(default = false)
private const val QUALITY_CACHE_EXTRA = "bilipai_image_quality_v1"
private const val QUALITY_DISK_SUFFIX = "|bilipai-image-quality-v1:"

/** 大图预览和原图保存不经过列表图片的压缩策略。 */
internal fun ImageRequest.Builder.originalBiliImage() = apply {
    extras[OriginalBiliImage] = true
}

/** 显示、预加载、取色共用同一质量及缓存身份；重复应用不会重复追加。 */
internal fun ImageRequest.withBiliImageQuality(): ImageRequest {
    if (extras[OriginalBiliImage] == true) return this
    val url = when (val value = data) {
        is String -> value
        is android.net.Uri -> value.toString()
        else -> return this
    }
    val saver = SettingsManager.isDataSaverActive(context)
    val resolved = BiliImageQualityPolicy.resolveUrl(url, saver)
    if (resolved == url && QUALITY_CACHE_EXTRA !in memoryCacheKeyExtras) return this
    val quality = if (saver) "10" else "100"
    if (resolved == url && memoryCacheKeyExtras[QUALITY_CACHE_EXTRA] == quality) return this
    return newBuilder()
        .data(resolved)
        .memoryCacheKeyExtra(QUALITY_CACHE_EXTRA, quality)
        .apply {
            this@withBiliImageQuality.diskCacheKey?.let { key ->
                diskCacheKey(key.substringBefore(QUALITY_DISK_SUFFIX) + QUALITY_DISK_SUFFIX + quality)
            }
            this@withBiliImageQuality.placeholderMemoryCacheKey?.let { key ->
                placeholderMemoryCacheKey(MemoryCache.Key(key.key, key.extras + (QUALITY_CACHE_EXTRA to quality)))
            }
            // 解码缓存也区分新旧颜色精度。
            allowRgb565(false)
        }
        .build()
}

internal class BiliImageQualityInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
        chain.withRequest(chain.request.withBiliImageQuality()).proceed()
}
