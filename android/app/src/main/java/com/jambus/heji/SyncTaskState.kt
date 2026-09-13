package com.jambus.heji

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SyncTaskStatus {
    RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED
}

/** Stable, locale-neutral lifecycle markers stored in preferences instead of rendered text. */
enum class SyncMessageCode { PREPARING, PROGRESS, CANCELLING, COMPLETED, PARTIAL_FAILURE, CANCELLED, INTERRUPTED }
enum class SyncErrorCode { ITEM_FAILED, INTERRUPTED }
data class SyncErrorDetail(val code: SyncErrorCode, val path: String = "")

data class SyncTaskSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0
)

/**
 * Provider-neutral, device-local sync state. Provider implementations may vary, but all of them
 * surface the same lifecycle, summary, error details, and interruption semantics.
 */
data class SyncTaskSnapshot(
    val providerId: String,
    val providerName: String,
    val targetName: String,
    val status: SyncTaskStatus,
    val completed: Int,
    val total: Int,
    val messageCode: SyncMessageCode,
    val startedAt: Long,
    val finishedAt: Long,
    val summary: SyncTaskSummary = SyncTaskSummary(),
    /** Locale-neutral diagnostics retained for settings details after a process restart. */
    val errors: List<SyncErrorDetail> = emptyList(),
    val errorCount: Int = 0,
    val vaultId: String = ""
) {
    val isRunning: Boolean get() = status == SyncTaskStatus.RUNNING

    fun statusLabel(context: Context): String = when (status) {
        SyncTaskStatus.RUNNING -> if (total > 0) context.getString(R.string.sync_running_count, completed, total) else context.getString(R.string.sync_running)
        SyncTaskStatus.SUCCEEDED -> context.getString(R.string.sync_complete)
        SyncTaskStatus.FAILED -> context.getString(R.string.sync_incomplete)
        SyncTaskStatus.CANCELLED -> context.getString(R.string.sync_cancelled)
        SyncTaskStatus.INTERRUPTED -> context.getString(R.string.sync_interrupted)
    }

    fun messageLabel(context: Context): String = when (messageCode) {
        SyncMessageCode.PREPARING -> context.getString(R.string.sync_preparing)
        SyncMessageCode.PROGRESS -> statusLabel(context)
        SyncMessageCode.CANCELLING -> context.getString(R.string.sync_cancelling)
        SyncMessageCode.COMPLETED -> context.getString(R.string.sync_complete)
        SyncMessageCode.PARTIAL_FAILURE -> context.getString(R.string.sync_incomplete_count, errorCount)
        SyncMessageCode.CANCELLED -> context.getString(R.string.sync_cancelled)
        SyncMessageCode.INTERRUPTED -> context.getString(R.string.sync_interrupted)
    }
}

/** Persists only the latest task summary; credentials and Vault content are never stored here. */
class SyncTaskStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): SyncTaskSnapshot? = preferences.getString(SNAPSHOT_KEY, null)?.let(::decode)

    @Synchronized
    fun begin(providerId: String, providerName: String, targetName: String, vaultId: String = ""): SyncTaskSnapshot? {
        if (snapshot()?.isRunning == true) return null
        return SyncTaskSnapshot(
            providerId = providerId,
            providerName = providerName,
            targetName = targetName,
            status = SyncTaskStatus.RUNNING,
            completed = 0,
            total = 0,
            messageCode = SyncMessageCode.PREPARING,
            startedAt = System.currentTimeMillis(),
            finishedAt = 0L,
            vaultId = vaultId
        ).also(::save)
    }

    @Synchronized
    fun updateProgress(progress: DriveSyncProgress): SyncTaskSnapshot? = snapshot()?.takeIf { it.isRunning }?.copy(
        completed = progress.completed,
        total = progress.total,
        messageCode = SyncMessageCode.PROGRESS
    )?.also(::save)

    @Synchronized
    fun requestCancellation(): SyncTaskSnapshot? = snapshot()?.takeIf { it.isRunning }?.copy(
        messageCode = SyncMessageCode.CANCELLING
    )?.also(::save)

    @Synchronized
    fun finish(result: DriveSyncResult): SyncTaskSnapshot? = snapshot()?.takeIf { it.isRunning }?.copy(
        status = when {
            result.cancelled -> SyncTaskStatus.CANCELLED
            result.errors.isNotEmpty() -> SyncTaskStatus.FAILED
            else -> SyncTaskStatus.SUCCEEDED
        },
        messageCode = finishMessageCode(result),
        finishedAt = System.currentTimeMillis(),
        summary = SyncTaskSummary(result.uploaded, result.downloaded, result.unchanged, result.conflicts),
        errors = result.errors.take(MAX_ERROR_DETAILS).map { SyncErrorDetail(SyncErrorCode.ITEM_FAILED, safePath(it)) },
        errorCount = result.errors.size
    )?.also(::save)

    /** A new service instance means an earlier running task cannot be trusted to have completed. */
    @Synchronized
    fun markInterruptedIfRunning(): SyncTaskSnapshot? = snapshot()?.takeIf { it.isRunning }?.copy(
        status = SyncTaskStatus.INTERRUPTED,
        messageCode = SyncMessageCode.INTERRUPTED,
        finishedAt = System.currentTimeMillis(),
        errors = listOf(SyncErrorDetail(SyncErrorCode.INTERRUPTED)),
        errorCount = 1
    )?.also(::save)

    private fun finishMessageCode(result: DriveSyncResult): SyncMessageCode = when {
        result.cancelled -> SyncMessageCode.CANCELLED
        result.errors.isNotEmpty() -> SyncMessageCode.PARTIAL_FAILURE
        else -> SyncMessageCode.COMPLETED
    }

    private fun save(value: SyncTaskSnapshot) {
        preferences.edit().putString(SNAPSHOT_KEY, encode(value).toString()).apply()
    }

    private fun encode(value: SyncTaskSnapshot): JSONObject = JSONObject().apply {
        put("providerId", value.providerId)
        put("providerName", value.providerName)
        put("targetName", value.targetName)
        put("status", value.status.name)
        put("completed", value.completed)
        put("total", value.total)
        put("messageCode", value.messageCode.name)
        put("startedAt", value.startedAt)
        put("finishedAt", value.finishedAt)
        put("uploaded", value.summary.uploaded)
        put("downloaded", value.summary.downloaded)
        put("unchanged", value.summary.unchanged)
        put("conflicts", value.summary.conflicts)
        put("errors", JSONArray(value.errors.map { JSONObject().put("code", it.code.name).put("path", it.path) }))
        put("errorCount", value.errorCount)
        put("vaultId", value.vaultId)
    }

    private fun decode(raw: String): SyncTaskSnapshot? = try {
        val value = JSONObject(raw)
        val status = SyncTaskStatus.valueOf(value.getString("status"))
        SyncTaskSnapshot(
            providerId = value.getString("providerId"),
            providerName = value.getString("providerName"),
            targetName = value.getString("targetName"),
            status = status,
            completed = value.optInt("completed"),
            total = value.optInt("total"),
            messageCode = value.optString("messageCode").takeIf { it.isNotBlank() }?.let(SyncMessageCode::valueOf)
                ?: if (status == SyncTaskStatus.RUNNING) SyncMessageCode.PROGRESS else when (status) {
                    SyncTaskStatus.SUCCEEDED -> SyncMessageCode.COMPLETED
                    SyncTaskStatus.FAILED -> SyncMessageCode.PARTIAL_FAILURE
                    SyncTaskStatus.CANCELLED -> SyncMessageCode.CANCELLED
                    SyncTaskStatus.INTERRUPTED -> SyncMessageCode.INTERRUPTED
                    SyncTaskStatus.RUNNING -> SyncMessageCode.PROGRESS
                },
            startedAt = value.optLong("startedAt"),
            finishedAt = value.optLong("finishedAt"),
            summary = SyncTaskSummary(
                value.optInt("uploaded"), value.optInt("downloaded"),
                value.optInt("unchanged"), value.optInt("conflicts")
            ),
            errors = (value.optJSONArray("errors") ?: JSONArray()).let { errors ->
                List(errors.length()) { index -> errors.optJSONObject(index) }.filterNotNull().mapNotNull { error ->
                    error.optString("code").let { code -> runCatching { SyncErrorCode.valueOf(code) }.getOrNull() }
                        ?.let { code -> SyncErrorDetail(code, error.optString("path")) }
                }
            },
            errorCount = value.optInt("errorCount"),
            vaultId = value.optString("vaultId")
        )
    } catch (_: Exception) {
        null
    }

    /** Persist only a Vault-relative, syntax-checked path parameter; never a localized failure message. */
    private fun safePath(error: String): String = Regex("[A-Za-z0-9._/-]+(?:\\.[A-Za-z0-9]+)?")
        .findAll(error)
        .map { it.value }
        .lastOrNull { it.contains('/') || it.endsWith(".md", true) || it.endsWith(".jpg", true) || it.endsWith(".mp4", true) }
        ?.takeIf { it.length <= 240 && !it.startsWith('/') && !it.contains("..") }
        .orEmpty()

    companion object {
        private const val PREFERENCES_NAME = "heji_notes_sync_tasks"
        private const val SNAPSHOT_KEY = "latest_task"
        private const val MAX_ERROR_DETAILS = 5
    }
}
