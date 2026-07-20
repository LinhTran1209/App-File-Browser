package com.j2team.fileserver.feature.servers

import android.content.Context
import com.j2team.fileserver.core.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE)

    fun all(): List<ServerProfile> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val json = JSONArray(raw)
        return buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                add(ServerProfile(item.getString("id"), item.getString("name"), item.getString("scheme"), item.getString("host"), item.getInt("port"), item.optString("path", "/")))
            }
        }
    }

    fun save(profile: ServerProfile): ServerProfile {
        val items = all().filterNot { it.id == profile.id } + profile
        val json = JSONArray().apply { items.forEach { put(it.toJson()) } }
        prefs.edit().putString("items", json.toString()).apply()
        return profile
    }

    fun create(name: String, scheme: String, host: String, port: Int, path: String) = save(
        ServerProfile(UUID.randomUUID().toString(), name.ifBlank { "$host:$port" }, scheme, host, port, path.ifBlank { "/" }),
    )

    fun delete(id: String) {
        val json = JSONArray().apply { all().filterNot { it.id == id }.forEach { put(it.toJson()) } }
        prefs.edit().putString("items", json.toString()).apply()
    }

    private fun ServerProfile.toJson() = JSONObject().apply {
        put("id", id); put("name", displayName); put("scheme", scheme); put("host", host); put("port", port); put("path", basePath)
    }
}
