package com.android.purebilibili.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Media3 boundary for the BTR downloader. A GET of a registered track is downloaded by BTR and
 * handed over in order: a bounded range (a DASH segment) as one range, an open-ended read (the
 * host's progressive path) as consecutive windows, at most [LOOKAHEAD_WINDOWS] ahead of the reader.
 * Anything else, and any failure, goes to [fallback] from the first byte not yet delivered.
 */
@UnstableApi
class BtrDataSource(private val fallback: DataSource) : BaseDataSource(true) {
    private sealed interface Signal {
        class Bytes(val data: ByteArray) : Signal
        data object End : Signal
        class Failed(val error: Throwable) : Signal
    }

    private var dataSpec: DataSpec? = null
    private var job: Job? = null
    // Windows in order; each window's bytes arrive on its own queue. Null ends the stream.
    private var windows: Channel<LinkedBlockingQueue<Signal>?>? = null
    private var window: LinkedBlockingQueue<Signal>? = null
    private var current: ByteArray? = null
    private var currentOffset = 0
    private var delivered = 0L
    private var length = 0L
    private var usingFallback = false
    private var fallbackOpened = false
    private var transferring = false
    private var openedAtNs = 0L
    private var hostBytes = 0L
    private var hostReadNs = 0L

    private fun logHostThroughput() {
        val ms = hostReadNs / 1_000_000.0
        BtrTransport.log(
            "host bytes=$hostBytes readMs=${ms.toLong()} mbps=${"%.1f".format(hostBytes * 8 / 1000.0 / maxOf(1.0, ms))} " +
                "node=${fallback.uri?.host ?: dataSpec?.uri?.host}"
        )
        hostBytes = 0
        hostReadNs = 0
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        delivered = 0
        current = null
        currentOffset = 0
        window = null
        openedAtNs = System.nanoTime()
        val track = if (dataSpec.isEligible() && BtrTransport.isActive()) BtrTransport.trackFor(dataSpec.uri.toString()) else null
        val downloader = BtrTransport.downloader()
        if (track == null || downloader == null) return openFallback(dataSpec)

        transferInitializing(dataSpec)
        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        length = dataSpec.length
        val ordered = Channel<LinkedBlockingQueue<Signal>?>(LOOKAHEAD_WINDOWS)
        windows = ordered
        job = BtrTransport.scope.launch {
            var start = dataSpec.position
            // Inclusive end; for an open-ended read it is learned from the first window's Content-Range.
            var end: Long? = if (bounded) dataSpec.position + dataSpec.length - 1 else null
            var index = 0
            try {
                while (end == null || start <= end) {
                    val size = when {
                        bounded -> end!! - start + 1
                        index == 0 -> FIRST_WINDOW_BYTES
                        else -> WINDOW_BYTES
                    }
                    val windowEnd = end?.let { minOf(it, start + size - 1) } ?: (start + size - 1)
                    val signals = LinkedBlockingQueue<Signal>()
                    // Suspends while LOOKAHEAD_WINDOWS windows wait for the reader.
                    ordered.send(signals)
                    val range = BtrPiece(0, start, windowEnd)
                    val windowIndex = index
                    if (end == null) {
                        // The first window of an open-ended read tells the file length.
                        val total = downloadWindow(downloader, track, range, windowIndex, signals)
                        if (total == null) {
                            // No length to plan windows with: the host carries on after this window.
                            ordered.send(LinkedBlockingQueue<Signal>().apply { put(Signal.Failed(IOException("CDN 未返回文件长度"))) })
                            return@launch
                        }
                        end = total - 1
                    } else {
                        // Later windows run alongside each other; a failure stays in its window's queue.
                        launch { runCatching { downloadWindow(downloader, track, range, windowIndex, signals) } }
                    }
                    start = windowEnd + 1
                    index += 1
                }
                ordered.send(null)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
        transferring = true
        transferStarted(dataSpec)
        return length
    }

    // One BTR range; returns the file's total length. Its bytes go to [signals] in order.
    private suspend fun downloadWindow(
        downloader: BtrDownloader,
        track: BtrTransport.Track,
        range: BtrPiece,
        index: Int,
        signals: LinkedBlockingQueue<Signal>
    ): Long? {
        val kind = if (range.length <= META_MAX_BYTES) BtrKind.META else track.kind
        val startup = kind != BtrKind.META && index == 0 && track.startupPending.compareAndSet(true, false)
        var received = 0L
        val startedNs = System.nanoTime()
        BtrTransport.rangeStarted()
        try {
            val result = downloader.downloadRange(
                range, track.resolver,
                BtrRangeOptions(
                    kind = kind,
                    startup = startup,
                    priority = if (startup) 120.0 else maxOf(30.0, 55.0 - index * 5),
                    deadline = BtrTransport::deadline,
                    onOrderedChunk = if (kind == BtrKind.META) null else { bytes, piece, _ ->
                        // Every piece must follow the previous one exactly (BTR's media Range check).
                        if (piece.start != range.start + received || bytes.size.toLong() != piece.length) {
                            throw BtrTransferException("媒体 Range 校验失败")
                        }
                        received += bytes.size
                        signals.put(Signal.Bytes(bytes))
                    }
                )
            )
            result.bytes?.let { signals.put(Signal.Bytes(it)) }
            signals.put(Signal.End)
            val ms = (System.nanoTime() - startedNs) / 1_000_000.0
            BtrTransport.log(
                "btr kind=$kind startup=$startup window=$index bytes=${range.length} ms=${ms.toLong()} " +
                    "mbps=${"%.1f".format(range.length * 8 / 1000.0 / maxOf(1.0, ms))} pieces=${result.pieceCount} " +
                    "threads=${downloader.concurrency()} hosts=${result.hosts.joinToString(",")}"
            )
            return result.total
        } catch (error: Throwable) {
            signals.put(Signal.Failed(error))
            if (error !is kotlinx.coroutines.CancellationException) {
                BtrTransport.log("btr failed kind=$kind window=$index bytes=${range.length} received=$received reason=${error.message}")
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (usingFallback) {
            val startedNs = System.nanoTime()
            val count = fallback.read(buffer, offset, readLength)
            // Time spent waiting on the network, not on the player: the host path's real throughput.
            hostReadNs += System.nanoTime() - startedNs
            if (count > 0) {
                countBytes(count)
                hostBytes += count
                if (hostBytes >= HOST_LOG_BYTES) logHostThroughput()
            }
            return count
        }
        if (length != C.LENGTH_UNSET.toLong() && delivered >= length) return C.RESULT_END_OF_INPUT
        var chunk = current?.takeIf { currentOffset < it.size }
        while (chunk == null) {
            val queue = window ?: nextWindow() ?: return C.RESULT_END_OF_INPUT
            when (val signal = take(queue)) {
                is Signal.Bytes -> chunk = signal.data.also { current = it; currentOffset = 0 }
                Signal.End -> window = null
                is Signal.Failed -> return switchToFallback(signal.error, buffer, offset, readLength)
            }
        }
        val count = minOf(readLength, chunk.size - currentOffset)
        chunk.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        countBytes(count)
        return count
    }

    private fun countBytes(count: Int) {
        delivered += count
        if (transferring) bytesTransferred(count)
    }

    // The next window in order, waiting for the producer; null when the stream has ended.
    private fun nextWindow(): LinkedBlockingQueue<Signal>? {
        val ordered = windows ?: throw IOException("BTR 未打开")
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("BTR read interrupted")
            val result = ordered.tryReceive()
            if (result.isSuccess) return result.getOrNull().also { window = it }
            if (result.isClosed) throw IOException("BTR 窗口已关闭")
            if (job?.isCompleted == true && ordered.isEmpty) {
                // The producer stopped without ending the stream: let the host carry on.
                return LinkedBlockingQueue<Signal>().apply { put(Signal.Failed(IOException("BTR 下载中止"))) }
                    .also { window = it }
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun take(queue: LinkedBlockingQueue<Signal>): Signal {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("BTR read interrupted")
            queue.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        }
    }

    // BTR could not finish: continue from the first byte not yet delivered on the host's path.
    private fun switchToFallback(error: Throwable?, buffer: ByteArray, offset: Int, readLength: Int): Int {
        job?.cancel()
        job = null
        val spec = dataSpec ?: throw IOException("BTR 未打开", error)
        BtrTransport.fellBack()
        val rest = spec.subrange(delivered)
        try {
            fallback.open(rest)
        } catch (e: IOException) {
            error?.let(e::addSuppressed)
            throw e
        }
        fallbackOpened = true
        usingFallback = true
        return read(buffer, offset, readLength)
    }

    private fun openFallback(spec: DataSpec): Long {
        usingFallback = true
        fallbackOpened = true
        return fallback.open(spec)
    }

    override fun getUri(): Uri? = if (usingFallback) fallback.uri else dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (usingFallback) fallback.responseHeaders else emptyMap()

    override fun close() {
        if (usingFallback && hostBytes >= HOST_LOG_MIN_BYTES) logHostThroughput()
        hostBytes = 0
        hostReadNs = 0
        job?.cancel()
        job = null
        windows = null
        window = null
        current = null
        try {
            if (fallbackOpened) fallback.close()
        } finally {
            fallbackOpened = false
            usingFallback = false
            if (transferring) {
                transferring = false
                transferEnded()
            }
        }
    }

    private fun DataSpec.isEligible(): Boolean =
        httpMethod == DataSpec.HTTP_METHOD_GET && httpBody == null && position >= 0 &&
            (length == C.LENGTH_UNSET.toLong() || length in 1..MAX_RANGE_BYTES) &&
            (flags and DataSpec.FLAG_ALLOW_GZIP) == 0 && isBilibiliMediaUrl(uri.toString())

    companion object {
        // init and index requests are small; BTR races them over three addresses instead of splitting.
        private const val META_MAX_BYTES = 64L * 1024
        // A DASH segment request beyond this is not a segment; leave it to the host.
        private const val MAX_RANGE_BYTES = 64L * 1024 * 1024
        // An open-ended read starts small to learn the file length quickly, then reads ahead in
        // windows like BTR's segment window (a few segments in flight).
        private const val FIRST_WINDOW_BYTES = 1024L * 1024
        private const val WINDOW_BYTES = 4L * 1024 * 1024
        private const val LOOKAHEAD_WINDOWS = 2
        private const val POLL_MS = 10L
        private const val HOST_LOG_MIN_BYTES = 256L * 1024
        private const val HOST_LOG_BYTES = 4L * 1024 * 1024
    }
}
