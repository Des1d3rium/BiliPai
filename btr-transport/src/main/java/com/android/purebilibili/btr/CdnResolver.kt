package com.android.purebilibili.btr

import java.net.URI

/** Port of BTR src/cdn-resolver.js. */
object BtrCdnHosts {
    val MAINLAND = listOf(
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-sz-mirror14b.bilivideo.com",
        "upos-sz-estgoss.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com"
    )
    val OVERSEAS = listOf(
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "cn-hk-eq-01-01.bilivideo.com",
        "cn-hk-eq-01-03.bilivideo.com"
    )
    val GLOBAL = OVERSEAS + MAINLAND
}

internal fun isAkamaiUrl(url: String): Boolean = hostOf(url).endsWith(".akamaized.net")

internal fun safeMediaUrl(url: String): String? = url.takeIf(::isBilibiliMediaUrl)

/** Same signed path and query on another node; a non-standard port is dropped. */
internal fun swapOrdinaryHost(rawUrl: String, targetHost: String, allowAkamai: Boolean = false): String? {
    if (!allowAkamai && isAkamaiUrl(rawUrl)) return null
    val host = targetHost.lowercase()
    if (normalizeBtrCdnHost(host) != host) return null
    return runCatching {
        val uri = URI(rawUrl)
        "${uri.scheme}://$host${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" } ?: ""}"
    }.getOrNull()
}

private fun customServers(mode: BtrCdnMode, customHosts: List<String>): List<String> =
    if (mode == BtrCdnMode.CUSTOM) customHosts.map(::normalizeBtrCdnHost).filter { it.isNotEmpty() } else emptyList()

/**
 * The addresses one track may be downloaded from: Bilibili's own addresses for the mode, then
 * the same signature on every node of the mode. Node-major order keeps the first requests spread.
 */
fun btrRepresentationUrls(originalUrls: List<String>, mode: BtrCdnMode, customHosts: List<String> = emptyList()): List<String> {
    val originals = originalUrls.mapNotNull(::safeMediaUrl).distinct()
    val custom = customServers(mode, customHosts)
    val hosts = custom.ifEmpty { if (mode == BtrCdnMode.OVERSEAS) BtrCdnHosts.OVERSEAS else BtrCdnHosts.MAINLAND }
    val donor = originals.firstOrNull { !isAkamaiUrl(it) }
    // Some overseas accounts only get akamaized.net addresses; the nodes accept those signatures too.
    val synthetic = (if (donor != null) hosts.map { swapOrdinaryHost(donor, it) }
    else hosts.flatMap { host -> originals.map { swapOrdinaryHost(it, host, allowAkamai = true) } })
        .mapNotNull { it?.let(::safeMediaUrl) }
    val allowedOriginals = when {
        custom.isNotEmpty() -> originals.filter { hostOf(it) in custom }
        mode == BtrCdnMode.OVERSEAS -> originals.filter { hostOf(it) !in BtrCdnHosts.MAINLAND }
        else -> originals.filter { hostOf(it) in BtrCdnHosts.MAINLAND }
    }
    return (allowedOriginals + synthetic).distinct()
}

/** Why an attempt failed; [status] is the HTTP status when a node answered. */
class BtrTransferException(
    message: String,
    val status: Int = 0,
    val timeout: Boolean = false,
    cause: Throwable? = null
) : java.io.IOException(message, cause)

/**
 * A node that twice fails without sending a byte is skipped for the rest of the video. A 4xx
 * blames the node or the address depending on which of them has delivered elsewhere.
 */
class BtrBanList(private val limit: Int = 2, private val onBan: (String, String) -> Unit = { _, _ -> }) {
    private val emptyReplies = HashMap<Triple<String, String, Boolean>, Int>()
    private val goodNodes = HashSet<String>()
    private val goodAddresses = HashSet<String>()
    private val reported = HashSet<String>()
    private var banned = emptySet<String>()

    @Synchronized
    private fun judge(url: String): Boolean {
        val strikes = HashMap<String, Int>()
        for ((key, count) in emptyReplies) {
            val (node, address, refused) = key
            val blamed = when {
                !refused -> "node:$node"
                node in goodNodes -> if (address in goodAddresses) "pair:$node $address" else "address:$address"
                address in goodAddresses -> "node:$node"
                else -> ""
            }
            if (blamed.isNotEmpty()) strikes[blamed] = (strikes[blamed] ?: 0) + count
        }
        banned = strikes.filterValues { it >= limit }.keys
        var added = false
        for (key in banned) {
            if (!reported.add(key)) continue
            added = true
            val isNode = key.startsWith("node:")
            runCatching { onBan(if (isNode) key.removePrefix("node:") else hostOf(url), if (isNode) "node" else "address") }
        }
        return added
    }

    @Synchronized
    fun record(url: String, receivedBytes: Long, error: Throwable?): Boolean {
        if (error is kotlinx.coroutines.CancellationException || receivedBytes > 0) return false
        val node = hostOf(url).ifEmpty { return false }
        val status = (error as? BtrTransferException)?.status ?: 0
        val key = Triple(node, addressOf(url), status in 400..499)
        emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
        return judge(url)
    }

    @Synchronized
    fun success(url: String) {
        val node = hostOf(url).ifEmpty { return }
        val address = addressOf(url)
        if (node in goodNodes && address in goodAddresses) return
        goodNodes += node
        goodAddresses += address
        judge(url)
    }

    @Synchronized
    fun allows(url: String): Boolean = "node:${hostOf(url)}" !in banned && "address:${addressOf(url)}" !in banned &&
        "pair:${hostOf(url)} ${addressOf(url)}" !in banned

    @Synchronized
    fun allowsNode(url: String): Boolean = "node:${hostOf(url)}" !in banned

    @Synchronized
    fun bannedHosts(): List<String> = banned.filter { it.startsWith("node:") }.map { it.removePrefix("node:") }

    @Synchronized
    fun reset() {
        emptyReplies.clear(); goodNodes.clear(); goodAddresses.clear(); reported.clear(); banned = emptySet()
    }
}

data class BtrNodeStatus(val host: String, val state: String, val bps: Double)

/**
 * Per-track node selection. A measured speed counts for [MEASUREMENT_TTL_MS]; a node left out for
 * being slow then becomes untested again and gets another try through the exploration slot.
 */
class BtrResolver(
    private val originalUrls: List<String>,
    private val mode: () -> BtrCdnMode,
    private val customHosts: () -> List<String> = { emptyList() },
    private val bans: BtrBanList? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Health(val failures: Int = 0, val blockedUntil: Long = 0, val lastSuccessAt: Long = 0)
    private data class Route(val lastSuccessAt: Long = 0, val lastMeasuredAt: Long = 0, val bps: Double = 0.0)

    private val health = HashMap<String, Health>()
    // Per node and file, without the query: a fresh signature is the same route.
    private val routes = HashMap<String, Route>()
    private var cursor = 0
    private var mediaRangeCount = 0
    private var rangeCursor = 0

    private fun routeOf(url: String): String =
        runCatching { URI(url).let { "${it.rawAuthority}${it.rawPath}" } }.getOrDefault(url)

    private fun measurement(url: String): Route? = routes[routeOf(url)]
    private fun measuredNow(url: String, now: Long): Boolean =
        measurement(url)?.lastMeasuredAt?.let { it > 0 && now - it < MEASUREMENT_TTL_MS } == true
    private val bySpeed = Comparator<String> { a, b ->
        val am = measurement(a) ?: Route()
        val bm = measurement(b) ?: Route()
        val proven = (bm.lastSuccessAt > 0).compareTo(am.lastSuccessAt > 0)
        if (proven != 0) proven else bm.bps.compareTo(am.bps)
    }
    private fun open(url: String, now: Long) = (health[url]?.blockedUntil ?: 0) <= now

    private fun allUrls() = btrRepresentationUrls(originalUrls, mode(), customHosts())

    private fun unbanned(list: List<String>): List<String> {
        val ban = bans ?: return list
        return list.filter(ban::allows).ifEmpty { list }
    }

    @Synchronized fun urls(): List<String> = unbanned(allUrls())

    @Synchronized
    fun ordered(pieceIndex: Int = 0, exclude: Set<String> = emptySet()): List<String> {
        val now = clock()
        val candidates = urls().filter { it !in exclude }
        val pool = candidates.filter { open(it, now) }.ifEmpty { candidates }
        if (pool.isEmpty()) return emptyList()
        val offset = (cursor + pieceIndex) % pool.size
        cursor = (cursor + 1) % pool.size
        return pool.drop(offset) + pool.take(offset)
    }

    @Synchronized
    fun rangeCandidates(): List<String> {
        val now = clock()
        val pool = urls().filter { open(it, now) }.sortedWith(bySpeed)
        if (pool.isEmpty()) return urls()
        val width = minOf(if (mediaRangeCount == 0) pool.size else 6, pool.size)
        val warmupRanges = if (mode() == BtrCdnMode.MAINLAND) 1 else 4
        val selected: MutableList<String>
        if (mediaRangeCount < warmupRanges) {
            selected = pool.take(width).toMutableList()
            rangeCursor = width % pool.size
        } else {
            // Measured nodes carry the segments by speed; one other node rides along to be measured.
            val measured = pool.filter { measuredNow(it, now) }
            val rest = pool.filter { !measuredNow(it, now) }
            val places = minOf(rest.size, maxOf(1, width - measured.size))
            val explore = List(places) { rest[(rangeCursor + it) % rest.size] }
            rangeCursor = (rangeCursor + places) % maxOf(1, pool.size)
            selected = (measured.take(width - explore.size) + explore).toMutableList()
            for (url in pool) {
                if (selected.size >= minOf(3, pool.size)) break
                if (url !in selected) selected += url
            }
        }
        mediaRangeCount += 1
        return selected
    }

    @Synchronized
    fun startupCandidates(): List<String> {
        val now = clock()
        val originals = if (customServers(mode(), customHosts()).isNotEmpty()) emptyList()
        else originalUrls.mapNotNull(::safeMediaUrl)
        return unbanned((originals + allUrls()).distinct()).filter { open(it, now) }.take(8)
    }

    @Synchronized
    fun rescueCandidates(): List<String> {
        val now = clock()
        return urls().filter { open(it, now) }.sortedWith(bySpeed)
    }

    @Synchronized
    fun success(url: String, bps: Double) {
        bans?.success(url)
        val old = measurement(url) ?: Route()
        val now = clock()
        val measured = bps > 0
        health[url] = Health(lastSuccessAt = now)
        routes[routeOf(url)] = Route(
            lastSuccessAt = now,
            lastMeasuredAt = if (measured) now else old.lastMeasuredAt,
            bps = if (!measured) old.bps else if (old.bps > 0) old.bps * 0.65 + bps * 0.35 else bps
        )
    }

    /** The speed of a transfer that was cut off: a measurement, not a success. */
    @Synchronized
    fun sample(url: String, bps: Double) {
        if (bps <= 0) return
        val old = measurement(url) ?: Route()
        routes[routeOf(url)] = Route(old.lastSuccessAt, clock(), if (old.bps > 0) old.bps * 0.65 + bps * 0.35 else bps)
    }

    @Synchronized
    fun failure(url: String, error: Throwable?, receivedBytes: Long = 0) {
        // A copy that lost a race was cut off, not broken; a timeout arrives as a BtrTransferException.
        if (error is kotlinx.coroutines.CancellationException) return
        bans?.record(url, receivedBytes, error)
        val failures = (health[url]?.failures ?: 0) + 1
        health[url] = (health[url] ?: Health()).copy(
            failures = failures,
            blockedUntil = clock() + minOf(60_000L, 3_000L * (1L shl minOf(failures, 4)))
        )
    }

    @Synchronized fun allows(url: String): Boolean = bans?.allows(url) ?: true

    /** Measured speed for weighting piece assignments; 0 when unknown or stale. */
    @Synchronized fun speed(url: String): Double = if (measuredNow(url, clock())) measurement(url)?.bps ?: 0.0 else 0.0

    @Synchronized
    fun status(): List<BtrNodeStatus> {
        val now = clock()
        return allUrls().map { url ->
            val item = health[url] ?: Health()
            val state = when {
                bans != null && !bans.allowsNode(url) -> "banned"
                item.blockedUntil > now -> "blocked"
                (measurement(url)?.lastSuccessAt ?: 0) > 0 -> "healthy"
                else -> "untested"
            }
            BtrNodeStatus(hostOf(url), state, measurement(url)?.bps ?: 0.0)
        }
    }

    companion object {
        const val MEASUREMENT_TTL_MS = 90_000L
    }
}
