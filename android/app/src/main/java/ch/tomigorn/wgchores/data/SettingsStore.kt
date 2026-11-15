package ch.tomigorn.wgchores.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

/** Device settings that survive "Change server" (unlike the session). */
class SettingsStore(private val prefs: DataStore<Preferences>) {
    private val themeKey = stringPreferencesKey("theme")

    val theme: Flow<ThemeMode> = prefs.data.map { p ->
        ThemeMode.entries.firstOrNull { it.name == p[themeKey] } ?: ThemeMode.System
    }

    suspend fun setTheme(mode: ThemeMode) {
        prefs.edit { it[themeKey] = mode.name }
    }
}
