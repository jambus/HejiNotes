package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class OneDrivePolicyTest {
    @Test fun `bindings remain isolated by Vault`() {
        val store = OneDriveMemoryPreferenceStore()
        val preferences = OneDriveSyncPreferences(store)
        preferences.setRoot(DriveVaultRoot("one-root-a", "Remote A"), "vault-a", "account-a")
        preferences.setRoot(DriveVaultRoot("one-root-b", "Remote B"), "vault-b", "account-b")
        preferences.markSuccessful("vault-a", "one-root-a", "account-a", 123L)
        preferences.markReloginRequired("vault-a")

        assertEquals("one-root-a", preferences.binding("vault-a")?.root?.id)
        assertEquals(123L, preferences.binding("vault-a")?.lastSuccessAt)
        assertTrue(preferences.requiresRelogin("vault-a"))
        preferences.clearReloginRequired("vault-a")
        assertTrue(!preferences.requiresRelogin("vault-a"))
        assertEquals("one-root-b", preferences.binding("vault-b")?.root?.id)
        assertNull(preferences.root("vault-a", "account-b"))
        preferences.clearRoot("vault-b")
        assertNull(preferences.binding("vault-b"))
        assertEquals("one-root-a", preferences.binding("vault-a")?.root?.id)
    }

    @Test fun `start gate rejects every tuple mismatch`() {
        val request = DriveSyncStartRequest("vault-a", "account-a", DriveVaultRoot("root-a", "Remote A"))
        val binding = DriveVaultBinding("vault-a", "account-a", request.root, 0L)
        val valid = OneDriveSyncStartContext("vault-a", binding)

        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.MISSING_SELECTION), OneDriveSyncStartPolicy.validate(null, valid))
        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.VAULT_MISMATCH), OneDriveSyncStartPolicy.validate(request, valid.copy(currentVaultId = "vault-b")))
        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.MISSING_BINDING), OneDriveSyncStartPolicy.validate(request, valid.copy(binding = null)))
        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.BINDING_ACCOUNT_MISMATCH), OneDriveSyncStartPolicy.validate(request, valid.copy(binding = binding.copy(accountId = "account-b"))))
        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.ROOT_ID_MISMATCH), OneDriveSyncStartPolicy.validate(request, valid.copy(binding = binding.copy(root = request.root.copy(id = "root-b")))))
        assertEquals(DriveSyncStartDecision.Rejected(DriveSyncStartRejection.ROOT_NAME_MISMATCH), OneDriveSyncStartPolicy.validate(request, valid.copy(binding = binding.copy(root = request.root.copy(name = "Renamed")))))
        assertTrue(OneDriveSyncStartPolicy.validate(request, valid) is DriveSyncStartDecision.Accepted)
    }

    @Test fun `continuation and preauthorized URLs reject foreign hosts and insecure schemes`() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root", OneDriveUrlPolicy.graph("https://graph.microsoft.com/v1.0/me/drive/root"))
        assertEquals("https://tenant.sharepoint.com/upload", OneDriveUrlPolicy.upload("https://tenant.sharepoint.com/upload"))
        assertEquals("https://public.dm.files.1drv.com/download", OneDriveUrlPolicy.download("https://public.dm.files.1drv.com/download"))

        listOf(
            { OneDriveUrlPolicy.graph("https://evil.example/v1.0/me/drive") },
            { OneDriveUrlPolicy.graph("http://graph.microsoft.com/v1.0/me/drive") },
            { OneDriveUrlPolicy.upload("https://sharepoint.com.evil.example/upload") },
            { OneDriveUrlPolicy.download("https://1drv.com.evil.example/file") }
        ).forEach { action ->
            assertTrue(runCatching(action).exceptionOrNull() is DriveApiException)
        }
        assertEquals("https://1drv.com/download", OneDriveUrlPolicy.download("https://1drv.com/download"))
        assertEquals("https://tenant.blob.core.windows.net/blob", OneDriveUrlPolicy.download("https://tenant.blob.core.windows.net/blob"))
        assertEquals("https://storage.live.com/items/1", OneDriveUrlPolicy.download("https://storage.live.com/items/1"))
        assertEquals("https://sn3302.files.1drv.com/download", OneDriveUrlPolicy.download("https://sn3302.files.1drv.com/download"))
        assertEquals("https://bay.livefilestore.com/download", OneDriveUrlPolicy.download("https://bay.livefilestore.com/download"))
        assertEquals("https://onedrive.live.com/download", OneDriveUrlPolicy.download("https://onedrive.live.com/download"))
        assertEquals("https://my.microsoftpersonalcontent.com/download", OneDriveUrlPolicy.download("https://my.microsoftpersonalcontent.com/download"))
    }

    @Test fun `signature policy normalizes both raw Base64 and URL-encoded strings`() {
        val raw = "1s/8WJWZqTrAnJLcqAkOfhaQIeu="
        val encoded = "1s%2F8WJWZqTrAnJLcqAkOfhaQIeu%3D"

        assertEquals(raw, OneDriveSignaturePolicy.normalizeRawBase64(raw))
        assertEquals(raw, OneDriveSignaturePolicy.normalizeRawBase64(encoded))
        assertEquals(encoded, OneDriveSignaturePolicy.encodeForRedirectUri(raw))
        assertEquals(encoded, OneDriveSignaturePolicy.encodeForRedirectUri(encoded))
    }

    @Test fun `stream copy stops after cancellation instead of completing the transfer`() {
        val cancelled = AtomicBoolean(false)
        val source = object : InputStream() {
            private val delegate = ByteArrayInputStream(ByteArray(64) { it.toByte() })
            private var first = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = delegate.read(buffer, offset, minOf(length, 8))
                if (first && count > 0) {
                    first = false
                    cancelled.set(true)
                }
                return count
            }
        }
        val output = ByteArrayOutputStream()

        assertTrue(runCatching { CancellableStreamCopy.copy(source, output, cancelled, 8) }.exceptionOrNull() is SyncTransferCancelled)
        assertTrue(output.size() in 1 until 64)
    }

    @Test fun `MoveBundle acknowledgement remains pending until all supported providers acknowledge`() {
        val change = MoveBundleChange(
            id = "move-1",
            vaultId = "vault-a",
            sourceToTarget = mapOf("old.md" to "new.md"),
            beforeSha256 = mapOf("old.md" to "before"),
            afterSha256 = mapOf("new.md" to "after"),
            state = LocalChangeState.COMMITTED
        )
        assertEquals(setOf("google_drive", "onedrive"), ProviderAcknowledgementPolicy.supportedProviders)

        val afterGoogle = ProviderAcknowledgementPolicy.apply(listOf(change), change.id, "google_drive")
        assertEquals(setOf("google_drive"), afterGoogle.single().acknowledgedProviders)

        val afterOneDrive = ProviderAcknowledgementPolicy.apply(afterGoogle, change.id, "onedrive")
        assertTrue(afterOneDrive.isEmpty())
    }

    @Test fun `MoveBundle acknowledgement custom required providers prune when set is satisfied`() {
        val change = MoveBundleChange(
            id = "move-1",
            vaultId = "vault-a",
            sourceToTarget = mapOf("old.md" to "new.md"),
            beforeSha256 = mapOf("old.md" to "before"),
            afterSha256 = mapOf("new.md" to "after"),
            state = LocalChangeState.COMMITTED
        )
        val afterCustom = ProviderAcknowledgementPolicy.apply(listOf(change), change.id, "onedrive", setOf("onedrive"))
        assertTrue(afterCustom.isEmpty())
    }

    @Test fun `new OneDrive items use create-only precondition while replacements use ETag`() {
        assertEquals(mapOf("If-None-Match" to "*"), OneDriveWritePrecondition.headers(null))
        assertEquals(mapOf("If-Match" to "etag-1"), OneDriveWritePrecondition.headers("etag-1"))
    }

    @Test fun `cache cleanup fails closed when directory enumeration fails`() {
        assertTrue(!OneDriveCacheCleanupPolicy.clean(null))
        assertTrue(OneDriveCacheCleanupPolicy.clean(emptyArray()))
    }

    @Test fun `first sync adopts unchanged move source and preserves changed source as conflict`() {
        assertEquals(MoveAdoptionDecision.UNCHANGED_SOURCE, MoveAdoptionPolicy.decide("abc", "ABC"))
        assertEquals(MoveAdoptionDecision.PRESERVE_CONFLICT, MoveAdoptionPolicy.decide("abc", "different"))
        assertEquals(MoveAdoptionDecision.MISSING_FINGERPRINT, MoveAdoptionPolicy.decide(null, "remote"))
    }

    @Test fun `copy policy proceeds with upload when target does not exist`() {
        val decision = OneDriveCopyPolicy.evaluateExisting(null, "hash123") { throw AssertionError("Should not be called") }
        assertEquals(CopyTargetDecision.PROCEED_UPLOAD, decision)
    }

    @Test fun `copy policy reuses existing target when digest matches`() {
        val target = DriveItem("item-1", "file.md", "text/markdown")
        val decision = OneDriveCopyPolicy.evaluateExisting(target, "HASH123") { "hash123" }
        assertEquals(CopyTargetDecision.REUSE_EXISTING, decision)
    }

    @Test fun `copy policy reports conflict when target exists with different digest`() {
        val target = DriveItem("item-1", "file.md", "text/markdown")
        val decision = OneDriveCopyPolicy.evaluateExisting(target, "hash123") { "different" }
        assertEquals(CopyTargetDecision.CONFLICT_DIFFERENT_CONTENT, decision)
    }

    @Test fun `sanitizeUrl strips path and query parameters returning only host`() {
        val urlWithParams = java.net.URL("https://graph.microsoft.com/v1.0/me/drive/root:/test.md:/content?token=secret_value")
        assertEquals("graph.microsoft.com", OneDriveUrlPolicy.sanitizeUrl(urlWithParams))

        val uploadUrl = java.net.URL("https://tenant.sharepoint.com/upload/session/xyz?guid=123")
        assertEquals("tenant.sharepoint.com", OneDriveUrlPolicy.sanitizeUrl(uploadUrl))
    }
}

private class OneDriveMemoryPreferenceStore(
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
