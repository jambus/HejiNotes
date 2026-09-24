package com.jambus.heji

import android.content.Context

data class DriveVaultRoot(val id: String, val name: String)
data class DriveVaultBinding(
    val vaultId: String,
    val accountId: String,
    val root: DriveVaultRoot,
    val lastSuccessAt: Long
)

/** Stores only non-sensitive remote selection and completion metadata. */
class DriveSyncPreferences internal constructor(private val store: PreferenceStore) {
    constructor(context: Context) : this(SharedPreferenceStore(
        context.getSharedPreferences("heji_notes_drive_sync", Context.MODE_PRIVATE)
    ))

    fun binding(vaultId: String): DriveVaultBinding? {
        migrateLegacy(vaultId)
        if (vaultId.isBlank()) return null
        val rootId = store.string(key(vaultId, ROOT_ID_KEY)) ?: return null
        val accountId = store.string(key(vaultId, ACCOUNT_ID_KEY)) ?: return null
        val name = store.string(key(vaultId, ROOT_NAME_KEY)) ?: "Google Drive"
        return DriveVaultBinding(vaultId, accountId, DriveVaultRoot(rootId, name), store.long(key(vaultId, LAST_SUCCESS_KEY)))
    }

    fun root(vaultId: String, accountId: String): DriveVaultRoot? =
        binding(vaultId)?.takeIf { accountId.isNotBlank() && it.accountId == accountId }?.root

    fun setRoot(root: DriveVaultRoot, vaultId: String, accountId: String) {
        if (vaultId.isBlank() || accountId.isBlank() || root.id.isBlank()) return
        store.update(mapOf(
            key(vaultId, ROOT_ID_KEY) to root.id,
            key(vaultId, ROOT_NAME_KEY) to root.name,
            key(vaultId, ACCOUNT_ID_KEY) to accountId
        ), setOf(key(vaultId, LAST_SUCCESS_KEY)))
    }

    fun clearRoot(vaultId: String) {
        if (vaultId.isBlank()) return
        store.update(emptyMap(), FIELDS.mapTo(mutableSetOf()) { key(vaultId, it) })
    }

    fun lastSuccessAt(vaultId: String, rootId: String, accountId: String): Long =
        binding(vaultId)?.takeIf { it.root.id == rootId && it.accountId == accountId }?.lastSuccessAt ?: 0L

    fun markSuccessful(vaultId: String, rootId: String, accountId: String, completedAt: Long = System.currentTimeMillis()) {
        val binding = binding(vaultId) ?: return
        if (binding.root.id != rootId || binding.accountId != accountId) return
        store.update(mapOf(key(vaultId, LAST_SUCCESS_KEY) to completedAt))
    }

    private fun migrateLegacy(vaultId: String) {
        if (vaultId.isBlank() || store.contains(key(vaultId, ROOT_ID_KEY)) || store.string(VAULT_ID_KEY) != vaultId) return
        val rootId = store.string(ROOT_ID_KEY) ?: return
        val accountId = store.string(ACCOUNT_ID_KEY) ?: return
        val values = mutableMapOf<String, Any>(
            key(vaultId, ROOT_ID_KEY) to rootId,
            key(vaultId, ROOT_NAME_KEY) to (store.string(ROOT_NAME_KEY) ?: "Google Drive"),
            key(vaultId, ACCOUNT_ID_KEY) to accountId
        )
        if (store.contains(LAST_SUCCESS_KEY)) values[key(vaultId, LAST_SUCCESS_KEY)] = store.long(LAST_SUCCESS_KEY)
        store.update(values, LEGACY_FIELDS)
    }

    private fun key(vaultId: String, field: String): String = "vault.v2.${encoded(vaultId)}.drive.$field"

    companion object {
        private const val ROOT_ID_KEY = "root_id"
        private const val ROOT_NAME_KEY = "root_name"
        private const val VAULT_ID_KEY = "vault_id"
        private const val ACCOUNT_ID_KEY = "account_id"
        private const val LAST_SUCCESS_KEY = "last_success"
        private val FIELDS = setOf(ROOT_ID_KEY, ROOT_NAME_KEY, ACCOUNT_ID_KEY, LAST_SUCCESS_KEY)
        private val LEGACY_FIELDS = FIELDS + VAULT_ID_KEY
    }
}
