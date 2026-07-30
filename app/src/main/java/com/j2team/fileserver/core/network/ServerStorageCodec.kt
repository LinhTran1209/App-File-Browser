package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.AdminDirectoryEntry
import com.j2team.fileserver.core.model.AdminDirectoryListing
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.model.ServerUserPermissions
import org.json.JSONArray
import org.json.JSONObject

internal object ServerStorageCodec {
    fun user(item: JSONObject): ServerUser {
        val perm = item.optJSONObject("perm") ?: JSONObject()
        return ServerUser(
            id = item.optLong("id"),
            username = item.optString("username"),
            scope = item.optString("scope", "/"),
            quotaBytes = item.optLong("quotaBytes"),
            quotaUsedBytes = item.optLong("quotaUsedBytes"),
            quotaRemainingBytes = item.optLong("quotaRemainingBytes"),
            quotaUnlimited = item.optBoolean("quotaUnlimited", item.optLong("quotaBytes") == 0L),
            scopeMissing = item.optBoolean("scopeMissing"),
            locale = item.optString("locale", "en"),
            admin = perm.optBoolean("admin", item.optBoolean("admin")),
            lockPassword = item.optBoolean("lockPassword"),
            hideDotfiles = item.optBoolean("hideDotfiles"),
            singleClick = item.optBoolean("singleClick"),
            redirectAfterCopyMove = item.optBoolean("redirectAfterCopyMove"),
            dateFormat = item.optBoolean("dateFormat"),
            aceEditorTheme = item.optString("aceEditorTheme"),
            permissions = ServerUserPermissions(
                create = perm.optBoolean("create"),
                delete = perm.optBoolean("delete"),
                download = perm.optBoolean("download"),
                modify = perm.optBoolean("modify"),
                rename = perm.optBoolean("rename"),
                share = perm.optBoolean("share"),
            ),
        )
    }

    fun directory(item: JSONObject): AdminDirectoryListing {
        val directories = item.optJSONArray("directories") ?: JSONArray()
        return AdminDirectoryListing(
            path = item.optString("path", "/"),
            parent = item.optString("parent", "/"),
            directories = buildList {
                for (index in 0 until directories.length()) {
                    val entry = directories.getJSONObject(index)
                    add(AdminDirectoryEntry(entry.optString("name"), entry.optString("path")))
                }
            },
            total = item.optLong("total"),
            used = item.optLong("used"),
            free = item.optLong("free"),
            contentBytes = item.optLong("contentBytes"),
        )
    }

    fun owners(item: JSONObject): List<String> {
        val usernames = item.optJSONArray("usernames") ?: JSONArray()
        return buildList {
            for (index in 0 until usernames.length()) {
                usernames.optString(index).trim().takeIf { it.isNotEmpty() && it !in this }?.let(::add)
            }
        }
    }

    fun userMutation(
        user: ServerUser,
        newPassword: String,
        creating: Boolean,
        currentPassword: String = "",
        profileOnly: Boolean = false,
    ): JSONObject {
        val completeUser = userJson(user).apply {
            if (newPassword.isNotBlank()) put("password", newPassword)
        }
        val fields = JSONArray().apply {
            if (!creating) {
                if (!profileOnly) {
                    put("username")
                    put("scope")
                    put("quotaBytes")
                    put("perm")
                    put("lockPassword")
                }
                put("hideDotfiles")
                put("singleClick")
                put("redirectAfterCopyMove")
                put("dateFormat")
                if (newPassword.isNotBlank()) put("password")
            }
        }
        val data = if (creating) {
            completeUser
        } else {
            JSONObject().apply {
                for (index in 0 until fields.length()) {
                    val field = fields.getString(index)
                    put(field, completeUser.get(field))
                }
                put("id", user.id)
            }
        }
        return JSONObject()
            .put("what", "user")
            .put("which", fields)
            .put("createUserDir", false)
            .put("current_password", currentPassword)
            .put("data", data)
    }

    fun userJson(user: ServerUser): JSONObject = JSONObject()
        .put("id", user.id)
        .put("username", user.username)
        .put("scope", user.scope)
        .put("quotaBytes", user.quotaBytes)
        .put("locale", user.locale)
        .put("lockPassword", user.lockPassword)
        .put("hideDotfiles", user.hideDotfiles)
        .put("singleClick", user.singleClick)
        .put("redirectAfterCopyMove", user.redirectAfterCopyMove)
        .put("dateFormat", user.dateFormat)
        .put("aceEditorTheme", user.aceEditorTheme)
        .put(
            "perm",
            JSONObject()
                .put("admin", user.admin)
                .put("create", user.permissions.create)
                .put("delete", user.permissions.delete)
                .put("download", user.permissions.download)
                .put("modify", user.permissions.modify)
                .put("rename", user.permissions.rename)
                .put("share", user.permissions.share),
        )
}
