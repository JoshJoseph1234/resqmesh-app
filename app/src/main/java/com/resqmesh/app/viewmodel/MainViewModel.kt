package com.resqmesh.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqmesh.app.ConnectivityState
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SosType
import com.resqmesh.app.data.ResQMeshDatabase
import com.resqmesh.app.data.SettingsRepository
import com.resqmesh.app.data.SosRepository
import com.resqmesh.app.network.BleMeshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import android.annotation.SuppressLint
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.resqmesh.app.data.SosMessageEntity

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- DATABASES & REPOSITORIES ---
    private val database = ResQMeshDatabase.getDatabase(application)
    private val sosRepository = SosRepository(database.sosDao())
    private val settingsRepository = SettingsRepository(application)

    // --- THE MESH ENGINE ---
    private val bleMeshManager = BleMeshManager(application) { lat, lon, type, message, macAddress ->
        handleIncomingMeshMessage(lat, lon, type, message, macAddress)
    }
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    // --- UI STATE: MESSAGES & CONNECTIVITY ---
    val sentMessages: StateFlow<List<SosMessageEntity>> = sosRepository.allMessages
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _connectivity = MutableStateFlow(ConnectivityState.OFFLINE)
    val connectivity = _connectivity.asStateFlow()

    // --- UI STATE: SETTINGS ---
    val bluetoothEnabled: StateFlow<Boolean> = settingsRepository.bluetoothEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val wifiDirectEnabled: StateFlow<Boolean> = settingsRepository.wifiEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // --- INITIALIZATION ---
    init {
        viewModelScope.launch {
            bluetoothEnabled.collect { isEnabled ->
                if (isEnabled) {
                    // ONLY turn on the ears when the toggle is flipped!
                    bleMeshManager.startScanning()
                } else {
                    bleMeshManager.stopAdvertising() // Stop shouting if we were
                    bleMeshManager.stopScanning()    // Stop listening
                }
            }
        }
    }

    // --- ACTIONS ---
    @SuppressLint("MissingPermission")
    fun sendSos(type: SosType, messageText: String) {
        viewModelScope.launch {
            // 1. Save it to the local Room Database permanently
            val newSos = SosMessageEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.PENDING
            )
            sosRepository.saveMessage(newSos)
            _connectivity.value = ConnectivityState.MESH_ACTIVE

            // 2. Check if the user actually allowed Bluetooth in settings
            if (bluetoothEnabled.value) {
                // 3. Get the live GPS coordinates and SHOUT!
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        // If GPS works, use real location. If not, fallback to Kochi coordinates.
                        val lat = location?.latitude?.toFloat() ?: 9.9312f
                        val lon = location?.longitude?.toFloat() ?: 76.2673f

                        bleMeshManager.startAdvertising(messageText, lat, lon)
                    }
                    .addOnFailureListener {
                        // If GPS completely fails, shout with fallback coordinates
                        bleMeshManager.startAdvertising(messageText, 9.9312f, 76.2673f)
                    }
            }
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBluetoothState(enabled)
        }
    }

    fun setWifiDirectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveWifiState(enabled)
        }
    }

    // --- MESH NETWORK ROUTING ---
    private fun handleIncomingMeshMessage(lat: Float, lon: Float, typeId: Byte, messageText: String, macAddress: String) {
        viewModelScope.launch {

            // 1. Convert the raw Byte back into our SosType enum
            val receivedType = if (typeId == 1.toByte()) SosType.MEDICAL else SosType.OTHER

            // 2. Package it into a database entity
            val incomingSos = SosMessageEntity(
                id = macAddress, // Prevents database spam! Overwrites the old location.
                type = receivedType,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.DELIVERED, // Mark it as an incoming alert
                latitude = lat.toDouble(),
                longitude = lon.toDouble()
            )

            // 3. Save it permanently to the Room Database
            sosRepository.saveMessage(incomingSos)

            // Optional: If you want to log that it saved successfully
            Log.d("MainViewModel", "Saved incoming mesh SOS to local database!")
        }
    }
}