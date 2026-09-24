package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultScopedPreferencesTest {
    @Test fun `daily settings isolate vaults and new vaults keep default`() {
        val store = MemoryPreferenceStore()
        val settings = VaultDailySettingsStore(store)

        settings.set("vault-a", "Journal/A")
        settings.set("vault-b", "Journal/B")

        assertEquals("Journal/A", settings.get("vault-a").directory)
        assertEquals("Journal/B", settings.get("vault-b").directory)
        assertEquals("Daily Notes", settings.get("vault-c").directory)
    }

    @Test fun `legacy daily value migrates once only to current vault`() {
        val store = MemoryPreferenceStore(mutableMapOf(
            VaultDailySettingsStore.LEGACY_DIRECTORY to "Legacy Daily",
            VaultDailySettingsStore.LEGACY_RESET_NOTICE to true
        ))
        val settings = VaultDailySettingsStore(store)

        assertEquals(VaultDailySettings("Legacy Daily", true), settings.get("vault-a"))
        assertEquals(VaultDailySettings("Daily Notes", false), settings.get("vault-b"))
        assertEquals(VaultDailySettings("Legacy Daily", true), settings.get("vault-a"))
        assertFalse(store.contains(VaultDailySettingsStore.LEGACY_DIRECTORY))
    }

    @Test fun `rename reset and acknowledgement remain scoped`() {
        val store = MemoryPreferenceStore()
        val settings = VaultDailySettingsStore(store)
        settings.set("vault-a", "Daily Notes/2026")
        settings.set("vault-b", "Other")

        val renamed = VaultRelativePath.renamedDailyDirectory(settings.get("vault-a").directory, "Daily Notes", "Journal")
        settings.rewrite("vault-a", renamed)
        val reset = VaultRelativePath.resetIfRemoved(settings.get("vault-a").directory, "Journal")
        if (reset != settings.get("vault-a").directory) settings.resetToRoot("vault-a")

        assertEquals(VaultDailySettings("", true), settings.get("vault-a"))
        assertEquals(VaultDailySettings("Other", false), settings.get("vault-b"))
        settings.set("vault-a", "New Daily")
        assertEquals(VaultDailySettings("New Daily", false), settings.get("vault-a"))
        assertEquals("Other", settings.get("vault-b").directory)
    }

    @Test fun `drive bindings isolate vaults and mismatch retains binding`() {
        val store = MemoryPreferenceStore()
        val preferences = DriveSyncPreferences(store)
        preferences.setRoot(DriveVaultRoot("root-a", "A remote"), "vault-a", "a@example.com")
        preferences.setRoot(DriveVaultRoot("root-b", "B remote"), "vault-b", "b@example.com")
        preferences.markSuccessful("vault-a", "root-a", "a@example.com", 42L)

        assertNull(preferences.root("vault-a", "different@example.com"))
        assertEquals("a@example.com", preferences.binding("vault-a")?.accountId)
        assertEquals(42L, preferences.binding("vault-a")?.lastSuccessAt)
        preferences.clearRoot("vault-b")
        assertNull(preferences.binding("vault-b"))
        assertEquals("root-a", preferences.binding("vault-a")?.root?.id)

        val legacyStore = MemoryPreferenceStore(mutableMapOf(
            "vault_id" to "vault-a",
            "account_id" to "a@example.com",
            "root_id" to "legacy-root",
            "root_name" to "Legacy remote",
            "last_success" to 99L
        ))
        val legacyPreferences = DriveSyncPreferences(legacyStore)

        assertNull(legacyPreferences.binding("vault-b"))
        val migrated = legacyPreferences.binding("vault-a")
        assertEquals("legacy-root", migrated?.root?.id)
        assertEquals(99L, migrated?.lastSuccessAt)
        assertEquals(migrated, legacyPreferences.binding("vault-a"))
        assertFalse(legacyStore.contains("vault_id"))
    }

    @Test fun `sync start policy rejects every tuple mismatch before accepting exact binding`() {
        val request = DriveSyncStartRequest("vault-a", "a@example.com", DriveVaultRoot("root-a", "Remote A"))
        val binding = DriveVaultBinding("vault-a", "a@example.com", request.root, 0L)
        val valid = DriveSyncStartContext("vault-a", binding, "a@example.com", true)

        val cases = listOf(
            null to (valid to DriveSyncStartRejection.MISSING_SELECTION),
            request to (valid.copy(currentVaultId = "vault-b") to DriveSyncStartRejection.VAULT_MISMATCH),
            request to (valid.copy(binding = null) to DriveSyncStartRejection.MISSING_BINDING),
            request to (valid.copy(binding = binding.copy(accountId = "b@example.com")) to DriveSyncStartRejection.BINDING_ACCOUNT_MISMATCH),
            request to (valid.copy(binding = binding.copy(root = request.root.copy(id = "root-b"))) to DriveSyncStartRejection.ROOT_ID_MISMATCH),
            request to (valid.copy(binding = binding.copy(root = request.root.copy(name = "Renamed"))) to DriveSyncStartRejection.ROOT_NAME_MISMATCH),
            request to (valid.copy(currentAccountId = "b@example.com") to DriveSyncStartRejection.CURRENT_ACCOUNT_MISMATCH),
            request to (valid.copy(authorized = false) to DriveSyncStartRejection.UNAUTHORIZED)
        )
        cases.forEach { (candidate, expected) ->
            val (context, reason) = expected
            assertEquals(DriveSyncStartDecision.Rejected(reason), DriveSyncStartPolicy.validate(candidate, context))
        }
        assertTrue(DriveSyncStartPolicy.validate(request, valid) is DriveSyncStartDecision.Accepted)
    }
}

private class MemoryPreferenceStore(
    private val values: MutableMap<String, Any> = mutableMapOf()
) : PreferenceStore {
    override fun string(key: String): String? = values[key] as? String
    override fun long(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun boolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun update(values: Map<String, Any>, removals: Set<String>): Boolean {
        this.values.putAll(values)
        removals.forEach(this.values::remove)
        return true
    }
}
