package com.resqmesh.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 1. Create the DataStore instance (This acts as the actual file on the device)
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resqmesh_settings")

class SettingsRepository(private val context: Context) {

    // 2. Define the exact "keys" (names) for the data we want to save
    companion object {
        val BLUETOOTH_KEY = booleanPreferencesKey("bluetooth_enabled")
        val WIFI_KEY = booleanPreferencesKey("wifi_enabled")
    }

    // 3. Read the data as a Flow (Automatically updates the UI when changed)
    val bluetoothEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLUETOOTH_KEY] ?: false // Default to false if not found
    }

    val wifiEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_KEY] ?: false
    }

    // 4. Functions to write new data to the storage
    suspend fun saveBluetoothState(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_KEY] = enabled
        }
    }

    suspend fun saveWifiState(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WIFI_KEY] = enabled
        }
    }
}