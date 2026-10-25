package com.android.purebilibili.btr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrAutoConcurrencyTest {
    private var now = 100_000.0
    private val auto = BtrAutoConcurrency { now }

    @Test
    fun aSessionStartsWithSixteenThreadsAndStepsBackOnceAhead() {
        auto.newSession()
        assertEquals(16, auto.threads())
        now += 3_000
        auto.buffer(20.0, playing = true)
        assertEquals(12, auto.threads())
        now += 3_000
        auto.buffer(20.0, playing = true)
        assertEquals(8, auto.threads())
    }

    @Test
    fun aLowBufferThatDoesNotGrowClimbsOneLevel() {
        now += 10_000
        auto.activity()
        auto.buffer(3.0, playing = true)
        now += 1_000; auto.activity(); auto.buffer(3.0, playing = true)
        now += 1_100; auto.activity()
        assertTrue(auto.buffer(3.0, playing = true))
        assertEquals(12, auto.threads())
    }

    @Test
    fun aFruitlessTrialIsTakenBackAndRests() {
        now += 10_000
        auto.delivered(4_000_000)
        assertTrue(auto.stall())
        assertEquals(12, auto.threads())
        auto.demand(active = 12, limit = 12, queued = 4)
        now += 10_500
        auto.delivered(1_000)
        assertEquals(8, auto.threads())
        // The rested level is skipped by pressure, only a stall may pass it.
        now += 3_000
        auto.activity()
        auto.buffer(2.0, true); now += 1_000; auto.activity(); auto.buffer(2.0, true); now += 1_100; auto.activity()
        assertFalse(auto.buffer(2.0, true))
        assertEquals(8, auto.threads())
    }

    @Test
    fun aRefusalStepsBackAndCapsTheClimb() {
        now += 10_000
        auto.stall()
        assertEquals(12, auto.threads())
        auto.pushback(429)
        assertEquals(8, auto.threads())
        now += 3_000
        assertFalse(auto.stall())
        now += 180_000
        assertTrue(auto.stall())
    }
}
