package com.j2team.fileserver.feature.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SyncFolderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sync_folders", Context.MODE_PRIVATE)
    private val identities = ServerIdentityStore(context)

    fun all(): List<SyncFolder> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        buildList { for (i in 0 until array.length()) add(folder(array.getJSONObject(i))) }
    }.getOrDefault(emptyList())

    fun forProfile(profileId: String): List<SyncFolder> = all().filter { folder ->
        folder.profileId == profileId || identities.sameAccount(folder.profileId, profileId) ||
            folder.identityOrNull()?.let { it == identities.get(profileId) } == true
    }

    fun migrateProfile(profileId: String, identity: ServerAccountIdentity) {
        val changed = all().map { folder ->
            if (folder.profileId == profileId && folder.identityOrNull() == null) folder.copy(serverId = identity.serverId, userId = identity.userId)
            else folder
        }
        persist(changed)
    }

    fun find(id: String): SyncFolder? = all().firstOrNull { it.id == id }

    fun create(profileId: String, name: String, localTreeUri: String, localDisplayPath: String, remotePath: String): SyncFolder {
        val identity = identities.get(profileId)
        return save(SyncFolder(UUID.randomUUID().toString(), profileId, identity?.serverId.orEmpty(), identity?.userId ?: 0, name, localTreeUri, localDisplayPath, remotePath))
    }

    fun save(folder: SyncFolder): SyncFolder {
        persist(all().filterNot { it.id == folder.id } + folder)
        return folder
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
        baselineFile(id).delete()
    }

    fun baseline(id: String): SyncBaseline = runCatching {
        val body = JSONObject(baselineFile(id).readText())
        SyncBaseline(entries(body.optJSONArray("local")), entries(body.optJSONArray("remote")))
    }.getOrDefault(SyncBaseline())

    fun saveBaseline(id: String, baseline: SyncBaseline) {
        val target = baselineFile(id)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.part")
        temporary.writeText(JSONObject().put("local", array(baseline.local)).put("remote", array(baseline.remote)).toString())
        if (target.exists()) target.delete()
        temporary.renameTo(target)
    }

    private fun persist(items: List<SyncFolder>) {
        prefs.edit().putString("items", JSONArray().apply { items.forEach { put(json(it)) } }.toString()).apply()
    }

    private fun baselineFile(id: String) = File(context.filesDir, "sync-baselines/$id.json")

    private fun json(value: SyncFolder) = JSONObject().apply {
        put("id", value.id); put("profile", value.profileId); put("serverId", value.serverId); put("userId", value.userId); put("name", value.name)
        put("localUri", value.localTreeUri); put("localPath", value.localDisplayPath); put("remote", value.remotePath)
        put("enabled", value.enabled); put("state", value.state.name); put("cursor", value.cursor)
        put("lastSync", value.lastSyncMillis); put("lastFullScan", value.lastFullScanMillis); put("bytes", value.totalBytes); put("error", value.error)
    }

    private fun folder(value: JSONObject) = SyncFolder(
        id = value.getString("id"), profileId = value.getString("profile"), serverId = value.optString("serverId"), userId = value.optLong("userId"), name = value.getString("name"),
        localTreeUri = value.getString("localUri"), localDisplayPath = value.optString("localPath", value.getString("name")),
        remotePath = value.getString("remote"), enabled = value.optBoolean("enabled", true),
        state = runCatching { SyncState.valueOf(value.optString("state", SyncState.Idle.name)) }.getOrDefault(SyncState.Idle),
        cursor = value.optLong("cursor"), lastSyncMillis = value.optLong("lastSync"), lastFullScanMillis = value.optLong("lastFullScan"), totalBytes = value.optLong("bytes"),
        error = value.optString("error").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun array(values: Map<String, SyncEntry>) = JSONArray().apply { values.values.forEach { entry ->
        put(JSONObject().put("path", entry.relativePath).put("dir", entry.directory).put("size", entry.size).put("modified", entry.modified))
    } }

    private fun entries(array: JSONArray?): Map<String, SyncEntry> = buildMap {
        if (array == null) return@buildMap
        for (i in 0 until array.length()) array.getJSONObject(i).let {
            val entry = SyncEntry(it.getString("path"), it.optBoolean("dir"), it.optLong("size"), it.optLong("modified"))
            put(entry.relativePath, entry)
        }
    }
}
