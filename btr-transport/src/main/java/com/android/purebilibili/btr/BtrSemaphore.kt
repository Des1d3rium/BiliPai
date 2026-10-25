package com.android.purebilibili.btr

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

// Queue classes, served in this order: the startup probe and init/index, then startup pieces,
// then everything else. A class never waits behind a lower one.
internal const val QUEUE_CRITICAL = 0
internal const val QUEUE_STARTUP = 1
internal const val QUEUE_ORDINARY = 2

// Priority an ordinary request with a playback deadline gains per millisecond of waiting:
// 20 (a hedge copy's boost) in 0.9 s.
private const val QUEUE_AGING_PER_MS = 20.0 / 900.0

fun interface BtrRelease {
    fun release()
}

/** Port of BTR's Semaphore: a connection budget whose queue is ordered by class, priority and deadline. */
class BtrSemaphore(limit: Int, private val clock: () -> Double) {
    private class Entry(
        val priority: Double,
        val deadlineAt: Double,
        val deadline: (() -> Double)?,
        val queueClass: Int,
        val ages: Boolean,
        val queuedAt: Double,
        val sequence: Long,
        val grant: (BtrRelease) -> Boolean
    )

    @Volatile var limit: Int = limit.coerceIn(1, 512)
        private set
    @Volatile var active: Int = 0
        private set
    private val queue = ArrayList<Entry>()
    private var sequence = 0L
    var onChange: ((active: Int, limit: Int, queued: Int) -> Unit)? = null

    fun setLimit(value: Int) {
        synchronized(this) { limit = value.coerceIn(1, 512) }
        drain()
    }

    val queued: Int get() = synchronized(this) { queue.size }

    private fun urgency(entry: Entry, now: Double): Int {
        // Read again at every pass: the deadline moves with the playhead and the playback rate.
        val deadlineAt = entry.deadline?.invoke() ?: entry.deadlineAt
        if (!deadlineAt.isFinite()) return 0
        val remaining = deadlineAt - now
        return when {
            remaining <= 0 -> 12
            remaining <= 750 -> 9
            remaining <= 2000 -> 6
            else -> 0
        }
    }

    private fun drain() {
        while (true) {
            val (entry, release) = synchronized(this) {
                if (active >= limit || queue.isEmpty()) return@synchronized null
                val now = clock()
                // A bounded, recomputed boost keeps overdue primaries from sitting behind prefetch
                // work; aging keeps an old request from being passed over again and again.
                fun rank(item: Entry) = item.priority + urgency(item, now) +
                    if (item.ages) (now - item.queuedAt) * QUEUE_AGING_PER_MS else 0.0
                queue.sortWith(
                    compareBy<Entry> { it.queueClass }.thenByDescending { rank(it) }.thenBy { it.sequence }
                )
                val next = queue.removeAt(0)
                active += 1
                val released = AtomicBoolean(false)
                next to BtrRelease {
                    if (released.compareAndSet(false, true)) {
                        synchronized(this) { active = maxOf(0, active - 1) }
                        drain()
                    }
                }
            } ?: break
            // A request canceled while it was being granted gives its connection straight back.
            if (!entry.grant(release)) release.release()
        }
        onChange?.invoke(active, limit, queued)
    }

    suspend fun acquire(
        priority: Double = 0.0,
        deadlineAt: Double = Double.POSITIVE_INFINITY,
        queueClass: Int = QUEUE_CRITICAL,
        ages: Boolean = false,
        deadline: (() -> Double)? = null
    ): BtrRelease = suspendCancellableCoroutine { continuation ->
        lateinit var entry: Entry
        entry = Entry(
            priority, if (deadlineAt.isFinite()) deadlineAt else Double.POSITIVE_INFINITY, deadline,
            queueClass, ages, clock(), synchronized(this) { sequence++ }
        ) { release ->
            if (!continuation.isActive) false else {
                continuation.resume(release) { _, granted, _ -> granted.release() }
                true
            }
        }
        continuation.invokeOnCancellation { synchronized(this) { queue.remove(entry) } }
        synchronized(this) { queue += entry }
        drain()
    }
}
