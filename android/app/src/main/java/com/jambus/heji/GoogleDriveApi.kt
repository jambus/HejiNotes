package com.jambus.heji

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val md5: String? = null,
    val version: Long? = null,
    /** Provider revision is opaque. Google numeric versions remain available for legacy baselines. */
    val revision: String? = version?.toString()
)
data class DriveRevision(val item: DriveItem, val etag: String?)

class DriveApiException(message: String) : Exception(message)

/** Narrow Drive REST adapter. It deliberately keeps credentials and network URLs out of logs. */
interface DriveGateway {
    val providerId: String get() = "google_drive"
    val providerName: String get() = "Google Drive"
    val folderMimeType: String get() = GoogleDriveApi.FOLDER_MIME_TYPE
    fun isFolder(item: DriveItem): Boolean = item.mimeType == folderMimeType
    fun shouldSync(item: DriveItem): Boolean = !item.mimeType.startsWith("application/vnd.google-apps.")
    fun listChildren(parentId: String): List<DriveItem>
    fun createFolder(parentId: String, name: String): DriveItem
    fun upload(parentId: String, name: String, mimeType: String, input: InputStream)
    fun replace(id: String, expectedEtag: String?, mimeType: String, input: InputStream)
    fun copy(id: String, parentId: String, name: String): DriveItem
    fun trash(id: String, expectedEtag: String? = null)
    fun refresh(id: String): DriveItem
    fun revision(id: String): DriveRevision
    fun download(item: DriveItem): InputStream
}

class GoogleDriveApi(private val accessToken: String) : DriveGateway {
    override fun listChildren(parentId: String): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val query = "'$parentId' in parents and trashed = false"
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?q=")
                append(encode(query))
                append("&pageSize=1000&fields=")
                append(encode("nextPageToken,files(id,name,mimeType,md5Checksum,version)"))
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val response = request("GET", url)
            val files = response.getJSONArray("files")
            for (index in 0 until files.length()) {
                val value = files.getJSONObject(index)
                result += DriveItem(
                    value.getString("id"),
                    value.getString("name"),
                    value.getString("mimeType"),
                    value.optString("md5Checksum").takeIf { it.isNotBlank() },
                    value.optLong("version").takeIf { value.has("version") }
                )
            }
            pageToken = response.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return result
    }

    override fun createFolder(parentId: String, name: String): DriveItem {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            put("parents", JSONArray().put(parentId))
        }
        val response = request("POST", "https://www.googleapis.com/drive/v3/files", metadata.toString())
        return DriveItem(response.getString("id"), response.getString("name"), FOLDER_MIME_TYPE)
    }

    override fun upload(parentId: String, name: String, mimeType: String, input: InputStream) {
        val boundary = "heji-notes-${System.nanoTime()}"
        val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        withConnection("POST", url) { connection ->
            connection.doOutput = true
            connection.setChunkedStreamingMode(32 * 1024)
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connection.outputStream.use { output ->
                val metadata = JSONObject().apply {
                    put("name", name)
                    put("parents", JSONArray().put(parentId))
                }.toString()
                output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
                output.write(metadata.toByteArray(StandardCharsets.UTF_8))
                output.write("\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
                copy(input, output)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            requireSuccess(connection)
        }
    }

    override fun replace(id: String, expectedEtag: String?, mimeType: String, input: InputStream) {
        uploadMultipart("PATCH", "https://www.googleapis.com/upload/drive/v3/files/${encode(id)}?uploadType=multipart", null, mimeType, input, expectedEtag)
    }

    override fun copy(id: String, parentId: String, name: String): DriveItem {
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(parentId))
        }
        val response = request(
            "POST",
            "https://www.googleapis.com/drive/v3/files/${encode(id)}/copy",
            metadata.toString()
        )
        return DriveItem(
            response.getString("id"),
            response.getString("name"),
            response.getString("mimeType"),
            response.optString("md5Checksum").takeIf { it.isNotBlank() }
        )
    }

    /** Recoverable removal used only by an explicit, verified note-bundle move. */
    override fun trash(id: String, expectedEtag: String?) {
        val metadata = JSONObject().put("trashed", true)
        request("PATCH", "https://www.googleapis.com/drive/v3/files/${encode(id)}", metadata.toString(), expectedEtag)
    }

    override fun refresh(id: String): DriveItem {
        return revision(id).item
    }

    override fun revision(id: String): DriveRevision {
        val (value, etag) = requestWithEtag("GET", "https://www.googleapis.com/drive/v3/files/${encode(id)}?fields=id,name,mimeType,md5Checksum,version")
        return DriveRevision(DriveItem(value.getString("id"), value.getString("name"), value.getString("mimeType"),
            value.optString("md5Checksum").takeIf { it.isNotBlank() }, value.optLong("version").takeIf { value.has("version") }), etag)
    }

    override fun download(item: DriveItem): InputStream {
        val connection = open("GET", "https://www.googleapis.com/drive/v3/files/${encode(item.id)}?alt=media")
        try {
            requireSuccess(connection)
            return DisconnectingInputStream(BufferedInputStream(connection.inputStream), connection)
        } catch (failure: Exception) {
            connection.disconnect()
            throw failure
        }
    }

    private fun request(method: String, url: String, body: String? = null, expectedEtag: String? = null): JSONObject =
        requestWithEtag(method, url, body, expectedEtag).first

    private fun requestWithEtag(method: String, url: String, body: String? = null, expectedEtag: String? = null): Pair<JSONObject, String?> =
        withConnection(method, url) { connection ->
            expectedEtag?.let { connection.setRequestProperty("If-Match", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            requireSuccess(connection)
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { JSONObject(it.readText()) } to connection.getHeaderField("ETag")
        }

    private fun uploadMultipart(
        method: String,
        url: String,
        metadata: JSONObject?,
        mimeType: String,
        input: InputStream,
        expectedEtag: String? = null
    ) {
        val boundary = "heji-notes-${System.nanoTime()}"
        withConnection(method, url) { connection ->
            connection.doOutput = true
            expectedEtag?.let { connection.setRequestProperty("If-Match", it) }
            connection.setChunkedStreamingMode(32 * 1024)
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
                output.write((metadata ?: JSONObject()).toString().toByteArray(StandardCharsets.UTF_8))
                output.write("\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
                copy(input, output)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            requireSuccess(connection)
        }
    }

    private fun <T> withConnection(method: String, url: String, block: (HttpURLConnection) -> T): T {
        val connection = open(method, url)
        return try { block(connection) } finally { connection.disconnect() }
    }

    private fun open(method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

    private fun requireSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.errorStream?.close()
            throw DriveApiException(
                when (code) {
                    401, 403 -> "Google Drive authorization is no longer available"
                    404 -> "The selected Google Drive folder is no longer available"
                    429 -> "Google Drive is busy; try again later"
                    else -> "Google Drive request failed ($code)"
                }
            )
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class DisconnectingInputStream(
        private val delegate: InputStream,
        private val connection: HttpURLConnection
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun close() {
            try { delegate.close() } finally { connection.disconnect() }
        }
    }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
