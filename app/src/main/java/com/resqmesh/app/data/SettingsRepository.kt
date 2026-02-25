package com.resqmesh.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID

// 1. Define the DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    // 2. Define Keys for your settings
    private val bluetoothKey = booleanPreferencesKey("bluetooth_enabled")
    private val wifiKey = booleanPreferencesKey("wifi_enabled")
    private val deviceIdKey = stringPreferencesKey("device_permanent_id")


    // 3. Create Flows to automatically listen for changes
    val bluetoothEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[bluetoothKey] ?: false
        }

    val wifiEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[wifiKey] ?: false
        }

    // 4. Create suspend functions to save the settings
    suspend fun saveBluetoothState(isEnabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[bluetoothKey] = isEnabled
        }
    }

    suspend fun saveWifiState(isEnabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[wifiKey] = isEnabled
        }
    }

    fun getOrGenerateDeviceId(): Int {
        return runBlocking {
            val currentId = context.dataStore.data.first()[deviceIdKey]
            if (currentId != null) {
                currentId.toInt(16)
            } else {
                val newId = UUID.randomUUID().toString().substring(0, 6).uppercase()
                context.dataStore.edit {
                    it[deviceIdKey] = newId
                }
                newId.toInt(16)
            }
        }
    }
}
