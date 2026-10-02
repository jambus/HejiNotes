package com.jambus.heji

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.NoRouteToHostException
import javax.net.ssl.SSLHandshakeException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SafeReadRetryTest {
    private class Response(val code: Int, val headers: Map<String, String> = emptyMap(), val body: String = "{}", val broken: Boolean = false, val brokenClose: Boolean = false, val establishmentFailure: IOException? = null, val bodyFailure: IOException? = null) : HttpURLConnection(URL("https://graph.microsoft.com/v1.0/test")) {
        var closed = false
        private var verb = "GET"
        override fun setRequestMethod(value: String) { verb = value }
        override fun getRequestMethod() = verb
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode(): Int { establishmentFailure?.let { throw it }; return code }
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream(): InputStream = if (broken || bodyFailure != null) object : InputStream() { override fun read(): Int = throw (bodyFailure ?: SocketTimeoutException()) } else ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream(): InputStream = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { if (brokenClose) throw SocketTimeoutException() }
        }
    }
    private fun api(provider: String, queue: MutableList<Response>, cancelled: AtomicBoolean = AtomicBoolean(), wait: (Long) -> Unit = {}, refresh: (Boolean) -> String = { "test" }): DriveGateway {
        val factory: (String, String) -> HttpURLConnection = { _, _ -> queue.removeAt(0) }
        val retry = SafeReadRetry({ 0L }, wait)
        return if (provider == "google") GoogleDriveApi("test", cancelled, retry, factory)
        else OneDriveApi(refresh, File(requireNotNull(System.getProperty("java.io.tmpdir"))), cancelled, retry, factory)
    }
    @Test fun `both providers recover allowed response and cap repeated failure`() {
        for (provider in listOf("google", "one")) for (code in listOf(429,500,502,503,504)) {
            val responses = mutableListOf(Response(code), Response(code), Response(200))
            var slept = 0L
            api(provider, responses, wait = { slept += it }).download(DriveItem("i","n","text/plain")).close()
            assertTrue(responses.isEmpty()); assertEquals(3000L, slept)
            val failed = mutableListOf(Response(code),Response(code),Response(code),Response(200))
            try { api(provider, failed).download(DriveItem("i","n","text/plain")); fail() } catch (_: DriveApiException) {}
            assertEquals(1,failed.size)
        }
    }
    @Test fun `RetryAfter budget malformed and huge headers are bounded`() {
        for (header in listOf("31", "999999999999999999999999999", "Thu, 1 Jan 1970 00:00:31 GMT")) {
            var count=0
            try { SafeReadRetry({0L},{}).execute(AtomicBoolean()) { count++; throw RetryableReadResponse(429,header) }; fail() } catch (_: DriveApiException) {}
            assertEquals(1,count)
        }
        for (header in listOf("-1","garbage", "1", "Thu, 1 Jan 1970 00:00:01 GMT")) {
            var count=0;var slept=0L
            SafeReadRetry({0L},{slept+=it}).execute(AtomicBoolean()) { if (++count==1) throw RetryableReadResponse(429,header); "ok" }
            assertEquals(1000L,slept)
        }
    }
    @Test fun `cancel during wait opens zero additional connections`() {
        for (provider in listOf("google","one")) {
            val cancelled=AtomicBoolean();val first=Response(429);val queue= mutableListOf(first,Response(200))
            try { api(provider,queue,cancelled,{cancelled.set(true)}).download(DriveItem("i","n","text/plain"));fail() } catch (_: SyncTransferCancelled) {}
            assertEquals(1,queue.size);assertTrue(first.closed)
        }
    }
    @Test fun `terminal codes and mutating authentication are never replayed`() {
        for (provider in listOf("google","one")) for (code in listOf(403,404,409,412)) {
            val first=Response(code);val queue=mutableListOf(first,Response(200))
            try { api(provider,queue).download(DriveItem("i","n","text/plain"));fail() } catch (_: Exception) {}
            assertEquals(1,queue.size);assertTrue(first.closed)
        }
        for (provider in listOf("google","one")) {
            var refreshed=0;val queue=mutableListOf(Response(401),Response(200))
            try { api(provider,queue,refresh={if(it)refreshed++;"test"}).upload("p","n","text/plain",ByteArrayInputStream("body".toByteArray()));fail() } catch (_: Exception) {}
            assertEquals(1,queue.size);assertEquals(0,refreshed)
        }
    }
    @Test fun `OneDrive refresh is once per run and shares transient attempt budget`() {
        var refresh=0;val queue=mutableListOf(Response(429),Response(401),Response(429),Response(200))
        try { api("one",queue,refresh={if(it)refresh++;"test"}).download(DriveItem("i","n","text/plain"));fail() } catch (_: DriveApiException) {}
        assertEquals(1,queue.size);assertEquals(1,refresh)
        val q=mutableListOf(Response(401),Response(401),Response(200))
        try { api("one",q).download(DriveItem("i","n","text/plain"));fail() } catch (_: OneDriveReloginRequired) {}
        assertEquals(1,q.size)
    }
    @Test fun `download consumer failure never reopens and failed redirect always disconnects`() {
        for (provider in listOf("google","one")) {
            val first=Response(200,broken=true);val queue=mutableListOf(first,Response(200))
            try { api(provider,queue).download(DriveItem("i","n","text/plain")).use { it.read() };fail() } catch (_: IOException) {}
            assertEquals(1,queue.size);assertTrue(first.closed)
        }
        val first=Response(302,mapOf("Location" to "https://example.org/private"));val queue=mutableListOf(first)
        try { api("one",queue).download(DriveItem("i","n","text/plain"));fail() } catch (_: DriveApiException) {}
        assertTrue(first.closed)
        val graph=Response(302,mapOf("Location" to "https://storage.live.com/file"));val redirected=Response(503)
        val q=mutableListOf(graph,redirected,Response(403))
        try { api("one",q).download(DriveItem("i","n","text/plain"));fail() } catch (_: DriveApiException) {}
        assertTrue(graph.closed);assertTrue(redirected.closed);assertNull(redirected.getRequestProperty("Authorization"))
    }
    @Test fun `metadata reads retry while invalid JSON never replays`() {
        for (provider in listOf("google", "one")) {
            val body = if (provider == "google") "{\"files\":[]}" else "{\"value\":[]}"
            val first = Response(503); val queue = mutableListOf(first, Response(200, body = body))
            assertTrue(api(provider, queue).listChildren("p").isEmpty())
            assertTrue(first.closed); assertTrue(queue.isEmpty())
            val invalid = Response(200, body = "invalid"); val bad = mutableListOf(invalid, Response(200, body = body))
            try { api(provider, bad).listChildren("p"); fail() } catch (_: org.json.JSONException) {}
            assertEquals(1, bad.size); assertTrue(invalid.closed)
        }
    }
    @Test fun `HTTP error cleanup failure never masks terminal status`() {
        for (provider in listOf("google", "one")) {
            val first = Response(403, brokenClose = true); val queue = mutableListOf(first, Response(200))
            try { api(provider, queue).download(DriveItem("i", "n", "text/plain")); fail() }
            catch (failure: DriveApiException) { assertEquals(SyncErrorReason.PERMISSION_DENIED, failure.reason) }
            assertEquals(1, queue.size); assertTrue(first.closed)
        }
    }
    @Test fun `selected transport timeouts retry without replaying generic errors`() {
        var count = 0
        assertEquals("ok", SafeReadRetry(wait = {}).execute(AtomicBoolean()) { if (++count < 3) throw SocketTimeoutException(); "ok" })
        assertEquals(3, count)
        count = 0
        assertEquals("ok", SafeReadRetry(wait = {}).execute(AtomicBoolean()) { if (++count == 1) throw java.net.SocketException("Connection reset"); "ok" })
        assertEquals(2, count)
    }
    @Test fun `exhausted auth preserves reason without useless refresh`() {
        var refresh = 0
        val q = mutableListOf(Response(429), Response(429), Response(401), Response(200))
        try { api("one", q, refresh = { if (it) refresh++; "test" }).download(DriveItem("i", "n", "text/plain")); fail() }
        catch (failure: OneDriveReloginRequired) { assertEquals(SyncErrorReason.AUTH_REQUIRED, SyncFailurePolicy.fromException(failure).reason) }
        assertEquals(1, q.size); assertEquals(0, refresh)
        val success = mutableListOf(Response(429), Response(401), Response(200))
        api("one", success).download(DriveItem("i", "n", "text/plain")).close()
        assertTrue(success.isEmpty())
    }
    @Test fun `copy authentication never issues fallback reads`() {
        var reads = 0
        try {
            OneDriveCopyPolicy.executeCopy("digest", { reads++; null }, { "digest" }, {
                throw DriveApiException("expired", SyncErrorReason.AUTH_REQUIRED)
            })
            fail()
        } catch (failure: DriveApiException) { assertEquals(SyncErrorReason.AUTH_REQUIRED, failure.reason) }
        assertEquals(1, reads)
    }
    @Test fun `generic IO and interrupted wait never retry`() {
        var count=0
        try { SafeReadRetry(wait={}).execute(AtomicBoolean()) {count++;throw IOException("parse")};fail() } catch (_: IOException) {}
        assertEquals(1,count)
        try { SafeReadRetry(wait={throw InterruptedException()}).execute(AtomicBoolean()) {throw SocketTimeoutException()};fail() } catch (_: SyncTransferCancelled) {}
        assertTrue(Thread.interrupted())
    }

    private fun connectionFailures(): List<IOException> = listOf(UnknownHostException("private"), ConnectException("private"), NoRouteToHostException("private"))

    @Test fun `both providers recover establishment DNS and connection failures within three attempts`() {
        for (provider in listOf("google", "one")) for (failure in connectionFailures()) {
            val first = Response(200, establishmentFailure = failure)
            val second = Response(200, establishmentFailure = failure)
            val queue = mutableListOf(first, second, Response(200))
            var waited = 0L
            api(provider, queue, wait = { waited += it }).download(DriveItem("i", "n", "text/plain")).close()
            assertEquals(3000L, waited)
            assertTrue(queue.isEmpty()); assertTrue(first.closed); assertTrue(second.closed)
            val exhausted = mutableListOf(Response(200, establishmentFailure = failure), Response(200, establishmentFailure = failure), Response(200, establishmentFailure = failure), Response(200))
            try { api(provider, exhausted).download(DriveItem("i", "n", "text/plain")); fail() }
            catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(1, exhausted.size)
        }
    }

    @Test fun `cancelled DNS and connection waits open no new requests for either provider`() {
        for (provider in listOf("google", "one")) for (failure in connectionFailures()) {
            val cancelled = AtomicBoolean()
            val first = Response(200, establishmentFailure = failure)
            val queue = mutableListOf(first, Response(200))
            try { api(provider, queue, cancelled, { cancelled.set(true) }).download(DriveItem("i", "n", "text/plain")); fail() }
            catch (_: SyncTransferCancelled) {}
            assertEquals(1, queue.size); assertTrue(first.closed)
        }
    }

    @Test fun `TLS even with nested timeout and transport body or write failures never replay`() {
        val tls = SSLHandshakeException("private").apply { initCause(SocketTimeoutException()) }
        for (provider in listOf("google", "one")) {
            val queue = mutableListOf(Response(200, establishmentFailure = tls), Response(200))
            try { api(provider, queue).download(DriveItem("i", "n", "text/plain")); fail() }
            catch (failure: SSLHandshakeException) { assertSame(tls, failure) }
            assertEquals(1, queue.size)
            for (failure in connectionFailures() + tls) {
                val body = mutableListOf(Response(200, bodyFailure = failure), Response(200))
                try { api(provider, body).download(DriveItem("i", "n", "text/plain")).use { it.read() }; fail() }
                catch (actual: IOException) { assertSame(failure, actual) }
                assertEquals(1, body.size)
                val writes = mutableListOf(Response(200, establishmentFailure = failure), Response(200))
                try { api(provider, writes).upload("p", "n", "text/plain", ByteArrayInputStream(byteArrayOf(1))); fail() }
                catch (actual: IOException) { assertSame(failure, actual) }
                assertEquals(1, writes.size)
            }
        }
    }

    @Test fun `all mutating gateway operations leave transport failures unreplayed`() {
        for (provider in listOf("google", "one")) for (failure in connectionFailures()) {
            val mutations: List<(DriveGateway) -> Unit> = listOf(
                { it.createFolder("p", "n"); Unit },
                { it.upload("p", "n", "text/plain", ByteArrayInputStream(byteArrayOf(1))) },
                { it.replace("i", "\"etag\"", "text/plain", ByteArrayInputStream(byteArrayOf(1))) },
                { it.trash("i", "\"etag\"") },
                { it.copy("i", "p", "n"); Unit }
            )
            for ((index, mutate) in mutations.withIndex()) {
                val queue = mutableListOf<Response>()
                // OneDrive streams a copy through safe source/target reads before its single upload.
                if (provider == "one" && index == 4) {
                    queue += Response(200, body = "{\"id\":\"i\",\"name\":\"source\",\"file\":{}}")
                    queue += Response(200, body = "body")
                    queue += Response(200, body = "{\"value\":[]}")
                }
                val first = Response(200, establishmentFailure = failure)
                queue += first; queue += Response(200)
                try { mutate(api(provider, queue)); fail() }
                catch (actual: IOException) { assertSame(failure, actual) }
                assertEquals(1, queue.size); assertTrue(first.closed)
            }
        }
    }
}
