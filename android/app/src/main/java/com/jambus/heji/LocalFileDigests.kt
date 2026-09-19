package com.jambus.heji

import java.io.InputStream
import java.security.MessageDigest

data class LocalFileDigests(
    val md5: String,
    val sha256: String
)

object LocalFileDigestsCalculator {
    fun computeDigests(input: InputStream?): LocalFileDigests? {
        if (input == null) return null
        return input.use { stream ->
            val md5Digest = MessageDigest.getInstance("MD5")
            val sha256Digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                md5Digest.update(buffer, 0, count)
                sha256Digest.update(buffer, 0, count)
            }
            LocalFileDigests(
                md5 = md5Digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
                sha256 = sha256Digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            )
        }
    }
}

object DriveSyncDigestPolicy {
    fun canReuseBaselineDigest(
        cached: DriveBaselineFile?,
        localLastModified: Long?,
        localSize: Long? = null
    ): Boolean = false

    fun canReuseBaselineDigest(
        path: String?,
        cached: DriveBaselineFile?,
        localLastModified: Long?,
        localSize: Long? = null
    ): Boolean = false

    fun resolveDigest(
        cached: DriveBaselineFile?,
        localLastModified: Long?,
        localSize: Long? = null,
        computeFallback: () -> LocalFileDigests?
    ): LocalFileDigests? = computeFallback()

    fun resolveDigest(
        path: String?,
        cached: DriveBaselineFile?,
        localLastModified: Long?,
        localSize: Long? = null,
        computeFallback: () -> LocalFileDigests?
    ): LocalFileDigests? = computeFallback()
}
