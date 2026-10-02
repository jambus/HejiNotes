package com.jambus.heji

import java.io.InputStream
import java.security.MessageDigest

internal object DriveMutationPrecondition {
    fun requireStrong(etag: String?): String {
        val tag = etag?.trim().orEmpty()
        if (etag?.any { it.isISOControl() } == true || tag.length < 3 || tag.first() != '"' || tag.last() != '"' ||
            !tag.substring(1, tag.length - 1).all { it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }) {
            throw SyncOperationException(SyncErrorReason.PRECONDITION_UNAVAILABLE)
        }
        return tag
    }
}

internal class CancellationInputStream(private val source: InputStream, private val cancelled: () -> Boolean) : InputStream() {
    override fun read(): Int {
        if (cancelled()) throw SyncTransferCancelled()
        return source.read()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (cancelled()) throw SyncTransferCancelled()
        return source.read(buffer, offset, length)
    }
    override fun close() = source.close()
}

/** Verified at EOF, before consumers may commit the downloaded bytes to the Vault. */
internal class VerifiedRemoteInputStream(
    private val source: InputStream,
    private val expectedMd5: String,
    private val expectedSha256: String?,
    private val path: String,
    private val cancelled: () -> Boolean,
    private val verifySource: () -> Unit
) : InputStream() {
    private val md5 = MessageDigest.getInstance("MD5")
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private var complete = false
    private var failed = false

    fun requireComplete() {
        checkCancellation()
        if (!complete || failed) throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
    }

    fun checkCancellation() {
        if (cancelled()) throw SyncTransferCancelled()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        checkCancellation()
        if (failed) throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
        if (complete) return -1
        val count = source.read(buffer, offset, length)
        if (count > 0) {
            md5.update(buffer, offset, count)
            sha256.update(buffer, offset, count)
        } else if (count < 0) {
            try {
                fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val actualMd5 = hex(md5)
                val actualSha256 = hex(sha256)
                if (!expectedMd5.equals(actualMd5, true) ||
                    (expectedSha256 != null && !expectedSha256.equals(actualSha256, true))) {
                    throw SyncOperationException(SyncErrorReason.CONTENT_MISMATCH, path)
                }
                checkCancellation()
                verifySource()
                checkCancellation()
                complete = true
            } catch (failure: Exception) {
                failed = true
                throw failure
            }
        }
        return count
    }

    override fun close() = source.close()
}
