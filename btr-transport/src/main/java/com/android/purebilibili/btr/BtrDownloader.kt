package com.android.purebilibili.btr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

enum class BtrKind { META, VIDEO, AUDIO }

/** What playback asks of one range request; [deadline] is read again at every check. */
data class BtrRangeOptions(
    val kind: BtrKind = BtrKind.VIDEO,
    val parallel: Boolean = true,
    val startup: Boolean = false,
    val priority: Double = 50.0,
    val deadline: (() -> Double)? = null,
    val maxConcurrency: Int? = null,
    val onOrderedChunk: (suspend (bytes: ByteArray, piece: BtrPiece, total: Long?) -> Unit)? = null
)

class BtrPieceResult(val bytes: ByteArray, val total: Long?, val url: String)

class BtrRangeResult(
    /** Null when the bytes went to [BtrRangeOptions.onOrderedChunk]. */
    val bytes: ByteArray?,
    val byteLength: Long,
    val pieceCount: Int,
    val total: Long?,
    val hosts: List<String>
)

/** One request's progress; consumed by diagnostics. */
data class BtrTransferEvent(
    val phase: Phase,
    val url: String,
    val kind: BtrKind,
    val receivedBytes: Long,
    val totalBytes: Long,
    val bps: Double,
    val etaMs: Double?,
    val elapsedMs: Double,
    val error: Throwable? = null
) {
    enum class Phase { START, PROGRESS, DONE, CANCEL, ERROR }
}

/** Every attempt of one piece failed. */
class BtrPieceFailedException(cause: Throwable?) : java.io.IOException(cause?.message ?: "没有可用 CDN", cause)

/**
 * Port of BTR src/idm-downloader.js (createDownloader). All coroutines of one downloader are
 * expected to run on a single-threaded dispatcher, as the original runs on the page's event loop.
 */
class BtrDownloader(
    private val fetcher: BtrRangeFetcher,
    private val settings: () -> BtrSettings,
    private val auto: BtrAutoConcurrency,
    private val clock: () -> Double,
    private val onTransfer: (BtrTransferEvent) -> Unit = {}
) {
    private enum class StartupMode { NONE, STARTUP, PROBE }

    private class Recorder {
        var bytes = 0L
        val chunks = ArrayList<ByteArray>()
        fun add(chunk: ByteArray) { chunks += chunk; bytes += chunk.size }
    }

    private class Prefix(val bytes: Long, val chunks: List<ByteArray>)

    private class Context(
        val base: Prefix?,
        val recorder: Recorder,
        val startedAt: Double,
        var seenBytes: Long,
        var seenAt: Double,
        val length: Long
    )

    private class Plan(val recorder: Recorder, val part: BtrPiece)

    private class EarlyHedge(limit: Int) {
        var progressRemaining = maxOf(0, limit)
        fun claimProgress(): Boolean = if (progressRemaining <= 0) false else { progressRemaining -= 1; true }
        // At least one per range, even where no connection is held back: one request is much
        // cheaper than waiting out a timeout.
        var staleRemaining = maxOf(1, limit)
        fun claimStale(): Boolean = if (staleRemaining <= 0) false else { staleRemaining -= 1; true }
    }

    private class TrialState(var waited: Int = 0, var cursor: Int = 0)

    private fun config(): BtrSettings {
        val normalized = settings().normalized()
        return if (normalized.autoConcurrency) normalized.copy(concurrency = auto.threads()) else normalized
    }

    private val semaphore = BtrSemaphore(config().concurrency, clock).also {
        it.onChange = { active, limit, queued -> if (config().autoConcurrency) auto.demand(active, limit, queued) }
    }

    /** Re-read the thread count; the automatic controller calls this on every step. */
    fun applySettings() = semaphore.setLimit(config().concurrency)

    private val unsubscribe = auto.subscribe { applySettings() }

    fun close() = unsubscribe()

    fun concurrency(): Int = semaphore.limit

    // What one connection typically delivers and how long a sub-chunk typically takes.
    private var connectionBps = 0.0
    private var pieceMs = 0.0

    private fun recordMeter(bytes: Long, elapsedMs: Double) {
        if (bytes < SPEED_SAMPLE_MIN_BYTES || elapsedMs <= 0) return
        val bps = bytes * 1000.0 / elapsedMs
        connectionBps = if (connectionBps > 0) connectionBps * 0.7 + bps * 0.3 else bps
        pieceMs = if (pieceMs > 0) pieceMs * 0.7 + elapsedMs * 0.3 else elapsedMs
    }

    // A sub-chunk should keep its connection busy for a good part of a second, otherwise round
    // trips dominate on high-latency routes; a range still splits into at least one piece per node.
    private fun adaptiveMinChunk(s: BtrSettings, rangeLength: Long, pieceLimit: Int, hostCount: Int = 4): Long {
        if (connectionBps <= 0) return s.minChunkBytes
        val target = floor(connectionBps * 0.6 / CHUNK_STEP).toLong() * CHUNK_STEP
        val spread = ceil(rangeLength.toDouble() / maxOf(1, minOf(maxOf(4, hostCount), pieceLimit))).toLong()
        return maxOf(s.minChunkBytes, minOf(1024L * 1024, target, spread))
    }

    // A second copy starts once a piece takes clearly longer than pieces have been taking.
    private fun hedgeDelayMs(s: BtrSettings): Double =
        if (pieceMs > 0) maxOf(250.0, minOf(s.hedgeDelayMs.toDouble(), Math.round(pieceMs * 1.5).toDouble()))
        else s.hedgeDelayMs.toDouble()

    /**
     * Whether the second copy of a piece with a playback deadline should start now: no data or
     * nothing new for a whole delay; or it will miss its deadline and the copy's node is faster,
     * this connection is slower than usual, or the copy's node is unmeasured.
     */
    private fun hedgeDue(first: Context, delayMs: Double, deadlineAt: Double, copyBps: Double, rescue: EarlyHedge?): Boolean {
        val now = clock()
        val ran = now - first.startedAt
        val got = first.recorder.bytes
        if (got != first.seenBytes) {
            first.seenBytes = got
            first.seenAt = now
        }
        if (ran < delayMs) return false
        if (got == 0L || now - first.seenAt >= delayMs) return true
        val rate = got * 1000.0 / ran
        val remaining = maxOf(0L, first.length - got)
        if (now + remaining * 1000.0 / rate <= deadlineAt - HEDGE_SLACK_MS) return false
        if (copyBps > rate * HEDGE_FASTER) return true
        if (!(connectionBps > 0) || rate < connectionBps * HEDGE_PACE) return true
        if (!(copyBps > 0)) return true
        return now + remaining * 1000.0 / rate > deadlineAt && rescue?.claimStale() == true
    }

    private suspend fun attempt(
        piece: BtrPiece,
        url: String,
        kind: BtrKind,
        resolver: BtrResolver,
        priority: Double,
        begin: (() -> Plan)? = null,
        deadline: (() -> Double)? = null,
        observeProgress: ((BtrTransferEvent) -> Unit)? = null,
        queueClass: Int = QUEUE_CRITICAL
    ): BtrPieceResult {
        val s = config()
        val deadlineAt = deadline?.invoke() ?: Double.POSITIVE_INFINITY
        val release = semaphore.acquire(
            priority, deadlineAt, queueClass, queueClass == QUEUE_ORDINARY && deadlineAt.isFinite(), deadline
        )
        var part = piece
        var received = Recorder()
        val startedAt = clock()
        fun report(phase: BtrTransferEvent.Phase, error: Throwable? = null) {
            val elapsedMs = maxOf(1.0, clock() - startedAt)
            val bps = received.bytes * 1000.0 / elapsedMs
            val remaining = maxOf(0L, part.length - received.bytes)
            val event = BtrTransferEvent(
                phase, url, kind, received.bytes, part.length, bps,
                if (bps > 0) Math.round(remaining * 1000.0 / bps).toDouble() else null, elapsedMs, error
            )
            observeProgress?.invoke(event)
            onTransfer(event)
        }
        try {
            if (begin != null) {
                val plan = begin()
                part = plan.part
                received = plan.recorder
            }
            report(BtrTransferEvent.Phase.START)
            var headersAt = -1.0
            var lastByteAt = startedAt
            var timeoutReason: String? = null
            val total = coroutineScope {
                val fetch = async {
                    fetcher.fetch(url, part.start, part.end, onHeaders = {
                        headersAt = clock()
                        lastByteAt = headersAt
                    }) { chunk ->
                        received.add(chunk)
                        lastByteAt = clock()
                        if (s.autoConcurrency) auto.activity()
                        report(BtrTransferEvent.Phase.PROGRESS)
                    }
                }
                // BTR's three timers: first byte, a body that stops, and the whole attempt.
                val watchdog = launch {
                    while (isActive) {
                        delay(WATCHDOG_TICK_MS)
                        val now = clock()
                        timeoutReason = when {
                            headersAt < 0 && now - startedAt >= s.firstByteTimeoutMs -> "CDN 首字节超时"
                            headersAt >= 0 && now - lastByteAt >= s.stallTimeoutMs -> "CDN 子块停止传输"
                            now - startedAt >= s.attemptTimeoutMs -> "CDN 子块总耗时超限"
                            else -> null
                        }
                        if (timeoutReason != null) { fetch.cancel(); break }
                    }
                }
                try {
                    fetch.await()
                } catch (e: CancellationException) {
                    val reason = timeoutReason
                    if (reason != null) throw BtrTransferException(reason, timeout = true)
                    throw e
                } finally {
                    watchdog.cancel()
                }
            }
            if (received.bytes != part.length) throw BtrTransferException("子块长度不符：${received.bytes}/${part.length}")
            val bytes = concat(received.chunks, part.length)
            val elapsedMs = maxOf(1.0, clock() - startedAt)
            recordMeter(bytes.size.toLong(), elapsedMs)
            // The node answered either way; only a large enough transfer says how fast it is.
            resolver.success(url, if (bytes.size >= SPEED_SAMPLE_MIN_BYTES) bytes.size * 1000.0 / elapsedMs else 0.0)
            report(BtrTransferEvent.Phase.DONE)
            return BtrPieceResult(bytes, total, url)
        } catch (error: Throwable) {
            val canceled = error is CancellationException
            // A copy that lost its race still measured its node; without this a slow node would
            // never be measured, as a faster copy always finishes its pieces first.
            if (canceled && received.bytes >= SPEED_SAMPLE_MIN_BYTES) {
                resolver.sample(url, received.bytes * 1000.0 / maxOf(1.0, clock() - startedAt))
            }
            resolver.failure(url, error, received.bytes)
            if (s.autoConcurrency) {
                val status = (error as? BtrTransferException)?.status ?: 0
                if (status == 412 || status == 429) auto.pushback(status)
                else if (!canceled && (error as? BtrTransferException)?.timeout == true && received.bytes == 0L) auto.slow()
            }
            report(if (canceled) BtrTransferEvent.Phase.CANCEL else BtrTransferEvent.Phase.ERROR, error)
            throw error
        } finally {
            release.release()
        }
    }

    private fun pieceCandidates(piece: BtrPiece, resolver: BtrResolver, preferredUrls: List<String>, round: Int): List<String> {
        // The first preferred address is the node this piece was assigned to; only a retry round moves on.
        val offset = if (preferredUrls.isNotEmpty()) round % preferredUrls.size else 0
        val rotated = preferredUrls.drop(offset) + preferredUrls.take(offset)
        val rescue = resolver.rescueCandidates().filter { it !in rotated }
        // The copies after the first go to the fastest known nodes, wherever they were listed.
        val rest = (rotated.drop(1) + rescue).sortedByDescending { resolver.speed(it) }
        val candidates = (if (rotated.isNotEmpty()) listOf(rotated[0]) + rest else rest).toMutableList()
        for (url in resolver.ordered(piece.index)) if (url !in candidates) candidates += url
        return candidates
    }

    private suspend fun downloadPiece(
        piece: BtrPiece,
        resolver: BtrResolver,
        kind: BtrKind,
        preferredUrls: List<String>,
        startupMode: StartupMode = StartupMode.NONE,
        priority: Double = 0.0,
        deadline: (() -> Double)? = null,
        earlyHedge: EarlyHedge? = null
    ): BtrPieceResult {
        val hasDeadline = deadline != null && deadline().isFinite()
        val deadlineNow = { deadline?.invoke() ?: Double.POSITIVE_INFINITY }
        val s = config()
        val startup = startupMode != StartupMode.NONE
        val probe = startupMode == StartupMode.PROBE
        val startedAt = clock()
        var lastError: Throwable? = null

        // The longest contiguous run fetched from the front of this piece so far; every kept byte
        // came from a response whose 206 Content-Range matched this piece.
        var prefix: Prefix? = null
        fun keepProgress(base: Prefix?, recorder: Recorder) {
            val bytes = (base?.bytes ?: 0) + recorder.bytes
            if (bytes > (prefix?.bytes ?: 0) && bytes < piece.length) {
                prefix = Prefix(bytes, (base?.chunks ?: emptyList()) + recorder.chunks)
            }
        }
        fun liveProgress(context: Context?): Prefix? {
            context ?: return null
            val chunks = context.recorder.chunks.toList()
            val bytes = (context.base?.bytes ?: 0) + chunks.sumOf { it.size.toLong() }
            return Prefix(bytes, (context.base?.chunks ?: emptyList()) + chunks)
        }

        // One slow reply must not end acceleration: the list is walked again after a pause, and
        // rebuilt each time since node health and bans have changed by then.
        for (round in 0 until PIECE_ROUNDS) {
            if (round > 0) {
                if (clock() - startedAt > PIECE_RETRY_WINDOW_MS) break
                delay(minOf(2000L, 500L * (1L shl (round - 1))))
            }
            val candidates = pieceCandidates(piece, resolver, preferredUrls, round)
            val limit = minOf(8, candidates.size)
            val batchWidth = if (probe) limit else 2
            val tried = HashSet<String>()
            while (tried.size < limit) {
                currentCoroutineContext().ensureActive()
                // A node banned while this piece waited is skipped, unless only banned nodes are left.
                val untried = candidates.filter { it !in tried }
                val open = untried.filter(resolver::allows)
                val pair = open.ifEmpty { untried }.take(batchWidth)
                if (pair.isEmpty()) break
                tried += pair

                val firstFailed = CompletableDeferred<Unit>()
                val firstStraggler = CompletableDeferred<Unit>()
                var firstStartedAt = 0.0
                var deadlineDeficitSamples = 0
                val contexts = arrayOfNulls<Context>(pair.size)
                val observeFirst: (BtrTransferEvent) -> Unit = observe@{ event ->
                    val eta = event.etaMs
                    if (!hasDeadline || event.phase != BtrTransferEvent.Phase.PROGRESS || eta == null) return@observe
                    val deadlineAt = deadlineNow()
                    if (!deadlineAt.isFinite()) return@observe
                    val remainingToDeadline = deadlineAt - clock()
                    val missesDeadline = eta >= maxOf(0.0, remainingToDeadline)
                    val slowerThanPeers = connectionBps > 0 && event.bps < connectionBps * 0.5 && eta >= 500
                    deadlineDeficitSamples = if (eta - remainingToDeadline >= 250) deadlineDeficitSamples + 1 else 0
                    // A clearly slow node is rescued at once; a slow route after two deficit samples.
                    if ((missesDeadline && slowerThanPeers) || deadlineDeficitSamples >= 2) firstStraggler.complete(Unit)
                }

                val attempts: List<suspend () -> BtrPieceResult> = pair.mapIndexed { pairIndex, url ->
                    suspend {
                        if (pairIndex > 0) waitForHedge(
                            s, probe, startup, hasDeadline, deadlineNow, url, resolver, earlyHedge,
                            firstFailed, firstStraggler, { firstStartedAt }, { contexts[0] }
                        )
                        // Resume from the longest prefix known when the request really starts.
                        var base: Prefix? = null
                        val recorder = Recorder()
                        val begin = {
                            if (pairIndex == 0) firstStartedAt = clock()
                            base = prefix?.takeIf { it.bytes >= RESUME_MIN_BYTES }
                            if (pairIndex > 0) {
                                val live = liveProgress(contexts[0])
                                if (live != null && live.bytes >= RESUME_MIN_BYTES && live.bytes > (base?.bytes ?: 0)) base = live
                            }
                            if ((base?.bytes ?: 0) >= piece.length) base = null
                            val began = clock()
                            val taken = base?.bytes ?: 0
                            contexts[pairIndex] = Context(base, recorder, began, 0, began, piece.length - taken)
                            Plan(recorder, if (taken > 0) BtrPiece(piece.index, piece.start + taken, piece.end) else piece)
                        }
                        try {
                            val result = attempt(
                                piece, url, kind, resolver, priority + if (pairIndex > 0) 20 else 0, begin, deadline,
                                if (pairIndex == 0) observeFirst else null,
                                when {
                                    probe -> QUEUE_CRITICAL
                                    startup -> QUEUE_STARTUP
                                    else -> QUEUE_ORDINARY
                                }
                            )
                            val kept = base
                            if (kept != null) BtrPieceResult(concat(kept.chunks + result.bytes, piece.length), result.total, result.url)
                            else result
                        } catch (error: Throwable) {
                            keepProgress(base, recorder)
                            if (pairIndex == 0) firstFailed.complete(Unit)
                            throw error
                        }
                    }
                }
                try {
                    val winner = firstSuccess(attempts)
                    if (s.autoConcurrency) auto.delivered(piece.length)
                    return winner
                } catch (failed: BtrPieceFailedException) {
                    lastError = failed.cause
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        throw BtrPieceFailedException(lastError)
    }

    // The second copy of a pair waits for its turn (see hedgeDue); a first copy refused at once
    // or found straggling lets it start early.
    private suspend fun waitForHedge(
        s: BtrSettings,
        probe: Boolean,
        startup: Boolean,
        hasDeadline: Boolean,
        deadlineNow: () -> Double,
        url: String,
        resolver: BtrResolver,
        earlyHedge: EarlyHedge?,
        firstFailed: CompletableDeferred<Unit>,
        firstStraggler: CompletableDeferred<Unit>,
        firstStartedAt: () -> Double,
        firstContext: () -> Context?
    ) {
        val measured = hedgeDelayMs(s)
        val delayMs = when {
            probe -> 0.0
            startup -> minOf(if (hasDeadline) 200.0 else 250.0, measured)
            else -> measured
        }
        val waitedFrom = clock()
        var earlyClaimed = false
        while (true) {
            if (firstFailed.isCompleted) return
            if (firstStraggler.isCompleted && !probe && !earlyClaimed && earlyHedge?.claimProgress() == true) {
                earlyClaimed = true
                val grace = maxOf(0.0, 250 - (clock() - firstStartedAt()))
                if (grace > 0) delay(grace.toLong())
                return
            }
            if (hasDeadline && !probe) {
                val first = firstContext()
                if (first != null && hedgeDue(first, delayMs, deadlineNow(), resolver.speed(url), earlyHedge)) return
                delay(HEDGE_CHECK_MS)
            } else {
                val left = delayMs - (clock() - waitedFrom)
                if (left <= 0) return
                delay(minOf(HEDGE_CHECK_MS.toDouble(), left).toLong().coerceAtLeast(1))
            }
        }
    }

    /** Runs the attempts together; the first success wins and cancels the rest. */
    private suspend fun <T> firstSuccess(blocks: List<suspend () -> T>): T = coroutineScope {
        val results = Channel<Result<T>>(blocks.size)
        val jobs = blocks.map { block ->
            launch {
                val outcome = try {
                    Result.success(block())
                } catch (e: CancellationException) {
                    if (isActive) Result.failure(e) else throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                results.send(outcome)
            }
        }
        var last: Throwable? = null
        repeat(blocks.size) {
            val outcome = results.receive()
            if (outcome.isSuccess) {
                jobs.forEach { it.cancel(CancellationException("并发副本已取消")) }
                @Suppress("UNCHECKED_CAST")
                return@coroutineScope outcome.getOrNull() as T
            }
            last = outcome.exceptionOrNull()
        }
        throw BtrPieceFailedException(last)
    }

    private suspend fun startupAttempt(piece: BtrPiece, candidates: List<String>, resolver: BtrResolver, kind: BtrKind): BtrPieceResult =
        firstSuccess(candidates.mapIndexed { index, url ->
            suspend {
                val wait = when (index) { 0 -> 0L; 1 -> 120L; else -> 300L }
                if (wait > 0) delay(wait)
                attempt(piece, url, kind, resolver, 220.0)
            }
        })

    // Which address each piece tries first. The fastest node gets the most pieces; a node under a
    // twelfth of the best is left out; an unmeasured node gets a trial piece or two from the end.
    private var assignTurn = 0
    private val trialStates = WeakHashMap<BtrResolver, TrialState>()

    private fun assignPrimaries(urlsIn: List<String>, resolver: BtrResolver, countIn: Int): List<String> {
        var urls = urlsIn
        var count = countIn
        if (urls.isEmpty() || count <= 0) return emptyList()
        if (urls.size == 1) return List(count) { urls[0] }
        // Each range opens one node further on, so ranges in flight together do not all start on one node.
        val turn = assignTurn
        assignTurn = (assignTurn + 1) % 4096
        val measure = { url: String -> maxOf(0.0, resolver.speed(url)) }
        var known = urls.map(measure)
        if (known.none { it > 0 }) return List(count) { urls[(it + turn) % urls.size] }
        val top = known.max()
        val eligible = urls.filterIndexed { index, _ -> known[index] == 0.0 || known[index] >= top / 12 }
        if (eligible.isNotEmpty() && eligible.size < urls.size) {
            urls = eligible
            known = urls.map(measure)
        }
        val unknown = urls.filterIndexed { index, _ -> known[index] == 0.0 }
        var trials = minOf(unknown.size * 2, count / 4)
        val trialState = trialStates.getOrPut(resolver) { TrialState() }
        // Small segments never have a piece to spare: every fourth one gives up its last piece.
        if (trials == 0 && unknown.isNotEmpty() && count >= 2) {
            trialState.waited += 1
            if (trialState.waited >= 4) trials = 1
        }
        if (trials > 0) trialState.waited = 0
        if (unknown.isNotEmpty()) {
            urls = urls.filterIndexed { index, _ -> known[index] > 0 }
            known = urls.map(measure)
            count -= trials
        }
        val weights = known.map { maxOf(it, top * 0.05) }
        val total = weights.sum()
        // Smooth weighted round-robin, so every segment's first pieces spread over the nodes.
        val primaries = ArrayList<String>(count + trials)
        val credit = DoubleArray(weights.size)
        val order = urls.indices.map { (it + turn) % urls.size }
        repeat(count) {
            var best = order[0]
            for (index in order) {
                credit[index] += weights[index]
                if (credit[index] > credit[best]) best = index
            }
            credit[best] -= total
            primaries += urls[best]
        }
        repeat(trials) { primaries += unknown[(it + trialState.cursor) % unknown.size] }
        trialState.cursor = (trialState.cursor + trials) % 4096
        return primaries
    }

    private fun preferredFor(primary: String?, urls: List<String>): List<String> =
        if (primary != null) listOf(primary) + urls.filter { it != primary } else urls

    // init and index: one small request raced over three addresses, the next ones 120 and 300 ms later.
    private suspend fun downloadStartupRange(range: BtrPiece, resolver: BtrResolver): BtrRangeResult {
        semaphore.setLimit(config().concurrency)
        val piece = BtrPiece(0, range.start, range.end)
        val startedAt = clock()
        var lastError: Throwable? = null
        for (round in 0 until PIECE_ROUNDS) {
            if (round > 0) {
                if (clock() - startedAt > PIECE_RETRY_WINDOW_MS) break
                delay(minOf(2000L, 500L * (1L shl (round - 1))))
            }
            var candidates = resolver.startupCandidates().distinct().take(3)
            if (candidates.isEmpty() && round > 0) candidates = resolver.ordered(round).take(3)
            if (candidates.isEmpty()) break
            try {
                val winner = startupAttempt(piece, candidates, resolver, BtrKind.META)
                return BtrRangeResult(winner.bytes, range.length, 1, winner.total, listOf(hostOf(winner.url)))
            } catch (failed: BtrPieceFailedException) {
                currentCoroutineContext().ensureActive()
                lastError = failed.cause
            }
        }
        throw BtrPieceFailedException(lastError)
    }

    private class OrderedFlush(
        private val pieces: List<BtrPiece>,
        private val sink: suspend (ByteArray, BtrPiece, Long?) -> Unit
    ) {
        private val ready = arrayOfNulls<BtrPieceResult>(pieces.size)
        private var next = 0
        private val mutex = Mutex()

        suspend fun complete(index: Int, result: BtrPieceResult) {
            ready[index] = result
            mutex.withLock {
                while (next < pieces.size) {
                    val item = ready[next] ?: break
                    ready[next] = null
                    sink(item.bytes, pieces[next], item.total)
                    next += 1
                }
            }
        }
    }

    // The first media range of a start: a 64 KiB probe raced over the candidates measures them,
    // then the rest spreads over the nodes that delivered, written piece by piece in order.
    private suspend fun downloadStartupMediaRange(range: BtrPiece, resolver: BtrResolver, options: BtrRangeOptions, s: BtrSettings): BtrRangeResult {
        val effectiveConcurrency = s.concurrency
        semaphore.setLimit(effectiveConcurrency)
        val sink = checkNotNull(options.onOrderedChunk)
        val candidateUrls = resolver.rangeCandidates().distinct()
        val headLength = minOf(range.length, maxOf(64L * 1024, s.minChunkBytes))
        val head = BtrPiece(0, range.start, range.start + headLength - 1)
        val headResult = downloadPiece(head, resolver, options.kind, candidateUrls, StartupMode.PROBE, 220.0, options.deadline)
        sink(headResult.bytes, head, headResult.total)
        if (head.end >= range.end) {
            return BtrRangeResult(null, range.length, 1, headResult.total, listOf(hostOf(headResult.url)))
        }
        val rescueReserve = maxOf(1, minOf(16, ceilDiv(effectiveConcurrency, 8)))
        val mediaBudget = maxOf(1, effectiveConcurrency - rescueReserve)
        val audioBudget = maxOf(1, minOf(mediaBudget, ceilDiv(effectiveConcurrency, 8)))
        val pieceBudget = if (options.kind == BtrKind.AUDIO) audioBudget else maxOf(1, mediaBudget - audioBudget)
        val pieces = splitBtrRange(
            head.end + 1, range.end, pieceBudget,
            adaptiveMinChunk(s, range.end - head.end, pieceBudget, candidateUrls.size)
        ).map { it.copy(index = it.index + 1) }
        // Only addresses that have delivered carry the first segment; the others stay for rescue.
        val provenUrls = candidateUrls.filter { it == headResult.url || resolver.speed(it) > 0 }
        val primaries = assignPrimaries(provenUrls.ifEmpty { listOf(headResult.url) }, resolver, pieces.size)
        val earlyHedge = EarlyHedge(rescueReserve)
        val fallbacks = listOf(headResult.url) + candidateUrls.filter { it != headResult.url }
        val flush = OrderedFlush(pieces, sink)
        val results = coroutineScope {
            pieces.mapIndexed { orderedIndex, piece ->
                async {
                    downloadPiece(
                        piece, resolver, options.kind, preferredFor(primaries.getOrNull(orderedIndex), fallbacks),
                        StartupMode.STARTUP, 120.0 - minOf(30, piece.index), options.deadline, earlyHedge
                    ).also { flush.complete(orderedIndex, it) }
                }
            }.map { it.await() }
        }
        val totals = (listOf(headResult) + results).mapNotNull { it.total }
        if (totals.distinct().size > 1) throw BtrTransferException("不同 CDN 返回的文件总长度不一致")
        return BtrRangeResult(
            null, range.length, pieces.size + 1, totals.firstOrNull(),
            (listOf(headResult) + results).map { hostOf(it.url) }.distinct()
        )
    }

    /** Downloads [range] (inclusive) as BTR does: split over nodes, hedged, resumed and in order. */
    suspend fun downloadRange(range: BtrPiece, resolver: BtrResolver, options: BtrRangeOptions = BtrRangeOptions()): BtrRangeResult {
        val s = config()
        if (options.kind == BtrKind.META) return downloadStartupRange(range, resolver)
        if (options.startup && options.parallel && options.onOrderedChunk != null) {
            return downloadStartupMediaRange(range, resolver, options, s)
        }
        val preferredUrls = if (options.parallel) resolver.rangeCandidates() else resolver.urls()
        val globalConcurrency = if (options.parallel) s.concurrency else 1
        val requested = options.maxConcurrency?.coerceAtLeast(1) ?: globalConcurrency
        val effectiveConcurrency = if (options.parallel) minOf(globalConcurrency, requested) else 1
        // Fewer primary pieces than slots leaves room for a stalled piece's hedge to start at once.
        semaphore.setLimit(globalConcurrency)
        val rescueReserve = if (options.parallel && effectiveConcurrency >= 8) minOf(8, maxOf(1, ceilDiv(effectiveConcurrency, 8))) else 0
        val pieceConcurrency = if (options.startup) maxOf(1, minOf(22, effectiveConcurrency))
        else maxOf(1, effectiveConcurrency - rescueReserve)
        val pieces = splitBtrRange(
            range.start, range.end, pieceConcurrency,
            if (options.parallel) adaptiveMinChunk(s, range.length, pieceConcurrency, preferredUrls.size) else Long.MAX_VALUE
        )
        val primaries = if (options.parallel) assignPrimaries(preferredUrls, resolver, pieces.size) else emptyList()
        val earlyHedge = EarlyHedge(rescueReserve)
        val flush = options.onOrderedChunk?.let { OrderedFlush(pieces, it) }
        val results = coroutineScope {
            pieces.map { piece ->
                async {
                    downloadPiece(
                        piece, resolver, options.kind, preferredFor(primaries.getOrNull(piece.index), preferredUrls),
                        if (options.startup) StartupMode.STARTUP else StartupMode.NONE,
                        options.priority - minOf(20, piece.index), options.deadline, earlyHedge
                    ).also { flush?.complete(piece.index, it) }
                }
            }.map { it.await() }
        }
        val totals = results.mapNotNull { it.total }
        if (totals.distinct().size > 1) throw BtrTransferException("不同 CDN 返回的文件总长度不一致")
        return BtrRangeResult(
            if (flush != null) null else concat(results.map { it.bytes }, range.length),
            range.length, pieces.size, totals.firstOrNull(), results.map { hostOf(it.url) }.distinct()
        )
    }

    companion object {
        private const val PIECE_ROUNDS = 3
        private const val PIECE_RETRY_WINDOW_MS = 25_000.0
        // Below this a resumed request saves less than its own round trip costs.
        private const val RESUME_MIN_BYTES = 32L * 1024
        private const val HEDGE_SLACK_MS = 1_500.0
        private const val HEDGE_PACE = 0.6
        private const val HEDGE_FASTER = 1.5
        private const val HEDGE_CHECK_MS = 50L
        private const val WATCHDOG_TICK_MS = 50L
        // A shorter transfer is mostly round trip and would mark down the node that came to rescue.
        private const val SPEED_SAMPLE_MIN_BYTES = 48L * 1024
        private const val CHUNK_STEP = 64L * 1024

        private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

        internal fun concat(chunks: List<ByteArray>, expectedLength: Long): ByteArray {
            val output = ByteArray(expectedLength.toInt())
            var offset = 0
            for (chunk in chunks) {
                if (offset + chunk.size > output.size) throw BtrTransferException("子区间超出目标长度")
                chunk.copyInto(output, offset)
                offset += chunk.size
            }
            if (offset.toLong() != expectedLength) throw BtrTransferException("子区间长度不符：$offset/$expectedLength")
            return output
        }
    }
}
