package com.jambus.heji

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import org.json.JSONObject

internal class SyncTransferCancelled : Exception("Sync transfer cancelled")

internal object CancellableStreamCopy {
    fun copy(input: InputStream, output: java.io.OutputStream, cancelled: AtomicBoolean, bufferSize: Int = 32 * 1024): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            if (cancelled.get()) throw SyncTransferCancelled()
            val count = input.read(buffer)
            if (count < 0) return total
            output.write(buffer, 0, count)
            total += count
        }
    }
}

internal object OneDriveWritePrecondition {
    fun headers(expectedEtag: String?): Map<String, String> = if (expectedEtag == null) {
        mapOf("If-None-Match" to "*")
    } else {
        mapOf("If-Match" to expectedEtag)
    }
}

internal object OneDriveCacheCleanupPolicy {
    fun clean(files: Array<File>?): Boolean = files
        ?.filter { it.isFile && it.name.startsWith("onedrive-upload-") && it.name.endsWith(".tmp") }
        ?.all { !it.exists() || it.delete() }
        ?: false
}

internal enum class CopyTargetDecision {
    REUSE_EXISTING,
    CONFLICT_DIFFERENT_CONTENT,
    PROCEED_UPLOAD
}

internal object OneDriveCopyPolicy {
    fun evaluateExisting(existingItem: DriveItem?, sourceDigest: String, existingDigestSupplier: (DriveItem) -> String): CopyTargetDecision {
        if (existingItem == null) return CopyTargetDecision.PROCEED_UPLOAD
        val existingDigest = existingDigestSupplier(existingItem)
        return if (sourceDigest.equals(existingDigest, true)) {
            CopyTargetDecision.REUSE_EXISTING
        } else {
            CopyTargetDecision.CONFLICT_DIFFERENT_CONTENT
        }
    }

    fun executeCopy(
        sourceDigest: String,
        findTarget: () -> DriveItem?,
        computeDigest: (DriveItem) -> String,
        performUpload: () -> Unit
    ): DriveItem {
        val existing = findTarget()
        when (evaluateExisting(existing, sourceDigest, computeDigest)) {
            CopyTargetDecision.REUSE_EXISTING -> return requireNotNull(existing)
            CopyTargetDecision.CONFLICT_DIFFERENT_CONTENT -> {
                throw DriveApiException("OneDrive copy target already exists with different content")
            }
            CopyTargetDecision.PROCEED_UPLOAD -> Unit
        }
        try {
            performUpload()
        } catch (failure: DriveApiException) {
            val fallback = findTarget()
            if (fallback != null && computeDigest(fallback).equals(sourceDigest, true)) {
                return fallback
            }
            throw failure
        }
        val copied = findTarget()
            ?: throw DriveApiException("OneDrive did not confirm the copied item")
        val copiedDigest = computeDigest(copied)
        if (!sourceDigest.equals(copiedDigest, true)) throw DriveApiException("OneDrive copy verification failed")
        return copied
    }
}

internal object OneDriveUrlPolicy {
    fun graph(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw DriveApiException("Invalid Microsoft Graph URL")
        if (uri.scheme != "https" || !uri.host.equals("graph.microsoft.com", true) || !uri.path.startsWith("/v1.0/")) {
            throw DriveApiException("Untrusted Microsoft Graph continuation URL")
        }
        return value
    }

    fun upload(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw DriveApiException("Invalid OneDrive upload URL")
        val host = uri.host?.lowercase().orEmpty()
        val allowed = isDomainOrSubdomain(host, "1drv.com") ||
            isDomainOrSubdomain(host, "1drv.ms") ||
            isDomainOrSubdomain(host, "onedrive.com") ||
            isDomainOrSubdomain(host, "sharepoint.com") ||
            isDomainOrSubdomain(host, "sharepoint-df.com") ||
            isDomainOrSubdomain(host, "microsoftusercontent.com") ||
            isDomainOrSubdomain(host, "live.com") ||
            isDomainOrSubdomain(host, "livefilestore.com") ||
            isDomainOrSubdomain(host, "storage.live.com") ||
            isDomainOrSubdomain(host, "blob.core.windows.net") ||
            isDomainOrSubdomain(host, "cloud.microsoft") ||
            isDomainOrSubdomain(host, "microsoftpersonalcontent.com") ||
            isDomainOrSubdomain(host, "microsoft.com") ||
            isDomainOrSubdomain(host, "office.net") ||
            isDomainOrSubdomain(host, "office365.com") ||
            host == "graph.microsoft.com"
        if (uri.scheme != "https" || !allowed) {
            Log.e("OneDriveApi", "Untrusted upload URL host: '$host'")
            throw DriveApiException("Untrusted OneDrive upload URL")
        }
        return value
    }

    fun download(value: String?): String {
        val uri = value?.let { runCatching { URI(it) }.getOrNull() }
            ?: throw DriveApiException("OneDrive returned an invalid download URL")
        val host = uri.host?.lowercase().orEmpty()
        val allowed = isDomainOrSubdomain(host, "1drv.com") ||
            isDomainOrSubdomain(host, "1drv.ms") ||
            isDomainOrSubdomain(host, "onedrive.com") ||
            isDomainOrSubdomain(host, "sharepoint.com") ||
            isDomainOrSubdomain(host, "sharepoint-df.com") ||
            isDomainOrSubdomain(host, "microsoftusercontent.com") ||
            isDomainOrSubdomain(host, "microsoftpersonalcontent.com") ||
            isDomainOrSubdomain(host, "live.com") ||
            isDomainOrSubdomain(host, "livefilestore.com") ||
            isDomainOrSubdomain(host, "storage.live.com") ||
            isDomainOrSubdomain(host, "blob.core.windows.net") ||
            isDomainOrSubdomain(host, "core.windows.net") ||
            isDomainOrSubdomain(host, "cloud.microsoft") ||
            isDomainOrSubdomain(host, "microsoft.com") ||
            isDomainOrSubdomain(host, "office.net") ||
            isDomainOrSubdomain(host, "office365.com") ||
            host == "graph.microsoft.com"
        if (uri.scheme != "https" || !allowed) {
            Log.e("OneDriveApi", "Untrusted download URL host: '$host'")
            throw DriveApiException("Untrusted OneDrive download URL ($host)")
        }
        return value
    }

    fun sanitizeUrl(url: URL): String = url.host.orEmpty()

    private fun isDomainOrSubdomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}

/** Microsoft Graph adapter. Bearer tokens are sent only to graph.microsoft.com. */
class OneDriveApi(
    private val tokenProvider: (forceRefresh: Boolean) -> String,
    private val cacheDirectory: File,
    private val cancelled: AtomicBoolean = AtomicBoolean(false)
) : DriveGateway {
    constructor(accessToken: String, cacheDirectory: File, cancelled: AtomicBoolean = AtomicBoolean(false)) :
        this({ accessToken }, cacheDirectory, cancelled)

    @Volatile private var currentAccessToken: String = tokenProvider(false)

    private fun refreshToken(): String {
        val fresh = tokenProvider(true)
        currentAccessToken = fresh
        return fresh
    }

    override val providerId: String = "onedrive"
    override val providerName: String = "OneDrive"
    override val folderMimeType: String = FOLDER_MIME_TYPE
    override fun shouldSync(item: DriveItem): Boolean = !isFolder(item)

    fun root(): DriveVaultRoot {
        val item = requestJson("GET", "$GRAPH/me/drive/root?\$select=id,name,folder,eTag,cTag")
        return DriveVaultRoot(item.getString("id"), item.optString("name").ifBlank { "OneDrive" })
    }

    override fun listChildren(parentId: String): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        var next: String? = "$GRAPH/me/drive/items/${path(parentId)}/children?\$select=id,name,folder,file,eTag,cTag,size&\$top=200"
        while (next != null) {
            val page = requestJson("GET", OneDriveUrlPolicy.graph(next))
            val values = page.getJSONArray("value")
            for (index in 0 until values.length()) result += item(values.getJSONObject(index))
            next = page.optString("@odata.nextLink").takeIf { it.isNotBlank() }?.let(OneDriveUrlPolicy::graph)
        }
        return result
    }

    override fun createFolder(parentId: String, name: String): DriveItem {
        val body = JSONObject().apply {
            put("name", name)
            put("folder", JSONObject())
            put("@microsoft.graph.conflictBehavior", "fail")
        }
        return item(requestJson("POST", "$GRAPH/me/drive/items/${path(parentId)}/children", body.toString()))
    }

    override fun upload(parentId: String, name: String, mimeType: String, input: InputStream) {
        withUploadFile(input) { file ->
            if (file.length() <= SIMPLE_UPLOAD_LIMIT) {
                uploadFile("$GRAPH/me/drive/items/${path(parentId)}:/${path(name)}:/content", file, null)
            } else {
                uploadSession(parentId, name, file)
            }
        }
    }

    override fun replace(id: String, expectedEtag: String?, mimeType: String, input: InputStream) {
        withUploadFile(input) { file ->
            if (file.length() <= SIMPLE_UPLOAD_LIMIT) {
                uploadFile("$GRAPH/me/drive/items/${path(id)}/content", file, expectedEtag)
            } else {
                uploadSessionForItem(id, file, expectedEtag)
            }
        }
    }

    /** A verified server copy is not required by the engine; streamed copy avoids trusting async monitor URLs. */
    override fun copy(id: String, parentId: String, name: String): DriveItem {
        val source = refresh(id)
        return withUploadFile(download(source)) { file ->
            val sourceDigest = file.inputStream().use(::md5)
            OneDriveCopyPolicy.executeCopy(
                sourceDigest = sourceDigest,
                findTarget = { listChildren(parentId).firstOrNull { it.name == name } },
                computeDigest = { item -> download(item).use(::md5) },
                performUpload = {
                    if (file.length() <= SIMPLE_UPLOAD_LIMIT) {
                        uploadFile("$GRAPH/me/drive/items/${path(parentId)}:/${path(name)}:/content", file, null)
                    } else {
                        uploadSession(parentId, name, file)
                    }
                }
            )
        }
    }

    /** Microsoft Graph DELETE moves ordinary driveItems to the OneDrive recycle bin. */
    override fun trash(id: String, expectedEtag: String?) {
        withGraphConnection("DELETE", "$GRAPH/me/drive/items/${path(id)}") { connection ->
            expectedEtag?.let { connection.setRequestProperty("If-Match", it) }
            requireSuccess(connection, setOf(204, 404))
        }
    }

    override fun refresh(id: String): DriveItem = revision(id).item

    override fun revision(id: String): DriveRevision {
        val (json, etag) = requestJsonWithEtag(
            "GET",
            "$GRAPH/me/drive/items/${path(id)}?\$select=id,name,folder,file,eTag,cTag,size"
        )
        val value = item(json)
        return DriveRevision(value, etag ?: value.revision)
    }

    override fun download(item: DriveItem): InputStream {
        var graph = graphConnection("GET", "$GRAPH/me/drive/items/${path(item.id)}/content").apply {
            instanceFollowRedirects = false
        }
        var code = graph.responseCode
        if (code == 401 || code == 403) {
            graph.disconnect()
            refreshToken()
            graph = graphConnection("GET", "$GRAPH/me/drive/items/${path(item.id)}/content").apply {
                instanceFollowRedirects = false
            }
            code = graph.responseCode
        }
        if (code in 200..299) return DisconnectingInputStream(BufferedInputStream(graph.inputStream), graph, cancelled)
        if (code in 300..399) {
            val location = graph.getHeaderField("Location")
            graph.disconnect()
            val safe = OneDriveUrlPolicy.download(location)
            val download = (URL(safe).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            requireSuccess(download, (200..299).toSet(), authenticatedGraph = false)
            return DisconnectingInputStream(BufferedInputStream(download.inputStream), download, cancelled)
        }
        requireSuccess(graph, (200..299).toSet())
        throw DriveApiException("OneDrive download failed")
    }

    private fun uploadSession(parentId: String, name: String, file: File) {
        val body = JSONObject().put("item", JSONObject()
            .put("@microsoft.graph.conflictBehavior", "fail")
            .put("name", name))
        val session = requestJson(
            "POST",
            "$GRAPH/me/drive/items/${path(parentId)}:/${path(name)}:/createUploadSession",
            body.toString()
        )
        sendUploadChunks(OneDriveUrlPolicy.upload(session.getString("uploadUrl")), file)
    }

    private fun uploadSessionForItem(id: String, file: File, expectedEtag: String?) {
        val body = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
        val session = requestJson(
            "POST",
            "$GRAPH/me/drive/items/${path(id)}/createUploadSession",
            body.toString(),
            expectedEtag
        )
        sendUploadChunks(OneDriveUrlPolicy.upload(session.getString("uploadUrl")), file)
    }

    private fun sendUploadChunks(uploadUrl: String, file: File) {
        RandomAccessFile(file, "r").use { source ->
            val total = source.length()
            var offset = 0L
            val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
            while (offset < total) {
                if (cancelled.get()) throw SyncTransferCancelled()
                source.seek(offset)
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), total - offset).toInt())
                if (count <= 0) throw DriveApiException("OneDrive upload cache became unreadable")
                val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setFixedLengthStreamingMode(count)
                    setRequestProperty("Content-Length", count.toString())
                    setRequestProperty("Content-Range", "bytes $offset-${offset + count - 1}/$total")
                    setRequestProperty("Content-Type", "application/octet-stream")
                    instanceFollowRedirects = false
                }
                try {
                    connection.outputStream.use { it.write(buffer, 0, count) }
                    requireSuccess(connection, setOf(200, 201, 202), authenticatedGraph = false)
                    connection.inputStream?.close()
                } finally {
                    connection.disconnect()
                }
                offset += count
            }
            if (cancelled.get()) throw SyncTransferCancelled()
        }
    }

    private fun uploadFile(url: String, file: File, expectedEtag: String?) {
        withGraphConnection("PUT", url) { connection ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            OneDriveWritePrecondition.headers(expectedEtag).forEach(connection::setRequestProperty)
            try {
                file.inputStream().use { input ->
                    connection.outputStream.use { output -> CancellableStreamCopy.copy(input, output, cancelled, COPY_BUFFER_BYTES) }
                }
            } catch (ioe: java.io.IOException) {
                try {
                    requireSuccess(connection, setOf(200, 201))
                } catch (apiEx: Exception) {
                    throw apiEx
                }
                throw ioe
            }
            requireSuccess(connection, setOf(200, 201))
            connection.inputStream.close()
        }
    }

    private inline fun <T> withUploadFile(input: InputStream, block: (File) -> T): T {
        val file = File.createTempFile("onedrive-upload-", ".tmp", cacheDirectory)
        return try {
            input.use { inStream ->
                file.outputStream().use { output -> CancellableStreamCopy.copy(inStream, output, cancelled, COPY_BUFFER_BYTES) }
            }
            block(file)
        } finally {
            if (file.exists() && !file.delete()) throw DriveApiException("OneDrive transfer cache cleanup failed")
        }
    }

    private fun item(value: JSONObject): DriveItem {
        val folder = value.has("folder")
        val mimeType = if (folder) FOLDER_MIME_TYPE else value.optJSONObject("file")?.optString("mimeType")
            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val revision = value.optString("eTag").takeIf { it.isNotBlank() }
            ?: value.optString("cTag").takeIf { it.isNotBlank() }
        return DriveItem(value.getString("id"), value.getString("name"), mimeType, null, null, revision)
    }

    private fun requestJson(method: String, url: String, body: String? = null, expectedEtag: String? = null): JSONObject =
        requestJsonWithEtag(method, url, body, expectedEtag).first

    private fun requestJsonWithEtag(method: String, url: String, body: String? = null, expectedEtag: String? = null): Pair<JSONObject, String?> =
        withGraphConnection(method, OneDriveUrlPolicy.graph(url)) { connection ->
            expectedEtag?.let { connection.setRequestProperty("If-Match", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            requireSuccess(connection, (200..299).toSet())
            val result = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { JSONObject(it.readText()) }
            result to connection.getHeaderField("ETag")
        }

    private fun <T> withGraphConnection(method: String, url: String, block: (HttpURLConnection) -> T): T {
        var connection = graphConnection(method, OneDriveUrlPolicy.graph(url))
        return try {
            try {
                block(connection)
            } catch (ex: OneDriveReloginRequired) {
                connection.disconnect()
                refreshToken()
                connection = graphConnection(method, OneDriveUrlPolicy.graph(url))
                block(connection)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun graphConnection(method: String, url: String): HttpURLConnection =
        (URL(OneDriveUrlPolicy.graph(url)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $currentAccessToken")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = false
        }

    private fun sanitizeUrl(url: URL): String = OneDriveUrlPolicy.sanitizeUrl(url)

    private fun requireSuccess(connection: HttpURLConnection, expected: Set<Int>, authenticatedGraph: Boolean = true) {
        val code = connection.responseCode
        if (code !in expected) {
            Log.e("OneDriveApi", "HTTP $code for ${connection.requestMethod} ${sanitizeUrl(connection.url)}")
            if (authenticatedGraph && (code == 401 || code == 403)) throw OneDriveReloginRequired()
            throw DriveApiException(when (code) {
                401, 403 -> "OneDrive transfer session is no longer available"
                404 -> "The selected OneDrive item is no longer available"
                409, 412 -> "OneDrive content changed during sync ($code)"
                429 -> "OneDrive is busy; try again later"
                else -> "OneDrive request failed ($code)"
            })
        }
    }

    private fun path(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun md5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            if (cancelled.get()) throw SyncTransferCancelled()
            val count = input.read(buffer)
            if (count < 0) return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            digest.update(buffer, 0, count)
        }
    }

    private class DisconnectingInputStream(
        private val delegate: InputStream,
        private val connection: HttpURLConnection,
        private val cancelled: AtomicBoolean
    ) : InputStream() {
        override fun read(): Int {
            if (cancelled.get()) throw SyncTransferCancelled()
            return delegate.read()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cancelled.get()) throw SyncTransferCancelled()
            return delegate.read(buffer, offset, length)
        }
        override fun close() { try { delegate.close() } finally { connection.disconnect() } }
    }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.microsoft.folder"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val SIMPLE_UPLOAD_LIMIT = 4L * 1024L * 1024L
        private const val UPLOAD_CHUNK_BYTES = 5 * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 32 * 1024

        fun cleanupOrphans(cacheDirectory: File): Boolean {
            if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return false
            if (!cacheDirectory.isDirectory) return false
            return OneDriveCacheCleanupPolicy.clean(cacheDirectory.listFiles())
        }
    }
}
