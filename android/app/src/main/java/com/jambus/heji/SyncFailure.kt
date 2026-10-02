package com.jambus.heji

import java.io.IOException

/** Allowlisted, locale-neutral diagnostics. Exception text and remote URLs never enter task state. */
enum class SyncErrorReason {
    UNKNOWN, AUTH_REQUIRED, PERMISSION_DENIED, NETWORK, RATE_LIMITED, REMOTE_MISSING,
    REMOTE_CHANGED, PATH_AMBIGUOUS, LOCAL_READ_FAILED, LOCAL_WRITE_FAILED,
    CONTENT_MISMATCH, LOCAL_CHANGED, BASELINE_FAILED, COMMIT_UNCERTAIN,
    PRECONDITION_UNAVAILABLE, SYNC_BUSY
}

fun SyncErrorReason.label(context: android.content.Context): String = context.getString(when (this) {
    SyncErrorReason.UNKNOWN -> R.string.sync_reason_unknown
    SyncErrorReason.AUTH_REQUIRED -> R.string.sync_reason_auth
    SyncErrorReason.PERMISSION_DENIED -> R.string.sync_reason_permission
    SyncErrorReason.NETWORK -> R.string.sync_reason_network
    SyncErrorReason.RATE_LIMITED -> R.string.sync_reason_rate
    SyncErrorReason.REMOTE_MISSING -> R.string.sync_reason_missing
    SyncErrorReason.REMOTE_CHANGED -> R.string.sync_reason_remote_changed
    SyncErrorReason.PATH_AMBIGUOUS -> R.string.sync_reason_path
    SyncErrorReason.LOCAL_READ_FAILED -> R.string.sync_reason_read
    SyncErrorReason.LOCAL_WRITE_FAILED -> R.string.sync_reason_write
    SyncErrorReason.CONTENT_MISMATCH -> R.string.sync_reason_content
    SyncErrorReason.LOCAL_CHANGED -> R.string.sync_reason_local_changed
    SyncErrorReason.BASELINE_FAILED -> R.string.sync_reason_baseline
    SyncErrorReason.COMMIT_UNCERTAIN -> R.string.sync_reason_uncertain
    SyncErrorReason.PRECONDITION_UNAVAILABLE -> R.string.sync_reason_precondition
    SyncErrorReason.SYNC_BUSY -> R.string.sync_reason_busy
})

class SyncOperationException(val reason: SyncErrorReason, val path: String = "") : Exception(reason.name)

internal object SyncFailurePolicy {
    fun safePath(path: String): String = path.takeIf {
        it.isNotBlank() && it.length <= 1024 && !it.startsWith('/') &&
            !it.contains(':') && !it.contains('\\') && !it.contains('@') &&
            !it.any { char -> char.isISOControl() || char in "?*\"<>|" } &&
            it.split('/').none { part -> part.isBlank() || part == "." || part == ".." }
    }.orEmpty()

    fun fromException(failure: Exception, path: String = ""): SyncErrorDetail {
        val reason = when (failure) {
            is SyncOperationException -> failure.reason
            is DriveApiException -> failure.reason
            is OneDriveReloginRequired -> SyncErrorReason.AUTH_REQUIRED
            is SecurityException -> SyncErrorReason.PERMISSION_DENIED
            is IOException -> SyncErrorReason.NETWORK
            else -> SyncErrorReason.UNKNOWN
        }
        return SyncErrorDetail(SyncErrorCode.ITEM_FAILED,
            safePath(path.ifBlank { (failure as? SyncOperationException)?.path.orEmpty() }), reason)
    }

    fun http(code: Int): SyncErrorReason = when (code) {
        401 -> SyncErrorReason.AUTH_REQUIRED
        403 -> SyncErrorReason.PERMISSION_DENIED
        404 -> SyncErrorReason.REMOTE_MISSING
        409, 412 -> SyncErrorReason.REMOTE_CHANGED
        429 -> SyncErrorReason.RATE_LIMITED
        in 500..599 -> SyncErrorReason.NETWORK
        else -> SyncErrorReason.UNKNOWN
    }
}

internal object SyncTaskHistory {
    const val LIMIT = 16
    private val providers = setOf("google_drive", "onedrive")
    fun accepts(provider: String): Boolean = provider in providers
    fun upsert(previous: List<SyncTaskSnapshot>, value: SyncTaskSnapshot): List<SyncTaskSnapshot> {
        if (!accepts(value.providerId) || value.isRunning || value.vaultId.isBlank()) return previous.take(LIMIT)
        return (listOf(value) + previous.filterNot {
            it.providerId == value.providerId && it.vaultId == value.vaultId
        }).take(LIMIT)
    }
}
