package com.jambus.heji

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Application-level coordinator for note persistence, media processing, and cross-Activity lifecycle state.
 *
 * Scoped to the process rather than a transient Activity instance so configuration changes
 * and screen rotations do not abort in-flight saves with shutdownNow() or cause race conditions
 * between async JavaScript serialization and Activity recreation.
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
    private val readWaitExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "HejiAppNoteReadWait").apply {
            isDaemon = true
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var mainDispatcher: ((Runnable) -> Unit)? = null

    private fun dispatchToMain(action: Runnable) {
        val custom = mainDispatcher
        if (custom != null) {
            custom(action)
        } else {
            try {
                val looper = Looper.getMainLooper()
                if (looper != null && Looper.myLooper() == looper) {
                    action.run()
                } else {
                    mainHandler.post(action)
                }
            } catch (_: Exception) {
                action.run()
            }
        }
    }

    // Monotonic revision tracking per note URI to prevent late-arriving stale writes
    private val noteRevisions = ConcurrentHashMap<String, Long>()

    enum class PendingNoteSaveState {
        SERIALIZING, WRITE_QUEUED, WRITING, SAVED, WRITE_FAILED, SERIALIZE_FAILED, EXPIRED, CLEARED
    }

    data class UnsavedSnapshot(val operationId: String, val noteUri: String, val revision: Long, val content: String)

    sealed class NoteRecovery {
        object None : NoteRecovery()
        object Active : NoteRecovery()
        object Expired : NoteRecovery()
        data class Failed(val snapshot: UnsavedSnapshot) : NoteRecovery()
    }

    private class PendingNoteSave(
        val operationId: String,
        val noteUri: String,
        val revision: Long,
        val latch: CountDownLatch = CountDownLatch(1),
        val onExpired: (() -> Unit)? = null,
        var state: PendingNoteSaveState = PendingNoteSaveState.SERIALIZING,
        var snapshot: UnsavedSnapshot? = null,
        var deadline: ScheduledFuture<*>? = null
    )

    private val saveDeadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "HejiNoteSaveDeadline").apply { isDaemon = true }
    }
    private val pendingSavesById = ConcurrentHashMap<String, PendingNoteSave>()
    private val latestSaveByNote = ConcurrentHashMap<String, PendingNoteSave>()
    private val failedSnapshots = ConcurrentHashMap<String, UnsavedSnapshot>()

    // Photo session state coordination across rotations
    sealed class PhotoSessionResult {
        object InProgress : PhotoSessionResult()
        data class Success(val refreshedNote: VaultDocument, val attachmentName: String) : PhotoSessionResult()
        data class Failure(val message: String) : PhotoSessionResult()
    }

    data class PhotoOperation(
        val captureFilePath: String,
        val noteUri: String,
        val caretOffset: Int = 0,
        val scrollY: Int = 0,
        val savedBodyHash: String? = null,
        val id: String = java.util.UUID.randomUUID().toString()
    )

    private class PhotoSession(val operation: PhotoOperation) {
        var result: PhotoSessionResult = PhotoSessionResult.InProgress
        var listener: ((PhotoSessionResult) -> Unit)? = null
        var owner: Any? = null
        var phase = PhotoPhase.PROCESSING
        var workerDone = false
    }

    enum class PhotoPhase {
        PROCESSING, ATTACHMENTS_WRITING, READY_TO_COMMIT, COMMITTING,
        COMMITTED, FAILED, RECOVERY_REQUIRED, CANCELLED
    }

    enum class PhotoCancelResult { ACCEPTED, TOO_LATE, FINISHED }

    private val activePhotoSessions = ConcurrentHashMap<String, PhotoSession>()

    override fun execute(command: Runnable) {
        serialExecutor.execute(command)
    }

    fun executeIo(action: () -> Unit) {
        serialExecutor.execute(action)
    }

    fun executeMedia(action: () -> Unit) {
        mediaExecutor.execute(action)
    }

    /**
     * Called on the UI thread before initiating async evaluateJavascript serialization.
     * Prevents a newly recreated Activity from reading stale disk content before this save is submitted.
     */
    fun beginSerialization(noteUri: String, revision: Long, onExpired: (() -> Unit)? = null): String {
        val currentMax = noteRevisions.getOrDefault(noteUri, -1L)
        if (revision < currentMax) {
            return ""
        }
        val pending = PendingNoteSave(UUID.randomUUID().toString(), noteUri, revision, onExpired = onExpired)
        val old = latestSaveByNote.put(noteUri, pending)
        if (old != null) {
            pendingSavesById.remove(old.operationId, old)
            var expireSerialization = false
            synchronized(old) {
                when (old.state) {
                    PendingNoteSaveState.SERIALIZING -> expireSerialization = true
                    PendingNoteSaveState.WRITE_QUEUED -> {
                        old.state = PendingNoteSaveState.EXPIRED
                        finishPending(old)
                    }
                    else -> Unit
                }
            }
            if (expireSerialization) expire(old)
        }
        pendingSavesById[pending.operationId] = pending
        pending.deadline = saveDeadlineExecutor.schedule({ expire(pending) }, SAVE_BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return pending.operationId
    }

    /** Compatibility entry point for callers that only need a read barrier. */
    fun registerPendingSerialization(noteUri: String, revision: Long): String = beginSerialization(noteUri, revision)

    fun submitSnapshot(operationId: String, content: String): Boolean {
        val pending = pendingSavesById[operationId] ?: return false
        synchronized(pending) {
            if (pending.state != PendingNoteSaveState.SERIALIZING || latestSaveByNote[pending.noteUri] !== pending) return false
            val snapshot = UnsavedSnapshot(operationId, pending.noteUri, pending.revision, content)
            pending.snapshot = snapshot
            pending.state = PendingNoteSaveState.WRITE_QUEUED
            failedSnapshots[pending.noteUri] = snapshot
            return true
        }
    }

    /**
     * Cancels or clears a pending serialization if evaluateJavascript failed or was skipped.
     */
    fun cancelPendingSerialization(noteUri: String) {
        latestSaveByNote[noteUri]?.let { failSerialization(it) }
    }

    fun failSerialization(operationId: String) {
        pendingSavesById[operationId]?.let { failSerialization(it) }
    }

    private fun failSerialization(pending: PendingNoteSave) {
        synchronized(pending) {
            if (pending.state != PendingNoteSaveState.SERIALIZING) return
            pending.state = PendingNoteSaveState.SERIALIZE_FAILED
            finishPending(pending)
            pendingSavesById.remove(pending.operationId, pending)
        }
    }

    private fun expire(pending: PendingNoteSave) {
        var callback: (() -> Unit)? = null
        synchronized(pending) {
            if (pending.state != PendingNoteSaveState.SERIALIZING) return
            pending.state = PendingNoteSaveState.EXPIRED
            finishPending(pending)
            pendingSavesById.remove(pending.operationId, pending)
            callback = pending.onExpired
        }
        callback?.let { action ->
            dispatchToMain(Runnable {
                if (synchronized(pending) { pending.state == PendingNoteSaveState.EXPIRED }) action()
            })
        }
    }

    private fun finishPending(pending: PendingNoteSave) {
        pending.deadline?.cancel(false)
        pending.latch.countDown()
    }

    fun expireSerializationForTests(operationId: String) {
        pendingSavesById[operationId]?.let(::expire)
    }

    fun noteRecovery(noteUri: String): NoteRecovery {
        val pending = latestSaveByNote[noteUri]
        if (pending != null) synchronized(pending) {
            when (pending.state) {
                PendingNoteSaveState.SERIALIZING, PendingNoteSaveState.WRITE_QUEUED, PendingNoteSaveState.WRITING -> return NoteRecovery.Active
                PendingNoteSaveState.WRITE_FAILED -> pending.snapshot?.let { return NoteRecovery.Failed(it) }
                PendingNoteSaveState.EXPIRED, PendingNoteSaveState.SERIALIZE_FAILED -> return NoteRecovery.Expired
                PendingNoteSaveState.SAVED, PendingNoteSaveState.CLEARED -> return NoteRecovery.None
            }
        }
        return failedSnapshots[noteUri]?.let(NoteRecovery::Failed) ?: NoteRecovery.None
    }

    fun discardNoteRecovery(noteUri: String) {
        failedSnapshots.remove(noteUri)
        latestSaveByNote.remove(noteUri)?.let { pendingSavesById.remove(it.operationId); finishPending(it) }
    }

    /** Clears process-memory note bodies and invalidates callbacks when the selected Vault changes. */
    fun clearVaultScopedState() {
        val operations = pendingSavesById.values.toList()
        val terminalCallbacks = mutableListOf<() -> Unit>()
        operations.forEach { pending ->
            synchronized(pending) {
                if (pending.state != PendingNoteSaveState.CLEARED) {
                    pending.onExpired?.let(terminalCallbacks::add)
                }
                pending.state = PendingNoteSaveState.CLEARED
                pending.snapshot = null
                finishPending(pending)
            }
        }
        pendingSavesById.clear()
        latestSaveByNote.clear()
        failedSnapshots.clear()
        terminalCallbacks.forEach { callback -> dispatchToMain(Runnable(callback)) }
    }

    fun getRevision(noteUri: String): Long = noteRevisions.getOrDefault(noteUri, 0L)

    fun advanceRevision(noteUri: String): Long {
        return noteRevisions.compute(noteUri) { _, curr -> (curr ?: 0L) + 1L }!!
    }

    const val SAVE_BARRIER_TIMEOUT_MS = 30_000L

    /**
     * Submits a save to disk. Validates monotonic revision ordering.
     */
    fun submitSave(
        repository: NoteReadWriter,
        note: VaultDocument,
        content: String,
        revision: Long,
        onComplete: (success: Boolean, refreshed: VaultDocument?) -> Unit
    ) {
        val pending = latestSaveByNote[note.uri.toString()]?.takeIf { it.revision == revision }
        submitSaveInternal(repository, note, content, revision, pending, null, onComplete)
    }

    fun submitSave(
        repository: NoteReadWriter,
        note: VaultDocument,
        content: String,
        revision: Long,
        operationId: String,
        writeOverride: (() -> Boolean)? = null,
        onComplete: (success: Boolean, refreshed: VaultDocument?) -> Unit
    ) {
        val pending = pendingSavesById[operationId]
        if (pending == null || pending.noteUri != note.uri.toString() || pending.revision != revision) {
            dispatchToMain { onComplete(false, null) }
            return
        }
        submitSaveInternal(repository, note, content, revision, pending, writeOverride, onComplete)
    }

    private fun submitSaveInternal(
        repository: NoteReadWriter,
        note: VaultDocument,
        content: String,
        revision: Long,
        pending: PendingNoteSave?,
        writeOverride: (() -> Boolean)? = null,
        onComplete: (success: Boolean, refreshed: VaultDocument?) -> Unit
    ) {
        val noteKey = note.uri.toString()

        serialExecutor.execute {
            try {
                if (pending != null) synchronized(pending) {
                    if (pending.state == PendingNoteSaveState.SERIALIZING) {
                        val snapshot = UnsavedSnapshot(pending.operationId, noteKey, revision, content)
                        pending.snapshot = snapshot
                        failedSnapshots[noteKey] = snapshot
                        pending.state = PendingNoteSaveState.WRITE_QUEUED
                    }
                    if (pending.state != PendingNoteSaveState.WRITE_QUEUED || latestSaveByNote[noteKey] !== pending) {
                        if (pending.state == PendingNoteSaveState.WRITE_QUEUED) {
                            pending.state = PendingNoteSaveState.EXPIRED
                            finishPending(pending)
                        }
                        pendingSavesById.remove(pending.operationId, pending)
                        dispatchToMain { onComplete(false, null) }
                        return@execute
                    }
                    pending.state = PendingNoteSaveState.WRITING
                }
                val currentMax = noteRevisions.getOrDefault(noteKey, -1L)
                if (revision < currentMax) {
                    // Stale save from an older revision/instance, skip writing to disk
                    dispatchToMain { onComplete(false, null) }
                    return@execute
                }
                val success = VaultSaveLock.withLock {
                    writeOverride?.invoke() ?: repository.saveText(note, content)
                }
                if (pending != null) synchronized(pending) {
                    if (pending.state == PendingNoteSaveState.CLEARED) {
                        dispatchToMain { onComplete(false, null) }
                        return@execute
                    }
                }
                if (success) {
                    noteRevisions.compute(noteKey) { _, current -> maxOf(current ?: -1L, revision) }
                }
                val refreshed = if (success) repository.refreshDocument(note) else null
                if (pending != null) synchronized(pending) {
                    pending.state = if (success) PendingNoteSaveState.SAVED else PendingNoteSaveState.WRITE_FAILED
                    finishPending(pending)
                    if (!success) pendingSavesById.remove(pending.operationId, pending)
                    if (success) {
                        failedSnapshots.remove(noteKey, pending.snapshot)
                        pending.snapshot = null
                        latestSaveByNote.remove(noteKey, pending)
                        pendingSavesById.remove(pending.operationId, pending)
                    }
                }
                dispatchToMain {
                    onComplete(success, refreshed)
                }
            } finally {
                if (pending != null && pending.state == PendingNoteSaveState.WRITING) synchronized(pending) {
                    pending.state = PendingNoteSaveState.WRITE_FAILED
                    finishPending(pending)
                    pendingSavesById.remove(pending.operationId, pending)
                }
            }
        }
    }

    /**
     * Submits a read to the serial queue. If a pending serialization is active for this note,
     * it awaits the completion of that serialization/save before reading disk.
     */
    fun submitRead(
        repository: NoteReadWriter,
        note: VaultDocument,
        onResult: (NoteReadResult) -> Unit
    ) {
        val noteKey = note.uri.toString()
        val latch = latestSaveByNote[noteKey]?.let { pending -> synchronized(pending) {
            if (pending.state in setOf(PendingNoteSaveState.SERIALIZING, PendingNoteSaveState.WRITE_QUEUED, PendingNoteSaveState.WRITING)) pending.latch else null
        } }
        if (latch != null && latch.count > 0) {
            readWaitExecutor.execute {
                val completed = try {
                    latch.await(SAVE_BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    false
                }
                if (!completed) {
                    latestSaveByNote[noteKey]?.let(::expire)
                    dispatchToMain {
                        onResult(NoteReadResult.Failure(VaultFailureKind.READ_FAILED))
                    }
                    return@execute
                }
                serialExecutor.execute {
                    val result = repository.readNote(note)
                    dispatchToMain {
                        onResult(result)
                    }
                }
            }
        } else {
            serialExecutor.execute {
                val result = repository.readNote(note)
                dispatchToMain {
                    onResult(result)
                }
            }
        }
    }

    fun startPhotoSession(
        captureFilePath: String, noteUri: String, caretOffset: Int = 0,
        scrollY: Int = 0, savedBodyHash: String? = null
    ): PhotoOperation? {
        val operation = PhotoOperation(captureFilePath, noteUri, caretOffset, scrollY, savedBodyHash)
        return if (activePhotoSessions.putIfAbsent(captureFilePath, PhotoSession(operation)) == null) operation else null
    }

    fun photoOperation(path: String): PhotoOperation? = activePhotoSessions[path]?.operation

    private fun session(operation: PhotoOperation): PhotoSession? =
        activePhotoSessions[operation.captureFilePath]?.takeIf { it.operation == operation }

    fun transitionPhotoSession(operation: PhotoOperation, expected: PhotoPhase, next: PhotoPhase): Boolean {
        val session = session(operation) ?: return false
        synchronized(session) {
            if (session(operation) !== session || session.phase != expected) return false
            session.phase = next
            return true
        }
    }

    fun photoPhase(path: String): PhotoPhase? = activePhotoSessions[path]?.let { synchronized(it) { it.phase } }

    private fun deliver(session: PhotoSession) {
        val owner = synchronized(session) { session.owner } ?: return
        dispatchToMain {
            synchronized(session) {
                if (session(session.operation) !== session || session.owner !== owner) return@dispatchToMain
                val callback = session.listener ?: return@dispatchToMain
                if (session.result !is PhotoSessionResult.InProgress) callback(session.result)
            }
        }
    }

    fun completePhotoSession(
        operation: PhotoOperation, success: Boolean, refreshedNote: VaultDocument?,
        attachmentName: String?, errorMessage: String? = null
    ) {
        val session = session(operation) ?: return
        synchronized(session) {
            if (session.phase == PhotoPhase.CANCELLED || session.phase == PhotoPhase.RECOVERY_REQUIRED) return
            session.result = if (success && refreshedNote != null && attachmentName != null)
                PhotoSessionResult.Success(refreshedNote, attachmentName)
            else PhotoSessionResult.Failure(errorMessage ?: "照片尚未插入，请重试")
            session.phase = if (success) PhotoPhase.COMMITTED else PhotoPhase.FAILED
        }
        // Terminal results are delivered only after worker resources/rollback have been released.
    }

    fun requirePhotoRecovery(operation: PhotoOperation, message: String) {
        val session = session(operation) ?: return
        synchronized(session) {
            session.phase = PhotoPhase.RECOVERY_REQUIRED
            session.result = PhotoSessionResult.Failure(message)
        }
    }

    fun finishPhotoWorker(operation: PhotoOperation) {
        val session = session(operation) ?: return
        synchronized(session) {
            session.workerDone = true
            if (session.phase == PhotoPhase.CANCELLED) session.result = PhotoSessionResult.Failure("照片处理已取消")
        }
        deliver(session)
    }

    fun observePhotoSession(operation: PhotoOperation, owner: Any, callback: (PhotoSessionResult) -> Unit) {
        val session = session(operation) ?: return
        synchronized(session) { session.owner = owner; session.listener = callback }
        if (synchronized(session) { session.workerDone }) deliver(session)
    }

    fun detachPhotoObserver(owner: Any) {
        activePhotoSessions.values.forEach { session -> synchronized(session) {
            if (session.owner === owner) { session.owner = null; session.listener = null }
        } }
    }

    fun acknowledgePhotoSession(operation: PhotoOperation, owner: Any): Boolean {
        val session = session(operation) ?: return false
        synchronized(session) {
            if (session.owner !== owner || !session.workerDone || session.phase == PhotoPhase.RECOVERY_REQUIRED ||
                session.result is PhotoSessionResult.InProgress) return false
            return activePhotoSessions.remove(operation.captureFilePath, session)
        }
    }

    fun requestPhotoCancel(path: String): PhotoCancelResult {
        val session = activePhotoSessions[path] ?: return PhotoCancelResult.FINISHED
        synchronized(session) {
            when (session.phase) {
                PhotoPhase.PROCESSING, PhotoPhase.ATTACHMENTS_WRITING, PhotoPhase.READY_TO_COMMIT -> session.phase = PhotoPhase.CANCELLED
                PhotoPhase.COMMITTING, PhotoPhase.RECOVERY_REQUIRED -> return PhotoCancelResult.TOO_LATE
                else -> return PhotoCancelResult.FINISHED
            }
        }
        return PhotoCancelResult.ACCEPTED
    }

    fun isPhotoSessionCancelled(operation: PhotoOperation): Boolean {
        val session = session(operation) ?: return true
        return synchronized(session) { session.phase == PhotoPhase.CANCELLED }
    }

    fun hasActivePhotoSession(path: String): Boolean = activePhotoSessions[path]?.let {
        synchronized(it) { !it.workerDone }
    } ?: false

    fun hasPhotoSession(path: String): Boolean = activePhotoSessions.containsKey(path)

    /** Cancellation remains a barrier until the worker and rollback have actually finished. */
    fun isVaultMutationBarrierClear(): Boolean = pendingSavesById.isEmpty() && failedSnapshots.isEmpty() &&
        activePhotoSessions.values.none { session -> synchronized(session) {
            !session.workerDone || session.phase == PhotoPhase.RECOVERY_REQUIRED
        } }

    fun resetForTests() {
        noteRevisions.clear()
        pendingSavesById.values.forEach { it.deadline?.cancel(false); it.latch.countDown() }
        pendingSavesById.clear()
        latestSaveByNote.clear()
        failedSnapshots.clear()
        activePhotoSessions.clear()
        mainDispatcher = { it.run() }
    }

    fun pendingSaveCountForTests(): Int = pendingSavesById.size

    fun hasRetainedSnapshotForTests(noteUri: String): Boolean = failedSnapshots.containsKey(noteUri)
}
