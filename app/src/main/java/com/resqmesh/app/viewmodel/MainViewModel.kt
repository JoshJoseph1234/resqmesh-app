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
import android.bluetooth.BluetoothManager
import android.content.Context
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

    // Stores YOUR live location for the distance math
    val myCurrentLat = MutableStateFlow<Double?>(null)
    val myCurrentLon = MutableStateFlow<Double?>(null)

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

    @SuppressLint("MissingPermission")
    fun kickstartMeshEars() {
        if (bluetoothEnabled.value) {
            bleMeshManager.startScanning()
            // Grab our real GPS coordinates to power the distance math UI
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    myCurrentLat.value = it.latitude
                    myCurrentLon.value = it.longitude
                }
            }
        }
    }
    // --- ACTIONS ---
    @SuppressLint("MissingPermission")
    fun sendSos(type: SosType, messageText: String): Boolean {
        // 1. HARDWARE BLOCK: Refuse to send if antennas are dead!
        if (!isHardwareReady()) {
            return false
        }

        viewModelScope.launch {
            val newSos = SosMessageEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.PENDING
            )
            sosRepository.saveMessage(newSos)
            _connectivity.value = ConnectivityState.MESH_ACTIVE

            val typeByte = typeToByte(type)

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    val lat = location?.latitude?.toFloat() ?: 9.9312f
                    val lon = location?.longitude?.toFloat() ?: 76.2673f
                    bleMeshManager.startAdvertising(messageText, lat, lon, typeByte)
                }
                .addOnFailureListener {
                    bleMeshManager.startAdvertising(messageText, 9.9312f, 76.2673f, typeByte)
                }
        }
        return true // Successfully fired!
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

            // Use our new converter to read the incoming byte!
            val receivedType = byteToType(typeId)

            val incomingSos = SosMessageEntity(
                id = macAddress,
                type = receivedType,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.DELIVERED,
                latitude = lat.toDouble(),
                longitude = lon.toDouble()
            )

            sosRepository.saveMessage(incomingSos)
            Log.d("MainViewModel", "Saved incoming mesh SOS to local database!")

            // RELAY IT: Pass the identical typeId forward so the next phone knows what it is!
            bleMeshManager.startAdvertising(messageText, lat, lon, typeId)
        }
    }    // --- HELPER CONVERTERS ---
    private fun typeToByte(type: SosType): Byte = when(type) {
        SosType.MEDICAL -> 1
        SosType.RESCUE -> 2
        SosType.FOOD -> 3
        SosType.TRAPPED -> 4
        SosType.GENERAL -> 5
        SosType.OTHER -> 6
    }

    private fun byteToType(byte: Byte): SosType = when(byte.toInt()) {
        1 -> SosType.MEDICAL
        2 -> SosType.RESCUE
        3 -> SosType.FOOD
        4 -> SosType.TRAPPED
        5 -> SosType.GENERAL
        else -> SosType.OTHER
    }
    // --- HARDWARE MONITORING ---
    // Checks the actual physical chips, not just the app's preferences
    fun isHardwareReady(): Boolean {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val locManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val isBtOn = btManager.adapter?.isEnabled == true
        val isGpsOn = locManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

        return isBtOn && isGpsOn
    }

    // Forces the app UI to match the physical hardware state
    @SuppressLint("MissingPermission")
    fun syncHardwareState() {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val isBtOn = btManager.adapter?.isEnabled == true

        // If the hardware changed outside the app, update our Settings UI toggle!
        if (bluetoothEnabled.value != isBtOn) {
            setBluetoothEnabled(isBtOn)
        }

        if (isBtOn) {
            // Hardware just woke up! Turn the ears back on to catch missed messages!
            kickstartMeshEars()
        } else {
            // Hardware died. Shut down the engine safely.
            bleMeshManager.stopScanning()
            bleMeshManager.stopAdvertising()
        }
    }
}