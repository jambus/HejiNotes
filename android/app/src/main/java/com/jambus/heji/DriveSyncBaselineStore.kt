package com.jambus.heji

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class DriveBaselineFile(
    val path: String,
    val localSha256: String,
    val remoteId: String,
    val remoteMd5: String?,
    val remoteVersion: Long?,
    val localMd5: String? = null,
    val localLastModified: Long? = null,
    val localSize: Long? = null
)
data class DriveSyncBaseline(val vaultId: String, val rootId: String, val accountId: String, val files: Map<String, DriveBaselineFile>, val completedAt: Long)

sealed class DriveBaselineLoad {
    object Missing : DriveBaselineLoad()
    data class Present(val baseline: DriveSyncBaseline) : DriveBaselineLoad()
    object Corrupt : DriveBaselineLoad()
}

interface DriveBaselineStore {
    fun load(vaultId: String, rootId: String, accountId: String): DriveBaselineLoad
    fun save(baseline: DriveSyncBaseline): Boolean
}

/** Per-Vault, per-Drive-root successful comparison state; never Vault content. */
class LocalDriveSyncBaselineStore(context: Context) : DriveBaselineStore {
    private val preferences = context.getSharedPreferences("heji_notes_drive_baselines", Context.MODE_PRIVATE)

    override fun load(vaultId: String, rootId: String, accountId: String): DriveBaselineLoad {
        val raw = preferences.getString(key(vaultId, rootId, accountId), null) ?: return DriveBaselineLoad.Missing
        return runCatching {
            val json = JSONObject(raw)
            require(json.getInt("schema") == 2)
            val files = json.getJSONArray("files")
            val map = buildMap {
                for (index in 0 until files.length()) {
                    val item = files.getJSONObject(index)
                    val path = item.getString("path")
                    val localMd5 = item.optString("localMd5").takeIf { it.isNotBlank() } ?: item.optString("remoteMd5").takeIf { it.isNotBlank() }
                    val localLastModified = if (item.has("localLastModified")) item.getLong("localLastModified") else null
                    val localSize = if (item.has("localSize")) item.getLong("localSize") else null
                    put(path, DriveBaselineFile(
                        path,
                        item.getString("localSha256"),
                        item.getString("remoteId"),
                        item.optString("remoteMd5").takeIf { it.isNotBlank() },
                        item.optLong("remoteVersion").takeIf { item.has("remoteVersion") },
                        localMd5,
                        localLastModified,
                        localSize
                    ))
                }
            }
            DriveBaselineLoad.Present(DriveSyncBaseline(json.getString("vaultId"), json.getString("rootId"), json.getString("accountId"), map, json.getLong("completedAt")))
        }.getOrElse { DriveBaselineLoad.Corrupt }
    }

    override fun save(baseline: DriveSyncBaseline): Boolean {
        val files = JSONArray()
        baseline.files.values.sortedBy { it.path }.forEach { file ->
            val fileJson = JSONObject()
                .put("path", file.path)
                .put("localSha256", file.localSha256)
                .put("remoteId", file.remoteId)
                .put("remoteMd5", file.remoteMd5)
                .put("remoteVersion", file.remoteVersion)
            file.localMd5?.let { fileJson.put("localMd5", it) }
            file.localLastModified?.let { fileJson.put("localLastModified", it) }
            file.localSize?.let { fileJson.put("localSize", it) }
            files.put(fileJson)
        }
        val value = JSONObject().put("schema", 2).put("vaultId", baseline.vaultId).put("rootId", baseline.rootId).put("accountId", baseline.accountId)
            .put("completedAt", baseline.completedAt).put("files", files)
        return preferences.edit().putString(key(baseline.vaultId, baseline.rootId, baseline.accountId), value.toString()).commit()
    }

    private fun key(vaultId: String, rootId: String, accountId: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$vaultId\u0000$rootId\u0000$accountId".toByteArray(Charsets.UTF_8))
}
