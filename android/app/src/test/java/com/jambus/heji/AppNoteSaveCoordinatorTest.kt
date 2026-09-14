package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppNoteSaveCoordinatorTest {

    @Test
    fun `executes tasks serially in order`() {
        val executionOrder = mutableListOf<Int>()
        val latch = CountDownLatch(3)

        AppNoteSaveCoordinator.executeIo {
            Thread.sleep(20)
            synchronized(executionOrder) { executionOrder.add(1) }
            latch.countDown()
        }

        AppNoteSaveCoordinator.executeIo {
            Thread.sleep(10)
            synchronized(executionOrder) { executionOrder.add(2) }
            latch.countDown()
        }

        AppNoteSaveCoordinator.executeIo {
            synchronized(executionOrder) { executionOrder.add(3) }
            latch.countDown()
        }

        assertTrue("Tasks should complete within timeout", latch.await(2, TimeUnit.SECONDS))
        synchronized(executionOrder) {
            assertEquals(listOf(1, 2, 3), executionOrder)
        }
    }
}
