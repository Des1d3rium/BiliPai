package com.android.purebilibili.btr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Buffer floors for the host player when [BtrSettings.stableBuffer] is on: a deeper buffer rides out
 * the overseas nodes' frequent stalls of a few seconds. Durations in milliseconds; start stays quick.
 */
data class BtrBufferFloor(
    val minBufferMs: Int = 20_000,
    val maxBufferMs: Int = 50_000,
    val bufferForPlaybackMs: Int = 0,
    val bufferForPlaybackAfterRebufferMs: Int = 2_500,
    /** The host buffers only this far ahead in a video's first quarter. */
    val earlyPlaybackMaxBufferMs: Int = 15_000
)

/** Process-wide diagnostics; never holds signed URLs. */
data class BtrStats(
    val enabled: Boolean = false,
    val threads: Int = 0,
    val autoReason: String? = null,
    val activeRequests: Int = 0,
    val speedBps: Long = 0,
    val deliveredBytes: Long = 0,
    val rangeCount: Long = 0,
    val fallbackCount: Long = 0,
    val bufferAheadMs: Long = 0,
    val nodes: List<BtrNodeStatus> = emptyList(),
    val bannedHosts: List<String> = emptyList()
)

/**
 * The host's single entry point. The host registers each video's DASH tracks, reports playback,
 * and wraps its data source with [BtrDataSource]; everything else stays in this module.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object BtrTransport {
    internal class Track(val kind: BtrKind, val resolver: BtrResolver) {
        val startupPending = AtomicBoolean(true)
    }

    private val clock: () -> Double = { System.nanoTime() / 1_000_000.0 }
    // One serial loop for every downloader coroutine, as the original runs on the page's event loop.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Volatile var settings: BtrSettings = BtrSettings(enabled = false)
        private set
    @Volatile private var networkAllowed: () -> Boolean = { true }
    @Volatile private var downloader: BtrDownloader? = null

    val autoConcurrency = BtrAutoConcurrency(clock).also { auto ->
        auto.subscribe { threads -> log("auto threads=$threads reason=${auto.status().reason}") }
    }
    // One ban list for every video: a node that kept failing for the last video is skipped at the
    // next one too, until its strikes age out (BtrBanList.BAN_TTL_MS).
    private val bans = BtrBanList(onBan = { host, scope -> log("ban $scope host=$host") })
    // Node speeds survive the session reset, so the next video starts from them, and are saved so
    // they also survive an app restart.
    private val nodeStats = BtrNodeStats()
    private var statsFile: java.io.File? = null
    private const val NODE_STATS_FILE = "btr_node_stats.tsv"
    private const val NODE_STATS_SAVE_MS = 10_000L

    private fun startNodeStatsPersistence(file: java.io.File) {
        statsFile = file
        scope.launch {
            runCatching { if (file.isFile) nodeStats.restore(file.readText()) }
                .onFailure { log("node stats not restored: ${it.message}") }
            var saved = nodeStats.version
            while (true) {
                kotlinx.coroutines.delay(NODE_STATS_SAVE_MS)
                val current = nodeStats.version
                if (current == saved) continue
                saved = current
                runCatching {
                    val temp = java.io.File(file.parentFile, "${file.name}.tmp")
                    temp.writeText(nodeStats.encode())
                    if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
                }.onFailure { log("node stats not saved: ${it.message}") }
            }
        }
    }
    private val tracks = LinkedHashMap<String, Track>()
    private var sessionPaths: Set<String> = emptySet()

    private val activeRequests = AtomicInteger()
    private val deliveredBytes = AtomicLong()
    private val rangeCount = AtomicLong()
    private val fallbackCount = AtomicLong()
    private val speedSamples = ArrayDeque<Pair<Double, Long>>()
    private val mutableStats = MutableStateFlow(BtrStats())
    val stats: StateFlow<BtrStats> = mutableStats.asStateFlow()

    // Playback, sampled by BtrPlaybackMonitor.
    @Volatile private var aheadMs = 0L
    @Volatile private var sampledAt = 0.0
    @Volatile private var playing = false
    @Volatile private var rate = 1f

    /** Installs the transport. [client] should be the app's playback client (shared pool). */
    @Synchronized
    fun configure(next: BtrSettings, client: OkHttpClient?, allowNetwork: () -> Boolean, storageDir: java.io.File? = null) {
        settings = next.normalized()
        networkAllowed = allowNetwork
        if (storageDir != null && statsFile == null) startNodeStatsPersistence(java.io.File(storageDir, NODE_STATS_FILE))
        if (client != null && downloader == null) {
            downloader = BtrDownloader(OkHttpBtrRangeFetcher(client), { settings }, autoConcurrency, clock, ::onTransfer)
        }
        downloader?.applySettings()
        publish()
    }

    @Synchronized
    fun disable() {
        settings = settings.copy(enabled = false)
        tracks.clear()
        sessionPaths = emptySet()
        publish()
    }

    /** Floors for the next player the host builds, or null to keep the host's own policy. */
    fun bufferFloor(): BtrBufferFloor? = if (settings.enabled && settings.stableBuffer) BtrBufferFloor() else null

    internal fun isActive(): Boolean = settings.enabled && settings.transfer && downloader != null && runCatching { networkAllowed() }.getOrDefault(false)

    /**
     * Registers one playback's tracks: each list is one track's base URL followed by its backups.
     * A different set of tracks starts a new session (bans, startup and the automatic count).
     */
    @Synchronized
    fun registerTracks(videoTracks: List<List<String>>, audioTracks: List<List<String>>) {
        // Registered even while disabled: the plugin is enabled asynchronously at startup, and a
        // video opened from a link registers before that. Whether BTR serves a read is decided then.
        val incoming = videoTracks.map { BtrKind.VIDEO to it } + audioTracks.map { BtrKind.AUDIO to it }
        val byPath = incoming.mapNotNull { (kind, urls) ->
            val safe = urls.mapNotNull(::safeMediaUrl).distinct()
            safe.firstOrNull()?.let { routeKey(it) to (kind to safe) }
        }.toMap()
        if (byPath.isEmpty()) return
        val isNewSession = byPath.keys.none { it in sessionPaths }
        if (isNewSession) {
            tracks.clear()
            sessionPaths = emptySet()
            autoConcurrency.newSession()
        }
        sessionPaths = sessionPaths + byPath.keys
        val currentBans = bans
        for ((path, entry) in byPath) {
            val (kind, urls) = entry
            val existing = tracks[path]
            if (existing != null) {
                // The same file with fresh signatures: swap them in without restarting anything.
                if (existing.resolver.updateOriginals(urls)) log("addresses refreshed kind=$kind")
                continue
            }
            tracks[path] = Track(kind, BtrResolver(urls, { settings.mode }, { settings.customHosts }, currentBans, nodeStats = nodeStats))
        }
        while (tracks.size > 32) tracks.remove(tracks.keys.first())
        scheduleAddressRefresh()
        publish()
    }

    /**
     * The host's way to fetch the current video's playurl again and pass it to [registerTracks]
     * without reloading the player. BTR calls it before the earliest signature expires, as BTR
     * refreshes its playinfo, and early when nodes start refusing addresses (HTTP 403).
     */
    @Volatile var addressRefresher: (suspend () -> Unit)? = null

    private var refreshJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastRefreshAt = 0L

    @Synchronized
    private fun scheduleAddressRefresh() {
        val deadline = tracks.values.mapNotNull { it.resolver.signatureDeadline() }.minOrNull() ?: return
        val now = System.currentTimeMillis()
        val delayMs = addressRefreshDelayMs(deadline, now)
        log("signatures expire in ${(deadline * 1000 - now) / 60_000} min; refresh in ${delayMs / 60_000} min")
        refreshJob?.cancel()
        refreshJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            refreshAddresses("signature expires soon")
        }
    }

    private fun refreshAddresses(reason: String) {
        val refresher = addressRefresher ?: return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return
        lastRefreshAt = now
        log("refreshing addresses: $reason")
        scope.launch {
            try {
                refresher()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("address refresh failed: ${e.message}")
            }
        }
    }

    @Synchronized
    internal fun trackFor(url: String): Track? = tracks[routeKey(url)]

    internal fun downloader(): BtrDownloader? = downloader

    /** The playhead moved somewhere new: the next ranges are a start again. */
    @Synchronized
    fun seek() {
        tracks.values.forEach { it.startupPending.set(true) }
        autoConcurrency.newSession()
    }

    fun playback(bufferAheadMs: Long, isPlaying: Boolean, speed: Float) {
        aheadMs = bufferAheadMs.coerceAtLeast(0)
        sampledAt = clock()
        playing = isPlaying
        rate = speed
        if (settings.autoConcurrency && settings.enabled) autoConcurrency.buffer(aheadMs / 1000.0, isPlaying)
        publish()
    }

    fun stall() {
        if (settings.autoConcurrency && settings.enabled) autoConcurrency.stall()
    }

    /**
     * When playback needs the next range: the buffered end reaches the playhead after the buffer
     * ahead has played out. Paused, the playhead stands still, so the deadline keeps its distance.
     */
    internal fun deadline(): Double {
        val now = clock()
        val speed = maxOf(0.25f, kotlin.math.abs(rate))
        if (!playing) return now + aheadMs / speed
        return maxOf(now, sampledAt + aheadMs / speed)
    }

    internal fun fellBack() {
        fallbackCount.incrementAndGet()
        publish()
    }

    /** One line per range for field measurements; hosts only, never signed URLs. */
    internal fun log(message: String) {
        runCatching { android.util.Log.i("BTR", message) }
    }

    internal fun rangeStarted() {
        rangeCount.incrementAndGet()
    }

    private fun onTransfer(event: BtrTransferEvent) {
        when (event.phase) {
            BtrTransferEvent.Phase.START -> activeRequests.incrementAndGet()
            BtrTransferEvent.Phase.DONE, BtrTransferEvent.Phase.CANCEL, BtrTransferEvent.Phase.ERROR -> {
                activeRequests.decrementAndGet()
                // A refused signature is the usual sign of an expired address.
                if ((event.error as? BtrTransferException)?.status == 403) refreshAddresses("a node refused an address")
                if (event.phase == BtrTransferEvent.Phase.DONE) {
                    deliveredBytes.addAndGet(event.totalBytes)
                    synchronized(speedSamples) {
                        speedSamples.addLast(clock() to event.totalBytes)
                        while (speedSamples.isNotEmpty() && clock() - speedSamples.first().first > 5_000) speedSamples.removeFirst()
                    }
                }
                publish()
            }
            BtrTransferEvent.Phase.PROGRESS -> Unit
        }
    }

    @Synchronized
    private fun publish() {
        val speed = synchronized(speedSamples) {
            val now = clock()
            while (speedSamples.isNotEmpty() && now - speedSamples.first().first > 5_000) speedSamples.removeFirst()
            if (speedSamples.isEmpty()) 0L else (speedSamples.sumOf { it.second } * 1000.0 / 5_000).toLong()
        }
        val status = autoConcurrency.status()
        mutableStats.value = BtrStats(
            enabled = settings.enabled,
            threads = if (settings.autoConcurrency) status.threads else settings.concurrency,
            autoReason = if (settings.autoConcurrency) status.reason else null,
            activeRequests = activeRequests.get().coerceAtLeast(0),
            speedBps = speed,
            deliveredBytes = deliveredBytes.get(),
            rangeCount = rangeCount.get(),
            fallbackCount = fallbackCount.get(),
            bufferAheadMs = aheadMs,
            nodes = tracks.values.firstOrNull { it.kind == BtrKind.VIDEO }?.resolver?.status().orEmpty(),
            bannedHosts = bans.bannedHosts()
        )
    }

    private const val REFRESH_LEAD_MS = 3 * 60_000L
    private const val MIN_REFRESH_DELAY_MS = 30_000L
    private const val MIN_REFRESH_INTERVAL_MS = 60_000L

    /** How long to wait before refreshing addresses that expire at [deadlineSeconds]. */
    internal fun addressRefreshDelayMs(deadlineSeconds: Long, nowMs: Long): Long =
        maxOf(MIN_REFRESH_DELAY_MS, deadlineSeconds * 1000 - REFRESH_LEAD_MS - nowMs)

    // Per node-independent file: the same track served by another node keeps its path.
    internal fun routeKey(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
}
