package com.jambus.heji

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** Only connection/response establishment belongs inside this policy, never body consumption. */
class SafeReadRetry(
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: (Long) -> Unit = Thread::sleep
) {
    fun <T> execute(cancelled: AtomicBoolean, operation: (attempt: Int) -> T): T {
        var waited = 0L
        for (attempt in 0..2) {
            checkCancelled(cancelled)
            try { return operation(attempt) } catch (failure: Exception) {
                val delay = when (failure) {
                    is RetryReadAuthentication -> 0L
                    is RetryableReadResponse -> retryAfter(failure.retryAfter) ?: (1000L shl attempt)
                    is SocketTimeoutException -> 1000L shl attempt
                    is UnknownHostException, is ConnectException, is NoRouteToHostException -> 1000L shl attempt
                    is SocketException -> if (failure.message?.lowercase() in setOf("connection reset", "connection reset by peer")) 1000L shl attempt else throw failure
                    else -> throw failure
                }
                if (attempt == 2 && failure is RetryReadAuthentication) throw OneDriveReloginRequired()
                if (attempt == 2 || delay > 30_000L - waited) throw failure
                var remaining = delay
                while (remaining > 0) {
                    checkCancelled(cancelled)
                    val step = minOf(100L, remaining)
                    try { wait(step) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw SyncTransferCancelled()
                    }
                    remaining -= step
                }
                waited += delay
            }
        }
        error("Unreachable")
    }

    private fun retryAfter(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.all { it.isDigit() }) {
            val seconds = raw.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > 30L) Long.MAX_VALUE else seconds * 1000L
        }
        val timestamp = try { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { return null }
        val current = now()
        return if (timestamp <= current) 0 else if (timestamp - current > 30_000L || timestamp - current < 0) Long.MAX_VALUE else timestamp - current
    }

    companion object {
        fun checkCancelled(cancelled: AtomicBoolean) { if (cancelled.get()) throw SyncTransferCancelled() }
        fun transient(code: Int): Boolean = code in setOf(429, 500, 502, 503, 504)
    }
}

internal class RetryableReadResponse(val code: Int, val retryAfter: String?) :
    DriveApiException("Remote read failed ($code)", SyncFailurePolicy.http(code))

internal class RetryReadAuthentication : Exception("Authentication read replay")
