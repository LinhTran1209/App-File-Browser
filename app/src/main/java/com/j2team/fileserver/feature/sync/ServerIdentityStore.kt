package com.j2team.fileserver.feature.sync

import android.content.Context
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.FileBrowserClient
import org.json.JSONObject

class ServerIdentityStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("server_identities", Context.MODE_PRIVATE)

    fun get(profileId: String): ServerAccountIdentity? = prefs.getString(profileId, null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            ServerAccountIdentity(json.getString("serverId"), json.getLong("userId"))
        }.getOrNull()
    }

    fun put(profileId: String, identity: ServerAccountIdentity) {
        val json = JSONObject().put("serverId", identity.serverId).put("userId", identity.userId)
        prefs.edit().putString(profileId, json.toString()).apply()
    }

    fun linkLegacyFolders(profile: ServerProfile, identity: ServerAccountIdentity, transport: FileBrowserClient) {
        val folders = SyncFolderStore(context)
        val tokens = EncryptedSyncTokenStore(context)
        folders.all().filter { it.identityOrNull() == null }.forEach { folder ->
            val rawToken = tokens.get(folder.id) ?: return@forEach
            val verified = transport.syncIdentityResult(profile, "sync:$rawToken").value
            if (verified == identity) {
                put(folder.profileId, identity)
                folders.save(folder.copy(serverId = identity.serverId, userId = identity.userId))
            }
        }
    }

    fun sameAccount(leftProfileId: String, rightProfileId: String): Boolean {
        val left = get(leftProfileId) ?: return leftProfileId == rightProfileId
        val right = get(rightProfileId) ?: return leftProfileId == rightProfileId
        return left == right
    }

    fun candidates(folder: SyncFolder, profiles: List<ServerProfile>): List<ServerProfile> {
        val identity = folder.identityOrNull() ?: get(folder.profileId)
        val matching = if (identity == null) emptyList() else profiles.filter { get(it.id) == identity }
        val available = (matching + profiles.filter { it.id == folder.profileId }).distinctBy { it.id }
        return available.sortedWith(compareBy<ServerProfile>({ endpointPriority(it.host) }, { if (it.id == folder.profileId) 0 else 1 }))
    }

    private fun endpointPriority(host: String): Int = when {
        host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.16.") -> 0
        host.startsWith("100.") -> 1
        else -> 2
    }
}

fun SyncFolder.identityOrNull(): ServerAccountIdentity? =
    if (serverId.isBlank() || userId <= 0) null else ServerAccountIdentity(serverId, userId)
