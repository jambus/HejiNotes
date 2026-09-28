package com.jambus.heji

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide mutual exclusion lock for note content saves and delete-reconciliation checks.
 * Ensures that verifying a note's digest before trashing/deleting and the actual delete operation
 * are mutually exclusive with editor note writes.
 */
object VaultSaveLock {
    private val lock = ReentrantLock()

    fun <T> withLock(action: () -> T): T = lock.withLock(action)
}
