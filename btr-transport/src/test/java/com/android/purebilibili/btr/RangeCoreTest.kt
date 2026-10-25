package com.android.purebilibili.btr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeCoreTest {
    @Test
    fun splitSpreadsTheRemainderOverTheFirstPieces() {
        val pieces = splitBtrRange(100, 100 + 10 * 128 * 1024 + 2, 4, 128L * 1024)
        assertEquals(4, pieces.size)
        assertEquals(100, pieces.first().start)
        assertEquals(100L + 10 * 128 * 1024 + 2, pieces.last().end)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(pieces[0].length, pieces[3].length + 1)
    }

    @Test
    fun splitNeverGoesBelowTheMinimumChunk() {
        assertEquals(2, splitBtrRange(0, 200 * 1024 - 1, 16, 128L * 1024).size)
        assertEquals(1, splitBtrRange(0, 10, 16).size)
        // The floor is 32 KiB whatever the caller asks for.
        assertEquals(4, splitBtrRange(0, 128 * 1024 - 1, 64, 1).size)
    }

    @Test
    fun contentRangeIsValidated() {
        assertEquals(BtrContentRange(0, 9, 100), parseBtrContentRange("bytes 0-9/100"))
        assertEquals(BtrContentRange(5, 9, null), parseBtrContentRange("bytes 5-9/*"))
        assertNull(parseBtrContentRange("bytes 9-5/100"))
        assertNull(parseBtrContentRange("bytes 0-9/9"))
        assertNull(parseBtrContentRange(null))
    }

    @Test
    fun onlyBilibiliMediaHostsAreAccepted() {
        assertTrue(isBilibiliMediaUrl("https://upos-sz-mirrorali.bilivideo.com/a/b.m4s?x=1"))
        assertTrue(isBilibiliMediaUrl("https://upos-hz-mirrorakam.akamaized.net/a.m4s"))
        assertFalse(isBilibiliMediaUrl("http://upos-sz-mirrorali.bilivideo.com/a.m4s"))
        assertFalse(isBilibiliMediaUrl("https://example.com/a.m4s"))
        assertEquals("upos-sz-mirrorcos.bilivideo.com", normalizeBtrCdnHost(" https://UPOS-SZ-MIRRORCOS.bilivideo.com/x "))
        assertEquals("", normalizeBtrCdnHost("evil.example.com"))
    }

    @Test
    fun settingsKeepOnlyAllowedThreadCounts() {
        assertEquals(8, BtrSettings(concurrency = 7).normalized().concurrency)
        assertEquals(128, BtrSettings(concurrency = 128).normalized().concurrency)
    }
}
