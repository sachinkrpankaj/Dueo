package com.example.dueo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore(name = "appearance")
enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }
class ThemePreferences(private val context: Context) {
    private val key = stringPreferencesKey("theme")
    val mode = context.preferences.data.map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[key] } ?: ThemeMode.SYSTEM }
    suspend fun set(mode: ThemeMode) { context.preferences.edit { it[key] = mode.name } }
}
