package com.android.purebilibili.btr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP range request. Implementations deliver bytes in order and validate the response. */
interface BtrRangeFetcher {
    /**
     * Requests bytes [start]..[end] of [url]. [onHeaders] runs once a verified 206 has arrived,
     * [onChunk] for every block of the body. Returns the file's total length when known.
     */
    suspend fun fetch(
        url: String,
        start: Long,
        end: Long,
        onHeaders: () -> Unit,
        onChunk: (ByteArray) -> Unit
    ): Long?
}

/** OkHttp transport: shared connection pool, redirects off, Bilibili's referer, no compression. */
class OkHttpBtrRangeFetcher(
    client: OkHttpClient,
    private val headers: Map<String, String> = DEFAULT_HEADERS
) : BtrRangeFetcher {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // BTR's own first-byte, stall and total timers decide; OkHttp only bounds the socket.
        .readTimeout(java.time.Duration.ofSeconds(20))
        .build()

    override suspend fun fetch(
        url: String,
        start: Long,
        end: Long,
        onHeaders: () -> Unit,
        onChunk: (ByteArray) -> Unit
    ): Long? {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        // A blocking body read is unblocked by canceling the call, not by the coroutine.
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.await().use { response ->
                val range = parseBtrContentRange(response.header("Content-Range"))
                if (response.code != 206 || range == null || range.start != start || range.end != end) {
                    throw BtrTransferException("Range 校验失败：HTTP ${response.code}", status = response.code)
                }
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw BtrTransferException("CDN 返回了压缩的分块")
                }
                onHeaders()
                val body = response.body
                val source = body.source()
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                val expected = end - start + 1
                while (received < expected) {
                    val count = withContext(Dispatchers.IO) {
                        source.read(buffer, 0, minOf(buffer.size.toLong(), expected - received).toInt())
                    }
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    received += count
                    onChunk(buffer.copyOf(count))
                }
                if (received != expected) throw BtrTransferException("子块长度不符：$received/$expected")
                return range.total
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                else response.close()
            }
        })
    }

    companion object {
        val DEFAULT_HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0"
        )
    }
}
