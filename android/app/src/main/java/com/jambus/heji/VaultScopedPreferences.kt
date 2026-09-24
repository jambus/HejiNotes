package com.jambus.heji

import android.content.SharedPreferences
import java.util.Base64

internal interface PreferenceStore {
    fun string(key: String): String?
    fun long(key: String, default: Long = 0L): Long
    fun boolean(key: String, default: Boolean = false): Boolean
    fun contains(key: String): Boolean
    fun update(values: Map<String, Any>, removals: Set<String> = emptySet()): Boolean
}

internal class SharedPreferenceStore(private val preferences: SharedPreferences) : PreferenceStore {
    override fun string(key: String): String? = preferences.getString(key, null)
    override fun long(key: String, default: Long): Long = preferences.getLong(key, default)
    override fun boolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun update(values: Map<String, Any>, removals: Set<String>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported preference value")
            }
        }
        removals.forEach(editor::remove)
        return editor.commit()
    }
}

internal data class VaultDailySettings(val directory: String, val resetNotice: Boolean)

internal class VaultDailySettingsStore(private val store: PreferenceStore) {
    fun get(vaultId: String): VaultDailySettings {
        migrateLegacy(vaultId)
        if (vaultId.isBlank()) return VaultDailySettings(DEFAULT_DIRECTORY, false)
        return VaultDailySettings(
            store.string(key(vaultId, DIRECTORY)) ?: DEFAULT_DIRECTORY,
            store.boolean(key(vaultId, RESET_NOTICE))
        )
    }

    fun set(vaultId: String, directory: String): Boolean {
        if (vaultId.isBlank()) return false
        return store.update(
            mapOf(key(vaultId, DIRECTORY) to directory),
            setOf(key(vaultId, RESET_NOTICE))
        )
    }

    fun rewrite(vaultId: String, directory: String): Boolean {
        if (vaultId.isBlank()) return false
        return store.update(mapOf(key(vaultId, DIRECTORY) to directory))
    }

    fun resetToRoot(vaultId: String): Boolean {
        if (vaultId.isBlank()) return false
        return store.update(mapOf(
            key(vaultId, DIRECTORY) to "",
            key(vaultId, RESET_NOTICE) to true
        ))
    }

    private fun migrateLegacy(vaultId: String) {
        if (vaultId.isBlank() || store.contains(key(vaultId, DIRECTORY)) || !store.contains(LEGACY_DIRECTORY)) return
        val values = mutableMapOf<String, Any>(
            key(vaultId, DIRECTORY) to (store.string(LEGACY_DIRECTORY) ?: DEFAULT_DIRECTORY)
        )
        if (store.contains(LEGACY_RESET_NOTICE)) {
            values[key(vaultId, RESET_NOTICE)] = store.boolean(LEGACY_RESET_NOTICE)
        }
        store.update(values, setOf(LEGACY_DIRECTORY, LEGACY_RESET_NOTICE))
    }

    private fun key(vaultId: String, field: String): String = "vault.v2.${encoded(vaultId)}.daily.$field"

    companion object {
        private const val DIRECTORY = "directory"
        private const val RESET_NOTICE = "reset_notice"
        const val DEFAULT_DIRECTORY = "Daily Notes"
        const val LEGACY_DIRECTORY = "daily_note_directory"
        const val LEGACY_RESET_NOTICE = "daily_note_directory_reset_notice"
    }
}

internal fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(Charsets.UTF_8))
