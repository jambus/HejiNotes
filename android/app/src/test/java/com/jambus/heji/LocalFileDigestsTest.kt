package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class LocalFileDigestsTest {

    private class TrackingInputStream(private val delegate: InputStream) : InputStream() {
        var readCount: Int = 0
        var isClosed: Boolean = false

        override fun read(): Int {
            readCount++
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val bytesRead = delegate.read(b, off, len)
            if (bytesRead >= 0) {
                readCount += bytesRead
            }
            return bytesRead
        }

        override fun close() {
            isClosed = true
            super.close()
        }
    }

    @Test
    fun `computes accurate md5 and sha256 in a single pass`() {
        val testData = "Heji Notes Dual Digest Test Data 2026".toByteArray(Charsets.UTF_8)
        val trackingStream = TrackingInputStream(ByteArrayInputStream(testData))

        val digests = LocalFileDigestsCalculator.computeDigests(trackingStream)

        assertNotNull(digests)
        assertTrue(trackingStream.isClosed)
        assertEquals(testData.size, trackingStream.readCount)

        val expectedMd5 = MessageDigest.getInstance("MD5").digest(testData)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(testData)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals(expectedMd5, digests!!.md5)
        assertEquals(expectedSha256, digests.sha256)
    }

    @Test
    fun `closes stream on read failure`() {
        var closed = false
        val failingStream = object : InputStream() {
            override fun read(): Int = throw RuntimeException("Simulated IO failure")
            override fun close() {
                closed = true
            }
        }

        try {
            LocalFileDigestsCalculator.computeDigests(failingStream)
        } catch (_: Exception) {
        }

        assertTrue("Stream should be closed on failure", closed)
    }
}
