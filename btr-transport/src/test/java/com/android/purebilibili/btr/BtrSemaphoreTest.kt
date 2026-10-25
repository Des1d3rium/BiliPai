package com.android.purebilibili.btr

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BtrSemaphoreTest {
    @Test
    fun aHigherClassAndPriorityGoFirst() = runTest {
        val semaphore = BtrSemaphore(1) { testScheduler.currentTime.toDouble() }
        val held = semaphore.acquire()
        val order = mutableListOf<String>()
        launch { semaphore.acquire(priority = 10.0, queueClass = QUEUE_ORDINARY).also { order += "ordinary"; it.release() } }
        launch { semaphore.acquire(priority = 50.0, queueClass = QUEUE_ORDINARY).also { order += "urgent-ordinary"; it.release() } }
        launch { semaphore.acquire(priority = 0.0, queueClass = QUEUE_STARTUP).also { order += "startup"; it.release() } }
        testScheduler.runCurrent()
        held.release()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("startup", "urgent-ordinary", "ordinary"), order)
    }

    @Test
    fun anOverdueDeadlineOutranksALittleMorePriority() = runTest {
        val semaphore = BtrSemaphore(1) { testScheduler.currentTime.toDouble() }
        val held = semaphore.acquire()
        val order = mutableListOf<String>()
        launch { semaphore.acquire(priority = 55.0, queueClass = QUEUE_ORDINARY).also { order += "prefetch"; it.release() } }
        launch {
            semaphore.acquire(priority = 50.0, deadlineAt = 0.0, queueClass = QUEUE_ORDINARY, ages = true).also { order += "due"; it.release() }
        }
        testScheduler.runCurrent()
        held.release()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("due", "prefetch"), order)
    }

    @Test
    fun aCanceledWaiterDoesNotKeepASlot() = runTest {
        val semaphore = BtrSemaphore(1) { testScheduler.currentTime.toDouble() }
        val held = semaphore.acquire()
        val waiter = launch { semaphore.acquire() }
        testScheduler.runCurrent()
        waiter.cancel()
        testScheduler.runCurrent()
        held.release()
        assertEquals(0, semaphore.active)
        semaphore.acquire().release()
        assertEquals(0, semaphore.active)
    }
}
