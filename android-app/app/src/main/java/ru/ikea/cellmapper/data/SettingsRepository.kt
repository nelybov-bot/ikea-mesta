package ru.ikea.cellmapper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val scanAndPlaceKey = booleanPreferencesKey("scan_and_place")
    private val fileUriKey = stringPreferencesKey("file_uri")
    private val fileNameKey = stringPreferencesKey("file_name")

    val scanAndPlaceEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[scanAndPlaceKey] ?: true
    }

    val fileUri: Flow<String?> = context.dataStore.data.map { it[fileUriKey] }
    val fileName: Flow<String?> = context.dataStore.data.map { it[fileNameKey] }

    suspend fun setScanAndPlaceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[scanAndPlaceKey] = enabled }
    }

    suspend fun setFile(uri: String, name: String) {
        context.dataStore.edit {
            it[fileUriKey] = uri
            it[fileNameKey] = name
        }
    }
}
