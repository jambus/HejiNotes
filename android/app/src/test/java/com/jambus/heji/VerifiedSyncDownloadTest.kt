package com.jambus.heji

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class VerifiedSyncDownloadTest {
    private class Backend : SyncDownloadBackend {
        val names = linkedMapOf<String, SyncDownloadDocument>()
        val bytes = linkedMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        var mode = "normal"
        var corruptWrite = false
        var corruptCommit = false
        var concurrent = false
        var digestHook: ((SyncDownloadDocument) -> Unit)? = null
        var locationMoved = false
        var moveDuringOutput = false
        override fun validateLocation() {
            if (locationMoved) throw SyncOperationException(SyncErrorReason.COMMIT_UNCERTAIN, "notes/doc.md")
        }
        fun add(name: String, text: String): SyncDownloadDocument {
            val doc = SyncDownloadDocument("id-${bytes.size}", name, "notes/$name")
            names[name] = doc
            bytes[doc.id] = text.toByteArray()
            return doc
        }
        override fun find(name: String) = names[name]
        override fun create(name: String, mimeType: String?) = add(name, "")
        override fun output(document: SyncDownloadDocument): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                bytes[document.id] = if (corruptWrite) "corrupt".toByteArray() else toByteArray()
                if (concurrent) add("doc.md", "local-race")
                if (moveDuringOutput) locationMoved = true
            }
        }
        override fun digests(document: SyncDownloadDocument): LocalFileDigests {
            digestHook?.invoke(document)
            return LocalFileDigestsCalculator.computeDigests(ByteArrayInputStream(bytes.getValue(document.id)))!!
        }
        override fun rename(document: SyncDownloadDocument, name: String): SyncDownloadDocument? {
            if (name == "doc.md" && document.name.endsWith(".pending") && mode == "throw-before-once") {
                mode = "normal"
                throw IllegalStateException("provider failure")
            }
            if (name == "doc.md" && mode == "throw-before") throw IllegalStateException("provider failure")
            val actual = if (name == "doc.md" && mode == "wrong-name") "doc (1).md" else name
            names.remove(document.name)
            val committed = document.copy(name = actual, path = "notes/$actual")
            names[actual] = committed
            if (name == "doc.md" && document.name.endsWith(".pending") && corruptCommit) bytes[committed.id] = "damaged".toByteArray()
            if (name == "doc.md" && document.name.endsWith(".pending")) {
                if (mode == "null-after") return null
                if (mode == "throw-after") throw IllegalStateException("provider failure")
            }
            return committed
        }
        override fun delete(document: SyncDownloadDocument): Boolean {
            deleted += document.name
            names.remove(document.name)
            bytes.remove(document.id)
            return true
        }
        fun text(name: String) = String(bytes.getValue(names.getValue(name).id))
    }

    private fun write(backend: Backend, absent: Boolean = true): Boolean = VerifiedSyncDownload.write(
        backend, "notes/doc.md", "text/markdown", ByteArrayInputStream("cloud-body".toByteArray()), "token", absent
    )

    private fun failure(backend: Backend, absent: Boolean = true): SyncOperationException {
        try { write(backend, absent); fail("Expected verification failure") } catch (ex: SyncOperationException) { return ex }
        throw AssertionError("unreachable")
    }

    @Test fun `exact content and path count as success and remove only verified backup`() {
        val backend = Backend().apply { add("doc.md", "original") }
        assertTrue(write(backend, false))
        assertEquals("cloud-body", backend.text("doc.md"))
        assertEquals(listOf(".markbook-sync-token.previous"), backend.deleted)
    }

    @Test fun `corrupt staged writes leave original intact`() {
        val backend = Backend().apply { add("doc.md", "original"); corruptWrite = true }
        assertEquals(SyncErrorReason.CONTENT_MISMATCH, failure(backend, false).reason)
        assertEquals("original", backend.text("doc.md"))
        assertFalse(backend.names.containsKey(".markbook-sync-token.previous"))
    }

    @Test fun `unknown commit does not delete committed data or original backup`() {
        for (mode in listOf("null-after", "throw-after")) {
            val backend = Backend().apply { add("doc.md", "original"); this.mode = mode }
            assertEquals(SyncErrorReason.COMMIT_UNCERTAIN, failure(backend, false).reason)
            assertEquals("cloud-body", backend.text("doc.md"))
            assertEquals("original", backend.text(".markbook-sync-token.previous"))
            assertTrue(backend.deleted.isEmpty())
        }
    }

    @Test fun `provider renamed destination is not success and backup restored only into absent target`() {
        val backend = Backend().apply { add("doc.md", "original"); mode = "wrong-name" }
        failure(backend, false)
        // A moved staging identity is uncertain: no speculative rollback or cleanup.
        assertTrue(backend.deleted.isEmpty())
        assertEquals("original", backend.text(".markbook-sync-token.previous"))
        assertEquals("cloud-body", backend.text("doc (1).md"))
        assertTrue(backend.bytes.values.any { String(it) == "original" })
        assertTrue(backend.bytes.values.any { String(it) == "cloud-body" })
    }

    @Test fun `corrupted committed file does not discard original backup`() {
        val backend = Backend().apply { add("doc.md", "original"); corruptCommit = true }
        assertEquals(SyncErrorReason.CONTENT_MISMATCH, failure(backend, false).reason)
        assertEquals("original", backend.text(".markbook-sync-token.previous"))
        assertTrue(backend.deleted.isEmpty())
    }

    @Test fun `racing local target is preserved without writing over it`() {
        val backend = Backend().apply { concurrent = true }
        assertFalse(write(backend))
        assertEquals("local-race", backend.text("doc.md"))
        assertEquals(listOf(".markbook-sync-token.pending"), backend.deleted)
    }

    @Test fun `backup name collision fails before altering content`() {
        val backend = Backend().apply { add("doc.md", "original"); add(".markbook-sync-token.previous", "other") }
        assertEquals(SyncErrorReason.PATH_AMBIGUOUS, failure(backend, false).reason)
        assertEquals("original", backend.text("doc.md"))
        assertEquals("other", backend.text(".markbook-sync-token.previous"))
    }

    @Test fun `edit discovered by backup hash is preserved instead of deleted`() {
        val backend = Backend().apply { add("doc.md", "original") }
        val expected = backend.digests(backend.names.getValue("doc.md")).md5
        backend.digestHook = { document ->
            if (document.name.endsWith(".previous")) backend.bytes[document.id] = "new-local-edit".toByteArray()
        }
        try {
            VerifiedSyncDownload.write(backend, "notes/doc.md", "text/markdown", ByteArrayInputStream("cloud".toByteArray()), "token", false, expected)
            fail("Expected external content drift to block overwrite")
        } catch (ex: SyncOperationException) {
            assertEquals(SyncErrorReason.COMMIT_UNCERTAIN, ex.reason)
        }
        assertTrue(backend.bytes.values.any { String(it) == "new-local-edit" })
        assertTrue(backend.deleted.isEmpty())
    }

    @Test fun `verified absent target permits restoring original after proven precommit failure`() {
        val backend = Backend().apply { add("doc.md", "original"); mode = "throw-before-once" }
        failure(backend, false)
        assertEquals("original", backend.text("doc.md"))
        assertFalse(backend.names.containsKey(".markbook-sync-token.previous"))
        assertEquals("cloud-body", backend.text(".markbook-sync-token.pending"))
        assertTrue(backend.deleted.isEmpty())
    }

    @Test fun `unreadable original hash never renames or deletes the original`() {
        val backend = Backend().apply { add("doc.md", "original") }
        backend.digestHook = { document -> if (document.name == "doc.md") throw java.io.IOException("unreadable") }
        failure(backend, false)
        assertEquals("original", backend.text("doc.md"))
        assertFalse(backend.names.containsKey(".markbook-sync-token.previous"))
    }

    @Test fun `parent location drift blocks commit before original is changed`() {
        val backend = Backend().apply { add("doc.md", "original"); moveDuringOutput = true }
        assertEquals(SyncErrorReason.COMMIT_UNCERTAIN, failure(backend, false).reason)
        assertEquals("original", backend.text("doc.md"))
        assertFalse(backend.names.containsKey(".markbook-sync-token.previous"))
    }

    @Test fun `parent drift at final hash preserves original backup`() {
        val backend = Backend().apply { add("doc.md", "original") }
        var originalReads = 0
        backend.digestHook = { document ->
            if (document.name == "doc.md") {
                originalReads++
                if (originalReads == 2) backend.locationMoved = true
            }
        }
        assertEquals(SyncErrorReason.COMMIT_UNCERTAIN, failure(backend, false).reason)
        assertEquals("original", backend.text(".markbook-sync-token.previous"))
        assertEquals("cloud-body", backend.text("doc.md"))
        assertTrue(backend.deleted.isEmpty())
    }

    @Test fun `retained sync artifacts survive generic recovery and never sync`() {
        for (name in listOf(".markbook-sync-token.pending", ".markbook-sync-token.previous")) {
            assertEquals(RecoveryArtifact.Ignore, VaultRecoveryPolicy.classify(name, false))
            assertEquals(RecoveryArtifact.Ignore, VaultRecoveryPolicy.classify(name, true))
            assertFalse(SyncPathPolicy.isAllowed("notes/$name", false))
        }
    }

    @Test fun `wrong source bytes leave original and never create or delete backup`() {
        val backend = Backend().apply { add("doc.md", "original") }
        val expected = LocalFileDigestsCalculator.computeDigests(ByteArrayInputStream("expected-cloud".toByteArray()))!!
        val source = VerifiedRemoteInputStream(ByteArrayInputStream("truncated".toByteArray()), expected.md5,
            expected.sha256, "notes/doc.md", { false }) {}
        try {
            source.use { VerifiedSyncDownload.write(backend, "notes/doc.md", "text/plain", it, "token", false) }
            fail("Wrong cloud content was committed")
        } catch (ex: SyncOperationException) { assertEquals(SyncErrorReason.CONTENT_MISMATCH, ex.reason) }
        assertEquals("original", backend.text("doc.md"))
        assertFalse(backend.names.containsKey(".markbook-sync-token.previous"))
        assertFalse(backend.deleted.contains("doc.md"))
    }
}
