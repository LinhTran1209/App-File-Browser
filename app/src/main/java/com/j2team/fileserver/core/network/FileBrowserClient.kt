package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class FileBrowserClient {
    fun login(profile: ServerProfile, username: String, password: String): Result<String> = runCatching {
        val connection = open(profile.endpoint + "api/login", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }
        require(connection.responseCode in 200..299) { "Login failed (${connection.responseCode})" }
        connection.inputStream.bufferedReader().use { it.readText().trim('"', '\n', ' ') }
    }

    fun list(profile: ServerProfile, token: String? = null, path: String = "/"): Result<List<RemoteResource>> = runCatching {
        val url = profile.endpoint.trimEnd('/') + "/api/resources" + if (path.startsWith("/")) path else "/$path"
        val connection = open(url, "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        require(connection.responseCode in 200..299) { "Unable to list files (${connection.responseCode})" }
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(RemoteResource(item.optString("name"), item.optString("path", path), item.optBoolean("isDir"), item.optLong("size")))
            }
        }
    }

    private fun open(url: String, method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = 15_000
        useCaches = false
    }
}
