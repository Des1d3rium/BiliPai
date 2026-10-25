package com.android.purebilibili.btr

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OkHttpBtrRangeFetcherTest {
    private val server = MockWebServer()
    private val fetcher = OkHttpBtrRangeFetcher(OkHttpClient())

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.close()

    private fun fetch(start: Long, end: Long): Pair<ByteArray, Long?> = runBlocking {
        val out = java.io.ByteArrayOutputStream()
        val total = fetcher.fetch(server.url("/v.m4s").toString(), start, end, {}) { out.write(it) }
        out.toByteArray() to total
    }

    @Test
    fun anExactPartialResponseIsDelivered() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 10-14/100").body("hello").build())
        val (bytes, total) = fetch(10, 14)
        assertContentEquals("hello".toByteArray(), bytes)
        assertEquals(100, total)
        val request = server.takeRequest()
        assertEquals("bytes=10-14", request.headers["Range"])
        assertEquals("identity", request.headers["Accept-Encoding"])
        assertEquals("https://www.bilibili.com/", request.headers["Referer"])
    }

    @Test
    fun aFullResponseIsRefusedWithItsStatus() {
        server.enqueue(MockResponse.Builder().code(200).body("whole file").build())
        val error = assertFailsWith<BtrTransferException> { fetch(0, 4) }
        assertEquals(200, error.status)
    }

    @Test
    fun aDifferentRangeIsRefused() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-4/100").body("abcde").build())
        assertFailsWith<BtrTransferException> { fetch(5, 9) }
    }

    @Test
    fun aShortBodyIsRefused() {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-9/100").body("abc").build())
        assertFailsWith<Exception> { fetch(0, 9) }
    }
}
