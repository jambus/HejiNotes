package com.jambus.heji

import android.content.Context

/** Non-secret OneDrive selection metadata, isolated by the stable local Vault identity. */
class OneDriveSyncPreferences internal constructor(private val store: PreferenceStore) {
    constructor(context: Context) : this(SharedPreferenceStore(
        context.getSharedPreferences("heji_notes_onedrive_sync", Context.MODE_PRIVATE)
    ))

    fun binding(vaultId: String): DriveVaultBinding? {
        if (vaultId.isBlank()) return null
        val rootId = store.string(key(vaultId, ROOT_ID_KEY)) ?: return null
        val accountId = store.string(key(vaultId, ACCOUNT_ID_KEY)) ?: return null
        val name = store.string(key(vaultId, ROOT_NAME_KEY)) ?: "OneDrive"
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
        ), setOf(key(vaultId, LAST_SUCCESS_KEY), key(vaultId, RELOGIN_REQUIRED_KEY)))
    }

    fun clearRoot(vaultId: String) {
        if (vaultId.isBlank()) return
        store.update(emptyMap(), FIELDS.mapTo(mutableSetOf()) { key(vaultId, it) })
    }

    fun requiresRelogin(vaultId: String): Boolean =
        vaultId.isNotBlank() && store.boolean(key(vaultId, RELOGIN_REQUIRED_KEY))

    fun markReloginRequired(vaultId: String) {
        if (vaultId.isNotBlank()) store.update(mapOf(key(vaultId, RELOGIN_REQUIRED_KEY) to true))
    }

    fun clearReloginRequired(vaultId: String) {
        if (vaultId.isNotBlank()) store.update(emptyMap(), setOf(key(vaultId, RELOGIN_REQUIRED_KEY)))
    }

    fun markSuccessful(vaultId: String, rootId: String, accountId: String, completedAt: Long = System.currentTimeMillis()) {
        val binding = binding(vaultId) ?: return
        if (binding.root.id != rootId || binding.accountId != accountId) return
        store.update(mapOf(key(vaultId, LAST_SUCCESS_KEY) to completedAt))
    }

    private fun key(vaultId: String, field: String): String = "vault.v1.${encoded(vaultId)}.onedrive.$field"

    companion object {
        private const val ROOT_ID_KEY = "root_id"
        private const val ROOT_NAME_KEY = "root_name"
        private const val ACCOUNT_ID_KEY = "account_id"
        private const val LAST_SUCCESS_KEY = "last_success"
        private const val RELOGIN_REQUIRED_KEY = "relogin_required"
        private val FIELDS = setOf(ROOT_ID_KEY, ROOT_NAME_KEY, ACCOUNT_ID_KEY, LAST_SUCCESS_KEY, RELOGIN_REQUIRED_KEY)
    }
}
