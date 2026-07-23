package com.j2team.fileserver.core.model

enum class ServerSettingsSection { Profile, Shares, Global, Users }

data class ServerUserPermissions(
    val create: Boolean = false,
    val delete: Boolean = false,
    val download: Boolean = false,
    val modify: Boolean = false,
    val rename: Boolean = false,
    val share: Boolean = false,
)

data class ServerUser(
    val id: Long,
    val username: String,
    val scope: String = "/",
    val locale: String = "en",
    val admin: Boolean = false,
    val lockPassword: Boolean = false,
    val hideDotfiles: Boolean = false,
    val singleClick: Boolean = false,
    val redirectAfterCopyMove: Boolean = false,
    val dateFormat: Boolean = false,
    val aceEditorTheme: String = "",
    val permissions: ServerUserPermissions = ServerUserPermissions(),
)

fun ServerUser.effectivePermissions(): ServerUserPermissions =
    if (admin) ServerUserPermissions(true, true, true, true, true, true) else permissions

fun ServerUser.visibleSettingsSections(): List<ServerSettingsSection> = buildList {
    add(ServerSettingsSection.Profile)
    if (admin || permissions.share) add(ServerSettingsSection.Shares)
    if (admin) {
        add(ServerSettingsSection.Global)
        add(ServerSettingsSection.Users)
    }
}

data class ServerGlobalSettings(
    val signup: Boolean = false,
    val createUserDir: Boolean = false,
    val hideLoginButton: Boolean = false,
    val userHomeBasePath: String = "/users",
    val minimumPasswordLength: Int = 3,
    val disableExternalLinks: Boolean = false,
    val disableUsedPercentage: Boolean = false,
    val theme: String = "dark",
    val instanceName: String = "",
    val brandingDirectory: String = "",
    val chunkSize: String = "20MB",
    val retryCount: Int = 5,
    val rawJson: String = "{}",
)
