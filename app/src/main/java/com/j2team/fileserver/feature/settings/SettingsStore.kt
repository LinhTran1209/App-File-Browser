package com.j2team.fileserver.feature.settings

import android.content.Context

enum class AppTheme { System, Light, Dark }
enum class SortOrder { Name, Modified, Size }
enum class AppLanguage(val languageTag: String) { Vietnamese("vi"), English("en") }
enum class FolderIconSet { Classic, Color, Outline }

private const val WRITE_URI_PERMISSION_GRANT = 0x00000002

data class AppSettings(
    val theme: AppTheme = AppTheme.System,
    val sortOrder: SortOrder = SortOrder.Name,
    val ascending: Boolean = true,
    val gridView: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val language: AppLanguage = AppLanguage.Vietnamese,
    val downloadTreeUri: String? = null,
    val folderIconSet: FolderIconSet = FolderIconSet.Classic,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    fun read(): AppSettings {
        val legacy = AppSettings(
            theme = enumValue(prefs.getString("theme", AppTheme.System.name), AppTheme.System),
            sortOrder = enumValue(prefs.getString("sort", SortOrder.Name.name), SortOrder.Name),
            ascending = prefs.getBoolean("ascending", true),
            gridView = prefs.getBoolean("grid", false),
            showHiddenFiles = prefs.getBoolean("hidden", false),
        )
        return resolvePersistedSettings(prefs.getString(SETTINGS_JSON, null), legacy)
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(SETTINGS_JSON, settings.toPersistedJson()).apply()
    }

    private companion object {
        const val SETTINGS_JSON = "settings_json"
    }
}

fun AppSettings.toPersistedJson(): String = buildString {
    append('{')
    appendJsonString("theme", theme.name)
    append(',')
    appendJsonString("sortOrder", sortOrder.name)
    append(',')
    append("\"ascending\":").append(ascending)
    append(',')
    append("\"gridView\":").append(gridView)
    append(',')
    append("\"showHiddenFiles\":").append(showHiddenFiles)
    append(',')
    appendJsonString("language", language.name)
    append(',')
    append("\"downloadTreeUri\":")
    if (downloadTreeUri == null) append("null") else appendJsonValue(downloadTreeUri)
    append(',')
    appendJsonString("folderIconSet", folderIconSet.name)
    append('}')
}

fun decodeSettings(json: String): AppSettings = AppSettings(
    theme = enumValue(json.jsonString("theme"), AppTheme.System),
    sortOrder = enumValue(json.jsonString("sortOrder"), SortOrder.Name),
    ascending = json.jsonBoolean("ascending", true),
    gridView = json.jsonBoolean("gridView", false),
    showHiddenFiles = json.jsonBoolean("showHiddenFiles", false),
    language = enumValue(json.jsonString("language"), AppLanguage.Vietnamese),
    downloadTreeUri = json.jsonString("downloadTreeUri"),
    folderIconSet = enumValue(json.jsonString("folderIconSet"), FolderIconSet.Classic),
)

fun resolvePersistedSettings(persistedJson: String?, legacy: AppSettings): AppSettings =
    persistedJson?.takeIf(::isCompleteSettingsJson)?.let(::decodeSettings) ?: legacy

fun acceptsDownloadTreeGrant(grantFlags: Int): Boolean =
    grantFlags and WRITE_URI_PERMISSION_GRANT != 0

private fun isCompleteSettingsJson(json: String): Boolean =
    json.trim().let { value ->
        value.startsWith('{') && value.endsWith('}') &&
            value.jsonString("theme") != null &&
            value.jsonString("sortOrder") != null &&
            value.jsonBooleanOrNull("ascending") != null &&
            value.jsonBooleanOrNull("gridView") != null &&
            value.jsonBooleanOrNull("showHiddenFiles") != null
    }

private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

private fun StringBuilder.appendJsonString(name: String, value: String) {
    appendJsonValue(name)
    append(':')
    appendJsonValue(value)
}

private fun StringBuilder.appendJsonValue(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun String.jsonString(name: String): String? {
    val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(this) ?: return null
    return match.groupValues[1].replace(Regex("\\\\([\\\\\"nrt])")) { escaped ->
        when (escaped.groupValues[1]) {
            "n" -> "\n"
            "r" -> "\r"
            "t" -> "\t"
            else -> escaped.groupValues[1]
        }
    }
}

private fun String.jsonBoolean(name: String, default: Boolean): Boolean =
    jsonBooleanOrNull(name) ?: default

private fun String.jsonBooleanOrNull(name: String): Boolean? =
    Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)").find(this)?.groupValues?.get(1)?.toBoolean()
