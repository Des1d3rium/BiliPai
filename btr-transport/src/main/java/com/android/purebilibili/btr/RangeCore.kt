package com.android.purebilibili.btr

import java.net.URI

/** One sub-range of a request; [end] is inclusive, as in an HTTP Range header. */
data class BtrPiece(val index: Int, val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

data class BtrContentRange(val start: Long, val end: Long, val total: Long?) {
    val length: Long get() = end - start + 1
}

private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
private val MEDIA_SUFFIX = Regex("\\.(?:m4s|mp4|flv)$", RegexOption.IGNORE_CASE)
private val MEDIA_HOST = Regex(
    "(?:^|\\.)(?:bilivideo\\.(?:com|cn|net)|akamaized\\.net|szbdyd\\.com|hdslb\\.com|xycdn\\.com|" +
        "mountaintoys\\.cn|nexusedgeio\\.com|ahdohpiechei\\.com)$",
    RegexOption.IGNORE_CASE
)
private val HOST_NAME = Regex("^[a-z\\d](?:[a-z\\d-]*[a-z\\d])?(?:\\.[a-z\\d](?:[a-z\\d-]*[a-z\\d])?)+$")

fun parseBtrContentRange(value: String?): BtrContentRange? {
    val match = CONTENT_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.let { it.toLongOrNull() ?: return null }
    if (total != null && total <= end) return null
    return BtrContentRange(start, end, total)
}

/** BTR splitRange: at most [concurrency] pieces, none below the minimum chunk. */
fun splitBtrRange(start: Long, end: Long, concurrency: Int, minChunkBytes: Long = 128L * 1024): List<BtrPiece> {
    val length = end - start + 1
    if (start < 0 || length <= 0) return emptyList()
    val limit = concurrency.coerceIn(1, 512)
    val minimum = maxOf(32L * 1024, minChunkBytes)
    val count = maxOf(1L, minOf(limit.toLong(), (length + minimum - 1) / minimum)).toInt()
    val base = length / count
    val remainder = length % count
    var cursor = start
    return List(count) { index ->
        val size = base + if (index < remainder) 1 else 0
        BtrPiece(index, cursor, cursor + size - 1).also { cursor += size }
    }
}

internal fun hostOf(url: String): String =
    runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

/** The signed address without its node: the same address can be asked of any node. */
internal fun addressOf(url: String): String = runCatching {
    val uri = URI(url)
    uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
}.getOrDefault("")

fun isBilibiliMediaUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && MEDIA_SUFFIX.containsMatchIn(uri.rawPath.orEmpty()) &&
        MEDIA_HOST.containsMatchIn(uri.host.orEmpty())
}.getOrDefault(false)

/** A server added by hand: only its host name is kept, and only for Bilibili's video servers. */
fun normalizeBtrCdnHost(value: String): String {
    val text = value.trim().lowercase()
    if (text.isEmpty() || text.length > 253) return ""
    val host = runCatching {
        URI(if (Regex("^[a-z][a-z\\d+.-]*://").containsMatchIn(text)) text else "https://$text").host
    }.getOrNull()?.lowercase() ?: return ""
    return if (HOST_NAME.matches(host) && MEDIA_HOST.containsMatchIn(host)) host else ""
}

enum class BtrCdnMode { MAINLAND, OVERSEAS, CUSTOM }

/** BTR normalizeSettings. Timeouts and the hedge delay are the original's fixed values. */
data class BtrSettings(
    val enabled: Boolean = true,
    val mode: BtrCdnMode = BtrCdnMode.MAINLAND,
    val customHosts: List<String> = emptyList(),
    val concurrency: Int = 8,
    val autoConcurrency: Boolean = false,
    /** Ask the host player for a deeper buffer, so a node that stalls for seconds is not a stall. */
    val stableBuffer: Boolean = false,
    val minChunkBytes: Long = 64L * 1024,
    val firstByteTimeoutMs: Long = 5_500,
    val stallTimeoutMs: Long = 4_000,
    val attemptTimeoutMs: Long = 15_000,
    val hedgeDelayMs: Long = 900
) {
    fun normalized(): BtrSettings = copy(
        concurrency = concurrency.takeIf { it in ALLOWED_CONCURRENCY } ?: 8,
        customHosts = customHosts.map(::normalizeBtrCdnHost).filter { it.isNotEmpty() }.distinct().take(32)
    )

    companion object {
        val ALLOWED_CONCURRENCY = listOf(4, 8, 16, 32, 64, 128)
    }
}
