package com.resqmesh.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

// 1. Create the DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resqmesh_settings")

class SettingsRepository(private val context: Context) {

    // 2. Define the exact "keys" for the data we want to save
    companion object {
        val BLUETOOTH_KEY = booleanPreferencesKey("bluetooth_enabled")
        val WIFI_KEY = booleanPreferencesKey("wifi_enabled")
        // NEW: Key for our permanent 3-Byte Device ID
        val DEVICE_ID_KEY = intPreferencesKey("device_id")
    }

    // 3. Read the data as a Flow
    val bluetoothEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLUETOOTH_KEY] ?: false
    }

    val wifiEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_KEY] ?: false
    }

    // NEW: Safely get the ID, or generate it if this is the first time the app is opened!
    suspend fun getOrGenerateDeviceId(): Int {
        val preferences = context.dataStore.data.first()
        val existingId = preferences[DEVICE_ID_KEY]

        return if (existingId != null) {
            existingId
        } else {
            // Generate a random 3-Byte ID (0 to 16,777,215)
            val newId = Random.nextInt(0, 16777215)
            context.dataStore.edit { prefs ->
                prefs[DEVICE_ID_KEY] = newId
            }
            newId
        }
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