Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver.feature.settings

import android.content.Context

enum class AppTheme { System, Light, Dark }
enum class SortOrder { Name, Modified, Size }

data class AppSettings(
    val theme: AppTheme = AppTheme.System,
    val sortOrder: SortOrder = SortOrder.Name,
    val ascending: Boolean = true,
    val gridView: Boolean = false,
    val showHiddenFiles: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    fun read() = AppSettings(
        runCatching { AppTheme.valueOf(prefs.getString("theme", AppTheme.System.name)!!) }.getOrDefault(AppTheme.System),
        runCatching { SortOrder.valueOf(prefs.getString("sort", SortOrder.Name.name)!!) }.getOrDefault(SortOrder.Name),
        prefs.getBoolean("ascending", true), prefs.getBoolean("grid", false), prefs.getBoolean("hidden", false),
    )
    fun save(settings: AppSettings) { prefs.edit().putString("theme", settings.theme.name).putString("sort", settings.sortOrder.name).putBoolean("ascending", settings.ascending).putBoolean("grid", settings.gridView).putBoolean("hidden", settings.showHiddenFiles).apply() }
}

