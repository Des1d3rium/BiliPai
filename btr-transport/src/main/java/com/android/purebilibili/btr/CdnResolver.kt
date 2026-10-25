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

/** The `deadline` query parameter of a Bilibili signed address (epoch seconds), when present. */
fun signedUrlDeadline(url: String): Long? = runCatching {
    URI(url).rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
        part.takeIf { it.startsWith("deadline=") }?.removePrefix("deadline=")?.toLongOrNull()
    }
}.getOrNull()?.takeIf { it > 0 }

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
 * A node that twice fails without sending a byte is skipped. A 4xx blames the node or the address
 * depending on which of them has delivered elsewhere. Strikes count for [BAN_TTL_MS], so one list
 * can serve every video: a node banned for the last video stays banned for the next, and gets
 * another chance once its strikes have aged out.
 */
class BtrBanList(
    private val limit: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onBan: (String, String) -> Unit = { _, _ -> }
) {
    private val emptyReplies = HashMap<Triple<String, String, Boolean>, MutableList<Long>>()
    private var judgedAt = Long.MIN_VALUE
    private val goodNodes = HashSet<String>()
    private val goodAddresses = HashSet<String>()
    private val reported = HashSet<String>()
    private var banned = emptySet<String>()

    @Synchronized
    private fun judge(url: String?): Boolean {
        val now = clock()
        judgedAt = now
        emptyReplies.values.forEach { times -> times.removeAll { now - it >= BAN_TTL_MS } }
        emptyReplies.entries.removeAll { it.value.isEmpty() }
        val strikes = HashMap<String, Int>()
        for ((key, times) in emptyReplies) {
            val count = times.size
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
            runCatching { onBan(if (isNode) key.removePrefix("node:") else url?.let(::hostOf).orEmpty(), if (isNode) "node" else "address") }
        }
        // A ban that aged out may be reported again if the node keeps failing.
        reported.retainAll(banned)
        return added
    }

    // Bans expire with their strikes; look again now and then rather than on every request.
    private fun refreshIfStale() {
        if (clock() - judgedAt >= REJUDGE_MS) judge(null)
    }

    @Synchronized
    fun record(url: String, receivedBytes: Long, error: Throwable?): Boolean {
        if (error is kotlinx.coroutines.CancellationException || receivedBytes > 0) return false
        val node = hostOf(url).ifEmpty { return false }
        val status = (error as? BtrTransferException)?.status ?: 0
        val key = Triple(node, addressOf(url), status in 400..499)
        emptyReplies.getOrPut(key) { ArrayList() } += clock()
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
    fun allows(url: String): Boolean = refreshIfStale().let { "node:${hostOf(url)}" !in banned } && "address:${addressOf(url)}" !in banned &&
        "pair:${hostOf(url)} ${addressOf(url)}" !in banned

    @Synchronized
    fun allowsNode(url: String): Boolean = refreshIfStale().let { "node:${hostOf(url)}" !in banned }

    @Synchronized
    fun bannedHosts(): List<String> = refreshIfStale().let { banned }.filter { it.startsWith("node:") }.map { it.removePrefix("node:") }

    @Synchronized
    fun reset() {
        emptyReplies.clear(); goodNodes.clear(); goodAddresses.clear(); reported.clear(); banned = emptySet()
    }

    companion object {
        const val BAN_TTL_MS = 10 * 60_000L
        private const val REJUDGE_MS = 15_000L
    }
}

data class BtrNodeStatus(val host: String, val state: String, val bps: Double)

/**
 * What each node delivered recently, kept across videos. A new video starts from these instead of
 * probing every node again; a track's own fresher measurements still come first.
 */
class BtrNodeStats(private val clock: () -> Long = System::currentTimeMillis) {
    /** [measuredAt] is null until a transfer long enough to give a speed. */
    data class Sample(val bps: Double, val measuredAt: Long?, val lastSuccessAt: Long)

    private val nodes = HashMap<String, Sample>()

    /** Bumped on every change, so a saver writes only when something new was measured. */
    @Volatile var version = 0L
        private set

    @Synchronized
    fun record(host: String, bps: Double, success: Boolean) {
        if (host.isEmpty()) return
        version += 1
        val now = clock()
        val old = nodes[host]
        val measured = bps > 0
        nodes[host] = Sample(
            bps = if (!measured) old?.bps ?: 0.0 else old?.bps?.takeIf { it > 0 }?.let { it * 0.65 + bps * 0.35 } ?: bps,
            measuredAt = if (measured) now else old?.measuredAt,
            lastSuccessAt = if (success) now else old?.lastSuccessAt ?: 0
        )
    }

    /** A sample measured within [PRIOR_TTL_MS]; null otherwise. */
    @Synchronized
    fun get(host: String): Sample? = nodes[host]?.takeIf { sample ->
        sample.measuredAt?.let { clock() - it < PRIOR_TTL_MS } == true
    }

    @Synchronized fun clear() { nodes.clear(); version += 1 }

    /** One node per line: host, speed, measured and last-success times. Hosts only, never URLs. */
    @Synchronized
    fun encode(): String {
        val now = clock()
        return nodes.filterValues { sample -> sample.measuredAt?.let { now - it < PRIOR_TTL_MS } == true }
            .entries.joinToString("\n") { (host, s) -> "$host\t${s.bps}\t${s.measuredAt}\t${s.lastSuccessAt}" }
    }

    /** Restores what [encode] wrote; lines that do not parse, or are no longer fresh, are skipped. */
    @Synchronized
    fun restore(text: String) {
        val now = clock()
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 4) continue
            val host = normalizeBtrCdnHost(parts[0]).takeIf { it == parts[0] } ?: continue
            val bps = parts[1].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            val measuredAt = parts[2].toLongOrNull()?.takeIf { now - it in 0 until PRIOR_TTL_MS } ?: continue
            val lastSuccessAt = parts[3].toLongOrNull() ?: 0
            // A newer in-memory measurement wins over the saved one.
            if ((nodes[host]?.measuredAt ?: Long.MIN_VALUE) < measuredAt) nodes[host] = Sample(bps, measuredAt, lastSuccessAt)
        }
    }

    companion object {
        // A node fast within the last hour, also before the app restarted, is the best first guess;
        // a track's own fresh measurement replaces it after the first transfer.
        const val PRIOR_TTL_MS = 60 * 60_000L
    }
}

/**
 * Per-track node selection. A measured speed counts for [MEASUREMENT_TTL_MS]; a node left out for
 * being slow then becomes untested again and gets another try through the exploration slot.
 */
class BtrResolver(
    originalUrls: List<String>,
    private val mode: () -> BtrCdnMode,
    private val customHosts: () -> List<String> = { emptyList() },
    private val bans: BtrBanList? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nodeStats: BtrNodeStats? = null
) {
    @Volatile private var originalUrls: List<String> = originalUrls

    /**
     * New signatures for the same files (a refreshed playurl). Measurements are kept per node and
     * file, without the query, so they carry over; the old addresses' failures do not.
     */
    @Synchronized
    fun updateOriginals(urls: List<String>): Boolean {
        val next = urls.mapNotNull(::safeMediaUrl).distinct()
        if (next.isEmpty() || next == originalUrls) return false
        originalUrls = next
        return true
    }

    /** The earliest signature deadline among this track's addresses, in epoch seconds; null if none. */
    @Synchronized
    fun signatureDeadline(): Long? = originalUrls.mapNotNull(::signedUrlDeadline).minOrNull()

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

    private fun ownMeasurement(url: String): Route? = routes[routeOf(url)]
    private fun ownFresh(route: Route?, now: Long): Boolean =
        route != null && route.lastMeasuredAt > 0 && now - route.lastMeasuredAt < MEASUREMENT_TTL_MS

    // This track's own measurement while fresh, else what the node delivered for earlier videos.
    private fun measurement(url: String): Route? {
        val own = ownMeasurement(url)
        if (ownFresh(own, clock())) return own
        val prior = nodeStats?.get(hostOf(url)) ?: return own
        return Route(maxOf(own?.lastSuccessAt ?: 0, prior.lastSuccessAt), prior.measuredAt ?: 0, prior.bps)
    }
    private fun measuredNow(url: String, now: Long): Boolean =
        ownFresh(ownMeasurement(url), now) || nodeStats?.get(hostOf(url)) != null
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
        // Nodes measured for earlier videos make the warm-up unnecessary.
        val known = pool.count { measuredNow(it, now) } >= 2
        val width = minOf(if (mediaRangeCount == 0 && !known) pool.size else 6, pool.size)
        val warmupRanges = if (mode() == BtrCdnMode.MAINLAND) 1 else 4
        val selected: MutableList<String>
        if (mediaRangeCount < warmupRanges && !known) {
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
        // Fastest known first; with nothing measured, Bilibili's own addresses lead.
        return unbanned((originals + allUrls()).distinct()).filter { open(it, now) }
            .sortedByDescending { if (measuredNow(it, now)) measurement(it)?.bps ?: 0.0 else 0.0 }.take(8)
    }

    @Synchronized
    fun rescueCandidates(): List<String> {
        val now = clock()
        return urls().filter { open(it, now) }.sortedWith(bySpeed)
    }

    @Synchronized
    fun success(url: String, bps: Double) {
        bans?.success(url)
        nodeStats?.record(hostOf(url), bps, success = true)
        val old = ownMeasurement(url) ?: Route()
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
        nodeStats?.record(hostOf(url), bps, success = false)
        val old = ownMeasurement(url) ?: Route()
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
