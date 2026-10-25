package com.android.purebilibili.btr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CdnResolverTest {
    private val signed = "https://cn-gdfs-ct-01-01.bilivideo.com/upgcxcode/1/2/3-1-30080.m4s?e=abc%3D&deadline=9&os=bcache"
    private val mirror = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/1/2/3-1-30080.m4s?e=abc%3D&deadline=9&os=hwbv"

    @Test
    fun mainlandModeCopiesTheSignatureToEveryMainlandNode() {
        val urls = btrRepresentationUrls(listOf(signed, mirror), BtrCdnMode.MAINLAND)
        // Bilibili's own mainland address first, then every node with the donor's exact query.
        assertEquals(mirror, urls.first())
        val synthetic = urls.drop(1)
        assertEquals(BtrCdnHosts.MAINLAND.toSet(), urls.map(::hostOf).toSet())
        assertTrue(synthetic.filter { hostOf(it) != hostOf(mirror) }.all { addressOf(it) == addressOf(signed) })
    }

    @Test
    fun overseasModeLeavesMainlandOriginalsOut() {
        val urls = btrRepresentationUrls(listOf(signed, mirror), BtrCdnMode.OVERSEAS)
        assertEquals(signed, urls.first())
        assertFalse(urls.any { hostOf(it) in BtrCdnHosts.MAINLAND })
        assertTrue(urls.map(::hostOf).containsAll(BtrCdnHosts.OVERSEAS))
    }

    @Test
    fun akamaiAddressesDonateOnlyWhenNothingElseExists() {
        val akamai = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/x.m4s?hdnts=1"
        assertEquals(BtrCdnHosts.MAINLAND.size, btrRepresentationUrls(listOf(akamai), BtrCdnMode.MAINLAND).size)
        assertFalse(btrRepresentationUrls(listOf(signed, akamai), BtrCdnMode.MAINLAND).any { addressOf(it) == addressOf(akamai) })
    }

    @Test
    fun customModeUsesOnlyThePickedServers() {
        val urls = btrRepresentationUrls(listOf(signed), BtrCdnMode.CUSTOM, listOf("upos-sz-mirrorcos.bilivideo.com", "x.example.com"))
        assertEquals(listOf("upos-sz-mirrorcos.bilivideo.com"), urls.map(::hostOf))
    }

    @Test
    fun aRefusingNodeIsBannedOnlyWhereOthersServeTheAddress() {
        val bans = BtrBanList()
        val good = "https://a.bilivideo.com/v.m4s?s=1"
        val refusing = "https://b.bilivideo.com/v.m4s?s=1"
        bans.success(good)
        repeat(2) { bans.record(refusing, 0, BtrTransferException("403", status = 403)) }
        assertFalse(bans.allows(refusing))
        assertTrue(bans.allows(good))
        assertEquals(listOf("b.bilivideo.com"), bans.bannedHosts())
    }

    @Test
    fun anAddressEveryNodeRefusesIsDroppedNotTheNodes() {
        val bans = BtrBanList()
        bans.success("https://a.bilivideo.com/other.m4s?s=2")
        val refused = "https://a.bilivideo.com/v.m4s?s=1"
        repeat(2) { bans.record(refused, 0, BtrTransferException("403", status = 403)) }
        assertFalse(bans.allows(refused))
        assertTrue(bans.allowsNode(refused))
    }

    @Test
    fun aTransferThatSentBytesIsNotAStrike() {
        val bans = BtrBanList()
        repeat(3) { bans.record("https://a.bilivideo.com/v.m4s", 1024, BtrTransferException("stall", timeout = true)) }
        assertTrue(bans.allows("https://a.bilivideo.com/v.m4s"))
    }

    @Test
    fun failedNodesBackOffAndMeasuredNodesLead() {
        var now = 1_000_000L
        val resolver = BtrResolver(listOf(signed), { BtrCdnMode.MAINLAND }, clock = { now })
        val urls = resolver.urls()
        resolver.success(urls[2], 5_000_000.0)
        resolver.failure(urls[0], BtrTransferException("down"))
        assertEquals(urls[2], resolver.rescueCandidates().first())
        assertFalse(urls[0] in resolver.rescueCandidates())
        now += 60_001
        assertTrue(urls[0] in resolver.rescueCandidates())
        // A measurement older than its lifetime no longer weights assignments.
        now += BtrResolver.MEASUREMENT_TTL_MS
        assertEquals(0.0, resolver.speed(urls[2]))
    }

    @Test
    fun afterWarmupUnmeasuredNodesFillTheFreePlaces() {
        val now = 1_000L
        val resolver = BtrResolver(listOf(signed), { BtrCdnMode.MAINLAND }, clock = { now })
        val first = resolver.rangeCandidates()
        assertEquals(BtrCdnHosts.MAINLAND.size, first.size)
        first.take(3).forEach { resolver.success(it, 4_000_000.0) }
        // Six places: the three measured nodes, then unmeasured ones to be tried.
        val next = resolver.rangeCandidates()
        assertEquals(6, next.size)
        assertEquals(first.take(3).toSet(), next.take(3).toSet())
        assertEquals(3, next.count { resolver.speed(it) == 0.0 })
        // Once enough nodes are measured, exactly one unmeasured node rides along.
        next.drop(3).forEach { resolver.success(it, 2_000_000.0) }
        assertEquals(1, resolver.rangeCandidates().count { resolver.speed(it) == 0.0 })
    }

    @Test
    fun aNewVideoStartsFromSpeedsMeasuredForTheLastOne() {
        var now = 1_000_000L
        val stats = BtrNodeStats { now }
        val first = BtrResolver(listOf(signed), { BtrCdnMode.MAINLAND }, clock = { now }, nodeStats = stats)
        val fast = first.urls().first { hostOf(it) == "upos-sz-mirrorcos.bilivideo.com" }
        val slow = first.urls().first { hostOf(it) == "upos-sz-mirrorbd.bilivideo.com" }
        first.success(fast, 8_000_000.0)
        first.success(slow, 1_000_000.0)

        now += 60_000
        val nextVideo = "https://cn-gdfs-ct-01-01.bilivideo.com/upgcxcode/9/9/9-1-30080.m4s?e=other"
        val second = BtrResolver(listOf(nextVideo), { BtrCdnMode.MAINLAND }, clock = { now }, nodeStats = stats)
        val candidates = second.rangeCandidates()
        // No warm-up over every node: the known fast node leads and the list is the usual width.
        assertEquals("upos-sz-mirrorcos.bilivideo.com", hostOf(candidates.first()))
        assertEquals(6, candidates.size)
        assertTrue(second.speed(candidates.first()) > 0)
        assertEquals("upos-sz-mirrorcos.bilivideo.com", hostOf(second.startupCandidates().first()))

        // A prior goes stale after its lifetime.
        now += BtrNodeStats.PRIOR_TTL_MS
        assertEquals(0.0, second.speed(candidates.first()))
    }
}
