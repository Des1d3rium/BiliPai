package com.android.purebilibili.btr

/**
 * BTR 自动线程数. One controller for the process: the thread count starts at 8 and climbs a ladder
 * towards 32 whenever the download does not keep up with playback (a stall; a low buffer that
 * stops growing while bytes keep arriving; a connection that waits too long for its first byte
 * while every slot is busy). Every step up is a ten-second trial: without more bytes per second
 * it is taken back and that level rests. 412/429 step it back and cap the climb for a while.
 *
 * Each playback session opens with at least 16 threads and steps back to the earlier level once
 * the buffer is well ahead.
 */
class BtrAutoConcurrency(private val clock: () -> Double) {
    private data class Rest(val until: Double, val hard: Boolean)
    private data class Trial(val from: Int, val level: Int, val at: Double, val baseline: Double, var stalled: Boolean)
    private data class Bucket(val at: Double, var bytes: Long)
    private data class Span(val from: Double, val to: Double)
    private data class Ahead(val at: Double, val ahead: Double)
    private data class Startup(val base: Int, val until: Double)

    data class Status(
        val threads: Int,
        val reason: String,
        val throughputBps: Long,
        val saturation: Double,
        val trialFrom: Int?,
        val startupBase: Int?
    )

    private val listeners = LinkedHashSet<(Int) -> Unit>()
    private var level = 0
    private var changedAt = 0.0
    private var reason = "起步"
    private var trial: Trial? = null
    private val resting = HashMap<Int, Rest>()
    private val buckets = ArrayDeque<Bucket>()
    private var lastActivityAt = Double.NEGATIVE_INFINITY
    private var saturated = false
    private var saturatedSince = 0.0
    private val saturatedSpans = ArrayList<Span>()
    private val aheadSamples = ArrayDeque<Ahead>()
    private var pressureSince = 0.0
    private var startup: Startup? = null

    @Synchronized fun threads(): Int = LADDER[level]

    @Synchronized fun subscribe(listener: (Int) -> Unit): () -> Unit {
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }

    private fun pruneBuckets(at: Double) {
        while (buckets.isNotEmpty() && at - buckets.first().at > WINDOW_MS) buckets.removeFirst()
    }

    // Bytes per second from completed pieces only: a hedge copy that lost its race is not delivery.
    private fun throughput(at: Double): Double {
        pruneBuckets(at)
        if (buckets.isEmpty()) return 0.0
        val bytes = buckets.sumOf { it.bytes }
        return bytes * 1000.0 / maxOf(1000.0, buckets.last().at - buckets.first().at + BUCKET_MS)
    }

    // The share of the window during which every slot was busy and pieces were queued.
    private fun saturation(at: Double): Double {
        val from = at - WINDOW_MS
        saturatedSpans.removeAll { it.to <= from }
        var busy = saturatedSpans.sumOf { maxOf(0.0, it.to - maxOf(it.from, from)) }
        if (saturated) busy += maxOf(0.0, at - maxOf(saturatedSince, from))
        return minOf(1.0, busy / WINDOW_MS)
    }

    private fun resting(levelIndex: Int, at: Double): Rest? {
        val rest = resting[levelIndex] ?: return null
        if (rest.until <= at) { resting.remove(levelIndex); return null }
        return rest
    }

    private fun setLevel(next: Int, why: String, newTrial: Trial?) {
        level = next
        changedAt = clock()
        reason = why
        trial = newTrial
        pressureSince = 0.0
        val threads = LADDER[level]
        listeners.toList().forEach { runCatching { it(threads) } }
    }

    // A level resting after a refusal caps the climb; one resting after a fruitless trial is
    // skipped only by a strong signal (a stall), not by pressure.
    private fun stepUp(why: String, strong: Boolean): Boolean {
        val at = clock()
        if (at - changedAt < STEP_COOLDOWN_MS) return false
        if (resting(level, at)?.hard == true) return false
        var next = level + 1
        while (next < LADDER.size) {
            val rest = resting(next, at) ?: break
            if (rest.hard || !strong) return false
            next += 1
        }
        if (next >= LADDER.size) return false
        setLevel(next, why, Trial(level, next, at, throughput(at), false))
        // Not keeping up even with the start's threads: the start ends at this level.
        startup = null
        return true
    }

    // A start may go up to 16 threads, but not onto or past a level the server refused.
    private fun startupLevel(at: Double): Int {
        if (resting(level, at)?.hard == true) return level
        var target = level
        while (target < STARTUP_LEVEL && resting(target + 1, at)?.hard != true) target += 1
        return target
    }

    private fun stepDown(target: Int, restLevel: Int, why: String, restMs: Double, hard: Boolean): Boolean {
        resting[restLevel] = Rest(clock() + restMs, hard)
        if (target >= level) return false
        setLevel(target, why, null)
        return true
    }

    // Only judged when the connections were busy meanwhile; an idle download proves nothing.
    private fun judgeTrial(at: Double) {
        val current = trial ?: return
        if (at - current.at < TRIAL_MS) return
        trial = null
        if (current.stalled || saturation(at) < 0.6 || current.baseline <= 0) return
        if (throughput(at) < current.baseline) {
            stepDown(current.from, current.level, "${LADDER[current.level]} 线程没有比 ${LADDER[current.from]} 线程更快", REST_MS, false)
        }
    }

    /** A piece arrived whole. */
    @Synchronized fun delivered(bytes: Long, at: Double = clock()) {
        val last = buckets.lastOrNull()
        if (last != null && at - last.at < BUCKET_MS) last.bytes += bytes else buckets.addLast(Bucket(at, bytes))
        pruneBuckets(at)
        judgeTrial(at)
    }

    /** Bytes are flowing on some connection right now. */
    @Synchronized fun activity(at: Double = clock()) { lastActivityAt = at }

    /** The connections' state whenever it changes. */
    @Synchronized fun demand(active: Int, limit: Int, queued: Int, at: Double = clock()) {
        val now = active >= limit && queued > 0
        if (now == saturated) return
        if (saturated) saturatedSpans += Span(saturatedSince, at)
        saturated = now
        saturatedSince = at
        saturation(at)
    }

    /** The player stalled: more threads at once. */
    @Synchronized fun stall(why: String = "播放卡了一下"): Boolean {
        trial?.stalled = true
        return stepUp(why, true)
    }

    /**
     * The buffer ahead of the playhead, a few times a second while playing. A low buffer that has
     * not grown over the last second although bytes keep arriving means too few connections.
     */
    @Synchronized fun buffer(aheadSeconds: Double, playing: Boolean, at: Double = clock()): Boolean {
        aheadSamples.addLast(Ahead(at, aheadSeconds))
        while (aheadSamples.isNotEmpty() && at - aheadSamples.first().at > PRESSURE_MS + BUCKET_MS) aheadSamples.removeFirst()
        val start = startup
        if (start != null && at >= start.until) startup = null
        else if (start != null && aheadSeconds >= STARTUP_COMFORT_SECONDS && at - changedAt >= STEP_COOLDOWN_MS) {
            if (level > start.base) setLevel(level - 1, "开头已经跟上，线程数降到 ${LADDER[level - 1]}", null)
            if (level <= start.base) startup = null
        }
        val earlier = aheadSamples.firstOrNull { at - it.at >= PRESSURE_MS }
        val downloading = at - lastActivityAt < ACTIVITY_MS
        val pressed = playing && downloading && aheadSeconds < LOW_BUFFER_SECONDS && earlier != null &&
            aheadSeconds <= earlier.ahead + 0.05
        if (!pressed) { pressureSince = 0.0; return false }
        if (pressureSince == 0.0) { pressureSince = at; return false }
        if (at - pressureSince < PRESSURE_MS) return false
        pressureSince = 0.0
        return stepUp("缓冲跟不上播放", false)
    }

    /** A connection waited too long for its first byte while every slot was busy. */
    @Synchronized fun slow(): Boolean = if (saturation(clock()) >= 0.6) stepUp("连接排队等太久", false) else false

    /** The server refused the load: back one level, and nothing climbs past this one for a while. */
    @Synchronized fun pushback(status: Int): Boolean =
        stepDown(maxOf(0, level - 1), level, "服务器返回 $status", PUSHBACK_REST_MS, true)

    /** A new playback session: earlier buffer history means nothing, and the start runs wider. */
    @Synchronized fun newSession() {
        val at = clock()
        aheadSamples.clear()
        pressureSince = 0.0
        trial = null
        buckets.clear()
        saturatedSpans.clear()
        if (saturated) saturatedSince = at
        val base = startup?.base ?: level
        val target = startupLevel(at)
        if (target > level) setLevel(target, "开头先用 ${LADDER[target]} 线程", null)
        startup = if (level > base) Startup(base, at + STARTUP_MAX_MS) else null
    }

    @Synchronized fun status(): Status {
        val at = clock()
        return Status(
            LADDER[level], reason, throughput(at).toLong(), Math.round(saturation(at) * 100) / 100.0,
            trial?.let { LADDER[it.from] }, startup?.let { LADDER[it.base] }
        )
    }

    @Synchronized fun reset() {
        level = 0; changedAt = 0.0; reason = "起步"; trial = null; resting.clear(); buckets.clear()
        lastActivityAt = Double.NEGATIVE_INFINITY; saturated = false; saturatedSince = 0.0
        saturatedSpans.clear(); aheadSamples.clear(); pressureSince = 0.0; startup = null
    }

    companion object {
        val LADDER = listOf(8, 12, 16, 24, 32)
        private const val STARTUP_LEVEL = 2
        private const val STARTUP_COMFORT_SECONDS = 15.0
        private const val STARTUP_MAX_MS = 30_000.0
        private const val STEP_COOLDOWN_MS = 2_500.0
        private const val TRIAL_MS = 10_000.0
        private const val WINDOW_MS = 5_000.0
        private const val BUCKET_MS = 250.0
        private const val REST_MS = 90_000.0
        private const val PUSHBACK_REST_MS = 180_000.0
        private const val LOW_BUFFER_SECONDS = 6.0
        private const val PRESSURE_MS = 1_000.0
        private const val ACTIVITY_MS = 1_500.0
    }
}
