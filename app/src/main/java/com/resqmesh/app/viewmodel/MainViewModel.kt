package com.resqmesh.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqmesh.app.ConnectivityState
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SosType
import com.resqmesh.app.data.ResQMeshDatabase
import com.resqmesh.app.data.SettingsRepository
import com.resqmesh.app.data.SosMessageEntity
import com.resqmesh.app.data.SosRepository
import com.resqmesh.app.network.BleMeshManager // <-- This import prevents red lines!
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import android.annotation.SuppressLint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- DATABASES & REPOSITORIES ---
    private val database = ResQMeshDatabase.getDatabase(application)
    private val sosRepository = SosRepository(database.sosDao())
    private val settingsRepository = SettingsRepository(application)

    // --- THE MESH ENGINE ---
    private val bleMeshManager = BleMeshManager(application)
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

    @SuppressLint("MissingPermission") // Safe because we requested it in SettingsScreen
    private fun startMeshWithLiveLocation() {
        // Ask the phone's GPS for an immediate, high-accuracy reading
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    // We got a GPS lock! Shout the real coordinates.
                    bleMeshManager.startAdvertising(
                        messageText = "Help needed!",
                        lat = location.latitude.toFloat(),
                        lon = location.longitude.toFloat()
                    )
                } else {
                    // GPS is still warming up. We will default to 9.9312, 76.2673 for now
                    // so it falls back somewhere familiar while testing.
                    bleMeshManager.startAdvertising("Help needed!", 9.9312f, 76.2673f)
                }
                bleMeshManager.startScanning() // Turn on the ears
            }
            .addOnFailureListener {
                // If the location hardware completely fails, still broadcast the SOS
                bleMeshManager.startAdvertising("Help needed!", 9.9312f, 76.2673f)
                bleMeshManager.startScanning()
            }
    }

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
}