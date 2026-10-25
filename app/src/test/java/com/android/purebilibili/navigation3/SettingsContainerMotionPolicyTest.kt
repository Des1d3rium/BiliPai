package com.android.purebilibili.navigation3

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsContainerMotionPolicyTest {
    private val source = Rect(16f, 220f, 384f, 300f)
    private val page = Rect(0f, 0f, 400f, 900f)

    @Test
    fun realtimeBlurOnlyAffectsCoveredBackgroundAndReversesWithNavigationDepth() {
        for (depth in listOf(-1f, -0.5f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, resolveSettingsContainerBlurProgress(depth))
        }
        assertEquals(1f, resolveSettingsContainerBlurProgress(1f))
        assertEquals(1f, resolveSettingsContainerBlurProgress(2f))
        val enter = (0..10).map { resolveSettingsContainerBlurProgress(it / 10f) }
        val leave = (10 downTo 0).map { resolveSettingsContainerBlurProgress(it / 10f) }
        assertEquals(enter.reversed(), leave)
        assertTrue(enter.zipWithNext().all { (previous, next) -> next >= previous })
    }

    @Test
    fun pushAndPopHaveExactRowAndPageEndpoints() {
        for (returning in listOf(false, true)) {
            val closed = resolveSettingsContainerFrame(source, page, -1f, returning)
            val open = resolveSettingsContainerFrame(source, page, 0f, returning)
            assertEquals(source, closed.bounds)
            assertEquals(0f, closed.contentAlpha)
            assertEquals(1f, closed.cornerFraction)
            assertEquals(page, open.bounds)
            assertEquals(1f, open.contentAlpha)
            assertEquals(0f, open.cornerFraction)
        }
    }

    @Test
    fun pushAndReturnUseDistinctContentFadeStages() {
        assertEquals(0f, resolveSettingsContainerFrame(source, page, -0.8f, false).contentAlpha, 0.0001f)
        assertEquals(0.5f, resolveSettingsContainerFrame(source, page, -0.7f, false).contentAlpha, 0.0001f)
        assertEquals(1f, resolveSettingsContainerFrame(source, page, -0.6f, false).contentAlpha, 0.0001f)
        assertEquals(0f, resolveSettingsContainerFrame(source, page, -0.4f, true).contentAlpha, 0.0001f)
        assertEquals(0.5f, resolveSettingsContainerFrame(source, page, -0.3f, true).contentAlpha, 0.0001f)
        assertEquals(1f, resolveSettingsContainerFrame(source, page, -0.2f, true).contentAlpha, 0.0001f)
    }

    @Test
    fun containerAlwaysGrowsAroundTheSourceWithoutOvershooting() {
        for (returning in listOf(false, true)) {
            var previous = source
            for (step in 0..100) {
                val frame = resolveSettingsContainerFrame(source, page, step / 100f - 1f, returning)
                assertTrue(frame.bounds.left <= previous.left + 0.001f)
                assertTrue(frame.bounds.top <= previous.top + 0.001f)
                assertTrue(frame.bounds.right >= previous.right - 0.001f)
                assertTrue(frame.bounds.bottom >= previous.bottom - 0.001f)
                assertTrue(frame.contentAlpha in 0f..1f)
                assertTrue(frame.cornerFraction in 0f..1f)
                previous = frame.bounds
            }
        }
    }

    @Test
    fun settleOvershootCannotEscapeTheRowOrPageBounds() {
        assertEquals(source, resolveSettingsContainerFrame(source, page, -1.2f, false).bounds)
        assertEquals(page, resolveSettingsContainerFrame(source, page, 0.2f, true).bounds)
    }

    @Test
    fun onlyTheCapturedParentBoundaryMayExpand() {
        val target = BiliPaiNavKey.AppearanceSettings
        val owner = BiliPaiNavKey.Settings
        val stack = listOf(BiliPaiNavKey.MainHost, owner, target)
        assertTrue(isSettingsContainerBoundary(true, stack, target, owner))
        assertFalse(isSettingsContainerBoundary(false, stack, target, owner))
        assertFalse(isSettingsContainerBoundary(true, stack, target, null))
        assertFalse(isSettingsContainerBoundary(true, listOf(owner, BiliPaiNavKey.SettingsSearch, target), target, owner))
        assertFalse(isSettingsContainerBoundary(true, listOf(target), target, owner))
        assertFalse(isSettingsContainerBoundary(true, listOf(owner), target, owner))
    }
}
