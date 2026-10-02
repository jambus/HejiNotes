package com.jambus.heji

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class RemoteSyncSafetyTest {
    private fun digest(body: String) = LocalFileDigestsCalculator.computeDigests(ByteArrayInputStream(body.toByteArray()))!!

    @Test fun `unusable ETags open no Google connections or upload inputs`() {
        for (etag in listOf(null, "", " ", "*", "W/\"v1\"", "v1", "\"\"", "\"a\", \"b\"", "\"ok\"\r\n")) {
            var requests = 0
            var reads = 0
            val api = GoogleDriveApi("unused-test-token") { _, _ ->
                requests++
                throw AssertionError("No HTTP request may open without a strong precondition")
            }
            val input = object : InputStream() { override fun read(): Int { reads++; return -1 } }
            for (trash in listOf(false, true)) {
                try {
                    if (trash) api.trash("item", etag) else api.replace("item", etag, "text/plain", input)
                    fail("Invalid ETag accepted")
                } catch (ex: SyncOperationException) { assertEquals(SyncErrorReason.PRECONDITION_UNAVAILABLE, ex.reason) }
            }
            assertEquals(0, requests)
            assertEquals(0, reads)
        }
        assertEquals("\"v1\"", DriveMutationPrecondition.requireStrong("\"v1\""))
    }

    @Test fun `strong preconditions are sent on Google replacement and recycle requests`() {
        val opened = mutableListOf<HttpURLConnection>()
        val api = GoogleDriveApi("unused-test-token") { _, url ->
            object : HttpURLConnection(URL(url)) {
                val body = ByteArrayOutputStream()
                private var verb = "GET"
                // Desktop JDK's default setter rejects PATCH; Android's transport supports it.
                override fun setRequestMethod(method: String) { verb = method }
                override fun getRequestMethod() = verb
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getOutputStream() = body
                override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
                override fun getResponseCode() = 200
            }.also(opened::add)
        }
        api.replace("item", "\"v1\"", "text/plain", ByteArrayInputStream("content".toByteArray()))
        api.trash("item", "\"v2\"")
        assertEquals(2, opened.size)
        assertEquals("\"v1\"", opened[0].getRequestProperty("If-Match"))
        assertEquals("\"v2\"", opened[1].getRequestProperty("If-Match"))
        assertTrue(opened.all { it.requestMethod == "PATCH" })
    }

    @Test fun `wrong normally ending remote body cannot pass EOF proof`() {
        var verified = false
        val expected = digest("expected")
        val input = VerifiedRemoteInputStream(ByteArrayInputStream("wrong".toByteArray()), expected.md5,
            expected.sha256, "note.md", { false }) { verified = true }
        try { input.use { it.readBytes() }; fail("Wrong bytes accepted") }
        catch (ex: SyncOperationException) { assertEquals(SyncErrorReason.CONTENT_MISMATCH, ex.reason) }
        assertFalse(verified)
    }

    @Test fun `correct bytes require revision callback and completion proof`() {
        val expected = digest("content")
        var callbacks = 0
        val input = VerifiedRemoteInputStream(ByteArrayInputStream("content".toByteArray()), expected.md5,
            expected.sha256, "note.md", { false }) { callbacks++ }
        assertEquals("content", String(input.readBytes()))
        input.requireComplete()
        assertEquals(-1, input.read())
        assertEquals(1, callbacks)
        input.close()
    }

    @Test fun `cancellation after reading bytes but before EOF blocks commit proof`() {
        val expected = digest("content")
        var cancelled = false
        val input = VerifiedRemoteInputStream(ByteArrayInputStream("content".toByteArray()), expected.md5,
            null, "note.md", { cancelled }) {}
        val buffer = ByteArray(7)
        assertEquals(7, input.read(buffer))
        cancelled = true
        try { input.read(); fail("Cancelled stream accepted") } catch (_: SyncTransferCancelled) {}
        try { input.requireComplete(); fail("Cancelled commit accepted") } catch (_: SyncTransferCancelled) {}
        input.close()
    }

    @Test fun `source revision change at EOF preserves typed failure`() {
        val expected = digest("content")
        val input = VerifiedRemoteInputStream(ByteArrayInputStream("content".toByteArray()), expected.md5,
            null, "note.md", { false }) { throw SyncOperationException(SyncErrorReason.REMOTE_CHANGED) }
        try { input.use { it.readBytes() }; fail("Changed source accepted") }
        catch (ex: SyncOperationException) { assertEquals(SyncErrorReason.REMOTE_CHANGED, ex.reason) }
    }
}
