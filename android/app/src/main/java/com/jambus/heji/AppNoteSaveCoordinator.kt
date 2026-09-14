package com.jambus.heji

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Application-level serial coordinator for note writes and Vault IO.
 *
 * Scoped to the process rather than a transient Activity instance so configuration changes
 * and screen rotations do not abort in-flight saves with shutdownNow().
 */
object AppNoteSaveCoordinator : Executor {
    private val serialExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HejiAppNoteSaveCoordinator").apply {
            isDaemon = true
        }
    }
    private val mediaExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HejiAppPhotoMediaCoordinator").apply {
            isDaemon = true
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun execute(command: Runnable) {
        serialExecutor.execute(command)
    }

    fun executeIo(action: () -> Unit) {
        serialExecutor.execute(action)
    }

    fun executeMedia(action: () -> Unit) {
        mediaExecutor.execute(action)
    }

    fun submitSave(
        repository: VaultRepository,
        note: VaultDocument,
        content: String,
        revision: Long,
        onComplete: (success: Boolean, refreshed: VaultDocument?) -> Unit
    ) {
        serialExecutor.execute {
            val success = repository.saveText(note, content)
            val refreshed = if (success) repository.refreshDocument(note) else null
            mainHandler.post {
                onComplete(success, refreshed)
            }
        }
    }
}
