package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.ApiResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FileBrowserClient {
    /** Downloads a remote resource to [destination] without buffering it in memory. */
    fun download(
        profile: ServerProfile,
        token: String? = null,
        remotePath: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = runCatching {
        require(remotePath.isNotBlank()) { "Remote path is required" }
        val connection = open(rawUrl(profile, remotePath), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        require(connection.responseCode in 200..299) { "Download failed (${connection.responseCode})" }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), ".${destination.name}.part")
        val total = connection.contentLengthLong
        var copied = 0L
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress?.invoke(copied, total)
                    }
                }
            }
            if (destination.exists() && !destination.delete()) throw IOException("Unable to replace destination")
            if (!temporary.renameTo(destination)) throw IOException("Unable to finalize download")
            destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Uploads one local file as multipart/form-data to a remote directory. */
    fun upload(
        profile: ServerProfile,
        token: String? = null,
        parentPath: String = "/",
        file: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = runCatching {
        require(file.isFile) { "Upload file does not exist: ${file.name}" }
        val boundary = "----FileServer${System.currentTimeMillis()}"
        val connection = open(apiUrl(profile, "/api/resources", parentPath), "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("X-Auth", token)
        }
        val prefix = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val suffix = "\r\n--$boundary--\r\n"
        val total = prefix.toByteArray().size + file.length() + suffix.toByteArray().size
        var sent = 0L
        connection.outputStream.use { output ->
            val head = prefix.toByteArray()
            output.write(head); sent += head.size; onProgress?.invoke(sent, total)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    onProgress?.invoke(sent, total)
                }
            }
            val tail = suffix.toByteArray()
            output.write(tail); sent += tail.size; onProgress?.invoke(sent, total)
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        require(code in 200..299) { "Upload failed ($code)" }
        body
    }

    fun login(profile: ServerProfile, username: String, password: String): Result<String> = runCatching {
        val connection = open(profile.endpoint + "api/login", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }
        require(connection.responseCode in 200..299) { "Login failed (${connection.responseCode})" }
        connection.inputStream.bufferedReader().use { it.readText().trim('"', '\n', ' ') }
    }

    fun list(profile: ServerProfile, token: String? = null, path: String = "/"): Result<List<RemoteResource>> =
        listResult(profile, token, path).toResult()

    fun listResult(profile: ServerProfile, token: String? = null, path: String = "/"): ApiResult<List<RemoteResource>> = try {
        val url = profile.endpoint.trimEnd('/') + "/api/resources" + if (path.startsWith("/")) path else "/$path"
        val connection = open(url, "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Unable to list files ($code)"))
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("items") ?: JSONArray()
        ApiResult(code, buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(RemoteResource(item.optString("name"), item.optString("path", path), item.optBoolean("isDir"), item.optLong("size")))
            }
        })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun rawUrl(profile: ServerProfile, remotePath: String): String =
        apiUrl(profile, "/api/raw", remotePath)

    fun readText(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        maxBytes: Int = 1_000_000,
    ): Result<String> = runCatching {
        val connection = open(rawUrl(profile, remotePath), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        require(connection.responseCode in 200..299) { "Unable to open file (${connection.responseCode})" }
        connection.inputStream.buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= maxBytes) { "Text file is too large to preview" }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun open(url: String, method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = 15_000
        useCaches = false
    }

    private fun apiUrl(profile: ServerProfile, apiPath: String, path: String): String {
        val cleanPath = path.trim().let { if (it.isEmpty() || it == "/") "/" else if (it.startsWith("/")) it else "/$it" }
        return profile.endpoint.trimEnd('/') + apiPath + encodePath(cleanPath)
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
