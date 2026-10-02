package com.jambus.heji

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal object SyncTaskVaultKey {
    fun fromVaultId(vaultId: String): String {
        if (vaultId.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(vaultId.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

enum class SyncTaskStatus {
    RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED
}

/** A previous completed task must not dismiss a newly opened confirmation. */
internal object SyncConfirmationPolicy {
    fun shouldShowResult(before: SyncTaskSnapshot?, current: SyncTaskSnapshot?): Boolean =
        current != null && (current.isRunning || current != before)

    fun shouldRejectCrossVaultStart(global: SyncTaskSnapshot?, vaultKey: String): Boolean =
        global?.isRunning == true && global.vaultId != vaultKey
}

/** Stable, locale-neutral lifecycle markers stored in preferences instead of rendered text. */
enum class SyncMessageCode { PREPARING, PROGRESS, CANCELLING, COMPLETED, PARTIAL_FAILURE, CANCELLED, INTERRUPTED }
enum class SyncErrorCode { ITEM_FAILED, INTERRUPTED }
data class SyncErrorDetail(val code: SyncErrorCode, val path: String = "", val reason: SyncErrorReason = SyncErrorReason.UNKNOWN)

data class SyncTaskSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0,
    val deleted: Int = 0
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

    /** Caller owns the returned subscription and must close it when its UI stops. */
    fun observe(onChanged: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SNAPSHOT_KEY || key == HISTORY_KEY) onChanged()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) {
        val raw = preferences.getString(SNAPSHOT_KEY, null)
        val decoded = raw?.let(::decode)
        // Inspect the original serialized form, before decode normalizes the URI and diagnostics.
        if (decoded != null) {
            val sanitized = encode(decoded).toString()
            if (raw != sanitized) preferences.edit().putString(SNAPSHOT_KEY, sanitized).apply()
        }
        history() // Sanitize legacy provider records even while another Vault owns latest_task.
        decoded
    }

    fun snapshot(vaultId: String, providerId: String): SyncTaskSnapshot? {
        if (!SyncTaskHistory.accepts(providerId)) return null
        val key = SyncTaskVaultKey.fromVaultId(vaultId)
        val latest = snapshot()?.takeIf { it.vaultId == key && it.providerId == providerId }
        if (latest?.isRunning == true) return latest
        val result = history().firstOrNull { it.vaultId == key && it.providerId == providerId }
        return listOfNotNull(latest, result).maxByOrNull { it.startedAt }
    }

    private fun history(): List<SyncTaskSnapshot> = synchronized(HISTORY_LOCK) { try {
        val raw = preferences.getString(HISTORY_KEY, "[]")
        val records = JSONArray(raw)
        val decoded = (0 until records.length()).mapNotNull { decode(records.getJSONObject(it).toString()) }
            .filter { SyncTaskHistory.accepts(it.providerId) && !it.isRunning }.take(SyncTaskHistory.LIMIT)
        val sanitized = JSONArray(decoded.map(::encode)).toString()
        if (raw != sanitized) preferences.edit().putString(HISTORY_KEY, sanitized).apply()
        decoded
    } catch (_: Exception) { emptyList() } }

    /** A refused request has its own result; it must never replace or finish an active run. */
    fun rejectStart(providerId: String, providerName: String, targetName: String, vaultId: String,
                    reason: SyncErrorReason = SyncErrorReason.SYNC_BUSY): SyncTaskSnapshot = synchronized(HISTORY_LOCK) {
        val now = System.currentTimeMillis()
        val records = history()
        val previous = records.firstOrNull { it.providerId == providerId && it.vaultId == SyncTaskVaultKey.fromVaultId(vaultId) }
        val latestTime = preferences.getString(SNAPSHOT_KEY, null)?.let(::decode)
            ?.takeIf { it.providerId == providerId && it.vaultId == SyncTaskVaultKey.fromVaultId(vaultId) }?.startedAt ?: 0L
        val timestamp = maxOf(now, maxOf(previous?.startedAt ?: 0L, latestTime) + 1L)
        val rejected = SyncTaskSnapshot(providerId, providerName, targetName, SyncTaskStatus.FAILED,
            0, 0, SyncMessageCode.PARTIAL_FAILURE, timestamp, timestamp,
            errors = listOf(SyncErrorDetail(SyncErrorCode.ITEM_FAILED, reason = reason)), errorCount = 1,
            vaultId = SyncTaskVaultKey.fromVaultId(vaultId))
        preferences.edit().putString(HISTORY_KEY,
            JSONArray(SyncTaskHistory.upsert(records, rejected).map(::encode)).toString()).apply()
        rejected
    }

    fun begin(providerId: String, providerName: String, targetName: String, vaultId: String = ""): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) {
        if (snapshot()?.isRunning == true) return@synchronized null
        SyncTaskSnapshot(
            providerId = providerId,
            providerName = providerName,
            targetName = targetName,
            status = SyncTaskStatus.RUNNING,
            completed = 0,
            total = 0,
            messageCode = SyncMessageCode.PREPARING,
            startedAt = System.currentTimeMillis(),
            finishedAt = 0L,
            // Persist only a one-way lookup key, never the SAF tree URI itself.
            vaultId = SyncTaskVaultKey.fromVaultId(vaultId)
        ).also(::save)
    }

    fun updateProgress(progress: DriveSyncProgress): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) { snapshot()?.takeIf { it.isRunning }?.copy(
        completed = progress.completed,
        total = progress.total,
        messageCode = SyncMessageCode.PROGRESS
    )?.also(::save) }

    fun requestCancellation(): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) { snapshot()?.takeIf { it.isRunning }?.copy(
        messageCode = SyncMessageCode.CANCELLING
    )?.also(::save) }

    fun finish(result: DriveSyncResult): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) { snapshot()?.takeIf { it.isRunning }?.copy(
        status = when {
            result.cancelled -> SyncTaskStatus.CANCELLED
            result.errors.isNotEmpty() || result.errorDetails.isNotEmpty() -> SyncTaskStatus.FAILED
            else -> SyncTaskStatus.SUCCEEDED
        },
        messageCode = finishMessageCode(result),
        finishedAt = System.currentTimeMillis(),
        summary = SyncTaskSummary(result.uploaded, result.downloaded, result.unchanged, result.conflicts, result.deleted),
        errors = if (result.errorDetails.isNotEmpty()) result.errorDetails.take(MAX_ERROR_DETAILS).map {
            it.copy(path = SyncFailurePolicy.safePath(it.path))
        } else result.errors.take(MAX_ERROR_DETAILS).map { SyncErrorDetail(SyncErrorCode.ITEM_FAILED) },
        errorCount = maxOf(result.errors.size, result.errorDetails.size)
    )?.also(::save) }

    /** A new service instance means an earlier running task cannot be trusted to have completed. */
    fun markInterruptedIfRunning(): SyncTaskSnapshot? = synchronized(HISTORY_LOCK) { snapshot()?.takeIf { it.isRunning }?.copy(
        status = SyncTaskStatus.INTERRUPTED,
        messageCode = SyncMessageCode.INTERRUPTED,
        finishedAt = System.currentTimeMillis(),
        errors = listOf(SyncErrorDetail(SyncErrorCode.INTERRUPTED)),
        errorCount = 1
    )?.also(::save) }

    private fun finishMessageCode(result: DriveSyncResult): SyncMessageCode = when {
        result.cancelled -> SyncMessageCode.CANCELLED
        result.errors.isNotEmpty() || result.errorDetails.isNotEmpty() -> SyncMessageCode.PARTIAL_FAILURE
        else -> SyncMessageCode.COMPLETED
    }

    private fun save(value: SyncTaskSnapshot) {
        synchronized(HISTORY_LOCK) {
            var records = history()
            // Import the legacy last result before a different provider replaces latest_task.
            preferences.getString(SNAPSHOT_KEY, null)?.let(::decode)?.let {
                records = SyncTaskHistory.upsert(records, it)
            }
            records = SyncTaskHistory.upsert(records, value)
            preferences.edit().putString(SNAPSHOT_KEY, encode(value).toString())
                .putString(HISTORY_KEY, JSONArray(records.map(::encode)).toString()).apply()
        }
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
        put("deleted", value.summary.deleted)
        put("errors", JSONArray(value.errors.map { JSONObject().put("code", it.code.name)
            .put("path", SyncFailurePolicy.safePath(it.path)).put("reason", it.reason.name) }))
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
                value.optInt("unchanged"), value.optInt("conflicts"),
                value.optInt("deleted")
            ),
            errors = (value.optJSONArray("errors") ?: JSONArray()).let { errors ->
                List(errors.length()) { index -> errors.optJSONObject(index) }.filterNotNull().mapNotNull { error ->
                    error.optString("code").let { code -> runCatching { SyncErrorCode.valueOf(code) }.getOrNull() }
                        ?.let { code -> SyncErrorDetail(code, if (error.has("reason")) SyncFailurePolicy.safePath(error.optString("path")) else "",
                            runCatching { SyncErrorReason.valueOf(error.optString("reason")) }.getOrDefault(SyncErrorReason.UNKNOWN)) }
                }
            },
            errorCount = value.optInt("errorCount"),
            vaultId = value.optString("vaultId").let { if (it.startsWith("content://")) SyncTaskVaultKey.fromVaultId(it) else it }
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PREFERENCES_NAME = "heji_notes_sync_tasks"
        private const val SNAPSHOT_KEY = "latest_task"
        private const val HISTORY_KEY = "provider_results_v1"
        private val HISTORY_LOCK = Any()
        private const val MAX_ERROR_DETAILS = 5

        /** Persist only a Vault-relative, syntax-checked path parameter; never a localized failure message. */
        internal fun safePath(path: String): String = SyncFailurePolicy.safePath(path)
    }
}
