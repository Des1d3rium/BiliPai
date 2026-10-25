package com.android.purebilibili.btr

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val KIB = 1024L

/** A CDN node with a scripted speed and failure. Times are virtual. */
private class FakeNode(
    var status: Int = 206,
    var bytesPerTick: Int = 64 * 1024,
    var tickMs: Long = 10,
    var firstByteMs: Long = 20,
    var stallAfterBytes: Long = -1,
    var failAfterBytes: Long = -1,
    var failTimes: Int = Int.MAX_VALUE,
    var total: Long? = null
)

private class FakeFetcher(private val file: ByteArray, private val nodes: Map<String, FakeNode>) : BtrRangeFetcher {
    data class Request(val host: String, val start: Long, val end: Long)
    val requests = mutableListOf<Request>()

    override suspend fun fetch(url: String, start: Long, end: Long, onHeaders: () -> Unit, onChunk: (ByteArray) -> Unit): Long? {
        val host = hostOf(url)
        val node = nodes.getValue(host)
        requests += Request(host, start, end)
        delay(node.firstByteMs)
        if (node.status != 206) throw BtrTransferException("Range 校验失败：HTTP ${node.status}", status = node.status)
        onHeaders()
        var position = start
        var sent = 0L
        while (position <= end) {
            if (node.stallAfterBytes in 0..sent) awaitCancellation()
            if (node.failAfterBytes in 0..sent && node.failTimes > 0) {
                node.failTimes -= 1
                throw BtrTransferException("connection reset")
            }
            delay(node.tickMs)
            val count = minOf(node.bytesPerTick.toLong(), end - position + 1).toInt()
            onChunk(file.copyOfRange(position.toInt(), position.toInt() + count))
            position += count
            sent += count
        }
        return node.total ?: file.size.toLong()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BtrDownloaderTest {
    private val file = Random(7).nextBytes(4 * 1024 * 1024)
    private val hosts = listOf("upos-sz-mirrorali.bilivideo.com", "upos-sz-mirrorcos.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com")
    private val original = "https://${hosts[0]}/upgcxcode/1/2/3-1-30080.m4s?e=sig&os=ali"

    private class Harness(val downloader: BtrDownloader, val fetcher: FakeFetcher, val resolver: BtrResolver, val bans: BtrBanList)

    private fun TestScope.harness(
        nodes: Map<String, FakeNode>,
        concurrency: Int = 8,
        customHosts: List<String> = nodes.keys.toList()
    ): Harness {
        val fetcher = FakeFetcher(file, nodes)
        val clock = { testScheduler.currentTime.toDouble() }
        val settings = BtrSettings(mode = BtrCdnMode.CUSTOM, customHosts = customHosts, concurrency = concurrency)
        val downloader = BtrDownloader(fetcher, { settings }, BtrAutoConcurrency(clock), clock)
        val bans = BtrBanList()
        val resolver = BtrResolver(listOf(original), { BtrCdnMode.CUSTOM }, { customHosts }, bans, clock = { testScheduler.currentTime })
        return Harness(downloader, fetcher, resolver, bans)
    }

    private fun range(start: Long, length: Long) = BtrPiece(0, start, start + length - 1)

    private fun bytes(start: Long, length: Long) = file.copyOfRange(start.toInt(), (start + length).toInt())

    @Test
    fun aRangeIsSplitOverNodesAndReassembledInOrder() = runTest {
        val h = harness(hosts.associateWith { FakeNode() })
        val result = h.downloader.downloadRange(range(100, 2 * 1024 * KIB), h.resolver)
        assertContentEquals(bytes(100, 2 * 1024 * KIB), result.bytes)
        assertTrue(result.pieceCount > 1)
        assertTrue(result.hosts.size > 1, "pieces should spread over nodes: ${result.hosts}")
    }

    @Test
    fun orderedChunksFollowEachOtherExactly() = runTest {
        val nodes = hosts.associateWith { FakeNode() }
        // A slow node finishes its piece last; the pieces after it must still wait for it.
        nodes.getValue(hosts[0]).tickMs = 60
        val h = harness(nodes)
        val delivered = mutableListOf<BtrPiece>()
        val out = java.io.ByteArrayOutputStream()
        h.downloader.downloadRange(range(0, 1024 * KIB), h.resolver, BtrRangeOptions(onOrderedChunk = { chunk, piece, _ ->
            delivered += piece
            out.write(chunk)
        }))
        delivered.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertContentEquals(bytes(0, 1024 * KIB), out.toByteArray())
    }

    @Test
    fun aStalledNodeIsCutOffAndThePieceFinishesElsewhere() = runTest {
        val nodes = hosts.associateWith { FakeNode() }
        nodes.getValue(hosts[1]).stallAfterBytes = 16 * KIB
        val h = harness(nodes)
        val result = h.downloader.downloadRange(range(0, 1024 * KIB), h.resolver)
        assertContentEquals(bytes(0, 1024 * KIB), result.bytes)
        // Rescued by the hedge copy long before the four-second stall timer.
        assertTrue(testScheduler.currentTime < 4_000, "took ${testScheduler.currentTime} ms")
    }

    @Test
    fun aFailedCopyIsResumedFromWhatItAlreadyReceived() = runTest {
        val nodes = hosts.associateWith { FakeNode(bytesPerTick = 16 * 1024) }
        nodes.getValue(hosts[0]).apply { failAfterBytes = 48 * KIB; failTimes = 1 }
        val h = harness(nodes, concurrency = 4)
        val result = h.downloader.downloadRange(range(0, 512 * KIB), h.resolver)
        assertContentEquals(bytes(0, 512 * KIB), result.bytes)
        val failed = h.fetcher.requests.first { it.host == hosts[0] }
        assertTrue(
            h.fetcher.requests.any { it.start == failed.start + 48 * KIB && it.end == failed.end },
            "expected a resumed request for ${failed.start + 48 * KIB}-${failed.end}: ${h.fetcher.requests}"
        )
    }

    @Test
    fun aRefusingNodeIsAvoidedAndThenBanned() = runTest {
        val nodes = hosts.associateWith { FakeNode() }
        nodes.getValue(hosts[2]).status = 403
        val h = harness(nodes)
        repeat(3) {
            val result = h.downloader.downloadRange(range(it * 1024 * KIB, 1024 * KIB), h.resolver)
            assertContentEquals(bytes(it * 1024 * KIB, 1024 * KIB), result.bytes)
        }
        assertEquals(listOf(hosts[2]), h.bans.bannedHosts())
    }

    @Test
    fun mirrorsThatDisagreeOnTheFileLengthAreRejected() = runTest {
        val nodes = hosts.take(2).associateWith { FakeNode() }
        nodes.getValue(hosts[1]).total = file.size + 1L
        val h = harness(nodes)
        assertFailsWith<BtrTransferException> { h.downloader.downloadRange(range(0, 1024 * KIB), h.resolver) }
    }

    @Test
    fun aPieceThatWillMissItsDeadlineGetsACopyOnAnotherNode() = runTest {
        val nodes = hosts.take(2).associateWith { FakeNode() }
        // 10 KiB/s: a 128 KiB piece would take over twelve seconds here.
        nodes.getValue(hosts[0]).apply { bytesPerTick = 1024; tickMs = 100 }
        val h = harness(nodes, concurrency = 4)
        val deadlineAt = testScheduler.currentTime + 2_000.0
        val result = h.downloader.downloadRange(range(0, 512 * KIB), h.resolver, BtrRangeOptions(deadline = { deadlineAt }))
        assertContentEquals(bytes(0, 512 * KIB), result.bytes)
        assertTrue(testScheduler.currentTime < 4_000, "took ${testScheduler.currentTime} ms")
    }

    @Test
    fun aStartProbesTheHeadOnEveryCandidateFirst() = runTest {
        val h = harness(hosts.associateWith { FakeNode() })
        val out = java.io.ByteArrayOutputStream()
        h.downloader.downloadRange(range(0, 1024 * KIB), h.resolver, BtrRangeOptions(startup = true, onOrderedChunk = { chunk, _, _ ->
            out.write(chunk)
        }))
        assertContentEquals(bytes(0, 1024 * KIB), out.toByteArray())
        val head = h.fetcher.requests.takeWhile { it.start == 0L }
        assertEquals(64 * KIB - 1, head.first().end)
        assertEquals(hosts.size, head.map { it.host }.distinct().size)
    }

    @Test
    fun initAndIndexAreRacedNotSplit() = runTest {
        val nodes = hosts.associateWith { FakeNode() }
        nodes.getValue(hosts[0]).firstByteMs = 5_000
        val h = harness(nodes)
        val result = h.downloader.downloadRange(range(0, 2 * KIB), h.resolver, BtrRangeOptions(kind = BtrKind.META))
        assertContentEquals(bytes(0, 2 * KIB), result.bytes)
        assertEquals(1, result.pieceCount)
        assertTrue(testScheduler.currentTime < 1_000)
    }

    @Test
    fun aStartWithKnownNodesProbesOnlyTheFastestThree() = runTest {
        val mainland = BtrCdnHosts.MAINLAND.take(6)
        val fetcher = FakeFetcher(file, mainland.associateWith { FakeNode() })
        val clock = { testScheduler.currentTime.toDouble() }
        val settings = BtrSettings(mode = BtrCdnMode.CUSTOM, customHosts = mainland, concurrency = 8)
        val downloader = BtrDownloader(fetcher, { settings }, BtrAutoConcurrency(clock), clock)
        val stats = BtrNodeStats { testScheduler.currentTime }
        listOf(mainland[3] to 9e6, mainland[1] to 7e6, mainland[4] to 5e6).forEach { (host, bps) ->
            stats.record(host, bps, success = true)
        }
        val resolver = BtrResolver(listOf(original), { BtrCdnMode.CUSTOM }, { mainland }, clock = { testScheduler.currentTime }, nodeStats = stats)
        val out = java.io.ByteArrayOutputStream()
        downloader.downloadRange(range(0, 1024 * KIB), resolver, BtrRangeOptions(startup = true, onOrderedChunk = { chunk, _, _ ->
            out.write(chunk)
        }))
        assertContentEquals(bytes(0, 1024 * KIB), out.toByteArray())
        val head = fetcher.requests.takeWhile { it.start == 0L }
        assertEquals(setOf(mainland[3], mainland[1], mainland[4]), head.map { it.host }.toSet())
    }
}
