package com.android.purebilibili.btr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Media3 boundary for the BTR downloader. A bounded GET of a registered DASH track is downloaded
 * by BTR and handed over in order; anything else, and any failure, goes to [fallback] from the
 * first byte not yet delivered, so playback never depends on BTR succeeding.
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
    private var queue: LinkedBlockingQueue<Signal>? = null
    private var current: ByteArray? = null
    private var currentOffset = 0
    private var delivered = 0L
    private var length = 0L
    private var usingFallback = false
    private var fallbackOpened = false
    private var transferring = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        delivered = 0
        current = null
        currentOffset = 0
        val track = if (dataSpec.isEligible() && BtrTransport.isActive()) BtrTransport.trackFor(dataSpec.uri.toString()) else null
        val downloader = BtrTransport.downloader()
        if (track == null || downloader == null) return openFallback(dataSpec)

        transferInitializing(dataSpec)
        length = dataSpec.length
        val signals = LinkedBlockingQueue<Signal>()
        queue = signals
        val range = BtrPiece(0, dataSpec.position, dataSpec.position + dataSpec.length - 1)
        val kind = if (dataSpec.length <= META_MAX_BYTES) BtrKind.META else track.kind
        val startup = kind != BtrKind.META && track.startupPending.compareAndSet(true, false)
        var received = 0L
        BtrTransport.rangeStarted()
        job = BtrTransport.scope.launch {
            try {
                downloader.downloadRange(
                    range, track.resolver,
                    BtrRangeOptions(
                        kind = kind,
                        startup = startup,
                        priority = if (startup) 120.0 else 55.0,
                        deadline = BtrTransport::deadline,
                        onOrderedChunk = { bytes, piece, _ ->
                            // Every piece must follow the previous one exactly (BTR's media Range check).
                            if (piece.start != range.start + received || bytes.size.toLong() != piece.length) {
                                throw BtrTransferException("媒体 Range 校验失败")
                            }
                            received += bytes.size
                            signals.put(Signal.Bytes(bytes))
                        }
                    ).let { if (kind == BtrKind.META) it.copy(onOrderedChunk = null) else it }
                ).bytes?.let { signals.put(Signal.Bytes(it)) }
                signals.put(Signal.End)
            } catch (error: Throwable) {
                signals.put(Signal.Failed(error))
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
        transferring = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (usingFallback) {
            val count = fallback.read(buffer, offset, readLength)
            if (count > 0) countBytes(count)
            return count
        }
        if (delivered >= length) return C.RESULT_END_OF_INPUT
        val chunk = current?.takeIf { currentOffset < it.size } ?: when (val signal = nextSignal()) {
            is Signal.Bytes -> signal.data.also { current = it; currentOffset = 0 }
            Signal.End -> return if (delivered >= length) C.RESULT_END_OF_INPUT else switchToFallback(null, buffer, offset, readLength)
            is Signal.Failed -> return switchToFallback(signal.error, buffer, offset, readLength)
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

    private fun nextSignal(): Signal {
        val signals = queue ?: throw IOException("BTR 未打开")
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("BTR read interrupted")
            signals.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
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
        job?.cancel()
        job = null
        queue = null
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
        httpMethod == DataSpec.HTTP_METHOD_GET && httpBody == null && length != C.LENGTH_UNSET.toLong() &&
            length > 0 && position >= 0 && length <= MAX_RANGE_BYTES &&
            (flags and DataSpec.FLAG_ALLOW_GZIP) == 0 && isBilibiliMediaUrl(uri.toString())

    companion object {
        // init and index requests are small; BTR races them over three addresses instead of splitting.
        private const val META_MAX_BYTES = 64L * 1024
        // A DASH segment request beyond this is not a segment; leave it to the host.
        private const val MAX_RANGE_BYTES = 64L * 1024 * 1024
    }
}
