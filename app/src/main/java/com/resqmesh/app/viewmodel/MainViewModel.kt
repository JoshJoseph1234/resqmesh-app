package com.resqmesh.app.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqmesh.app.ConnectivityState
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SendSosResult
import com.resqmesh.app.SosType
import com.resqmesh.app.data.ResQMeshDatabase
import com.resqmesh.app.data.SettingsRepository
import com.resqmesh.app.data.SosRepository
import com.resqmesh.app.network.BleMeshManager
import com.resqmesh.app.util.ConnectivityUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.resqmesh.app.data.SosMessageEntity
import kotlinx.coroutines.delay

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- DATABASES & REPOSITORIES ---
    private val database = ResQMeshDatabase.getDatabase(application)
    private val sosRepository = SosRepository(database.sosDao())
    private val settingsRepository = SettingsRepository(application)

    // --- THE MESH ENGINE ---
    private val bleMeshManager = BleMeshManager(application) { lat, lon, type, message, senderId ->
        handleIncomingMeshMessage(lat, lon, type, message, senderId)
    }
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    // --- UI STATE: MESSAGES & CONNECTIVITY ---
    val sentMessages: StateFlow<List<SosMessageEntity>> = sosRepository.allMessages
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _connectivity = MutableStateFlow(ConnectivityState.OFFLINE)
    val connectivity = _connectivity.asStateFlow()

    private val _isInternetActuallyWorking = MutableStateFlow(false)

    // --- UI STATE: SETTINGS ---
    val bluetoothEnabled: StateFlow<Boolean> = settingsRepository.bluetoothEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val wifiDirectEnabled: StateFlow<Boolean> = settingsRepository.wifiEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Stores YOUR live location for the distance math
    val myCurrentLat = MutableStateFlow<Double?>(null)
    val myCurrentLon = MutableStateFlow<Double?>(null)

    private fun hasRequiredPermissions(): Boolean {
        val context = getApplication<Application>().applicationContext
        val hasLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtScan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasBtAdvertise = context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasBtConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            hasLocation && hasBtScan && hasBtAdvertise && hasBtConnect
        } else {
            hasLocation
        }
    }

    // --- INITIALIZATION ---
    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val hasInternet = ConnectivityUtil.hasRealInternet()
                _isInternetActuallyWorking.value = hasInternet
                if (hasInternet) {
                    _connectivity.value = ConnectivityState.INTERNET
                } else if (bluetoothEnabled.value) {
                    _connectivity.value = ConnectivityState.MESH_ACTIVE
                } else {
                    _connectivity.value = ConnectivityState.OFFLINE
                }
                delay(10000) // Check every 10 seconds
            }
        }

        viewModelScope.launch {
            bluetoothEnabled.collect { isEnabled ->
                if (isEnabled) { 
                    bleMeshManager.startScanning()
                } else {
                    bleMeshManager.stopAdvertising()
                    bleMeshManager.stopScanning()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun kickstartMeshEars() {
        if (bluetoothEnabled.value) {
            bleMeshManager.startScanning()
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    myCurrentLat.value = it.latitude
                    myCurrentLon.value = it.longitude
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSos(type: SosType, messageText: String): SendSosResult {
        if (messageText.isBlank()) {
            return SendSosResult.EMPTY_MESSAGE
        }

        if (!isHardwareReady()) {
            return SendSosResult.HARDWARE_NOT_READY
        }

        if (!hasRequiredPermissions()) {
            Log.e("MainViewModel", "sendSos called without required permissions!")
            return SendSosResult.HARDWARE_NOT_READY
        }

        viewModelScope.launch {
            val newSos = SosMessageEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = if (_isInternetActuallyWorking.value) DeliveryStatus.DELIVERED else DeliveryStatus.PENDING
            )
            sosRepository.saveMessage(newSos)

            if (_isInternetActuallyWorking.value) {
                Log.d("ResQMesh_Cloud", "Internet Detected! Sending SOS to Dummy Server: https://api.resqmesh.dummy/v1/sos")
                delay(500) // Simulate network latency
                Log.d("ResQMesh_Cloud", "Cloud Sync Success: Message ${newSos.id} is now on the central dashboard.")
            }

            val typeByte = typeToByte(type)
            val myDeviceId = settingsRepository.getOrGenerateDeviceId()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    val lat = location?.latitude?.toFloat() ?: 0.0f
                    val lon = location?.longitude?.toFloat() ?: 0.0f
                    bleMeshManager.startAdvertising(myDeviceId, messageText, lat, lon, typeByte)
                }
                .addOnFailureListener { 
                    bleMeshManager.startAdvertising(myDeviceId, messageText, 0.0f, 0.0f, typeByte)
                }
        }
        return SendSosResult.SUCCESS
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

    private fun handleIncomingMeshMessage(lat: Float, lon: Float, typeId: Byte, messageText: String, senderId: String) {
        viewModelScope.launch {
            val receivedType = byteToType(typeId)
            val smartId = "${senderId}_${receivedType.name}_${messageText.hashCode()}"

            val incomingSos = SosMessageEntity(
                id = smartId,
                type = receivedType,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.RELAYED, // Changed from DELIVERED
                latitude = lat.toDouble(),
                longitude = lon.toDouble()
            )

            sosRepository.saveMessage(incomingSos)
            Log.d("MainViewModel", "Saved incoming mesh SOS: $smartId")
        }
    }

    private fun typeToByte(type: SosType): Byte = when(type) {
        SosType.MEDICAL -> 1
        SosType.RESCUE -> 2
        SosType.FOOD -> 3
        SosType.TRAPPED -> 4
        SosType.GENERAL -> 5
        else -> 6
    }

    private fun byteToType(byte: Byte): SosType = when(byte.toInt()) {
        1 -> SosType.MEDICAL
        2 -> SosType.RESCUE
        3 -> SosType.FOOD
        4 -> SosType.TRAPPED
        5 -> SosType.GENERAL
        else -> SosType.OTHER
    }

    fun isHardwareReady(): Boolean {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val locManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val isBtOn = btManager.adapter?.isEnabled == true
        val isGpsOn = locManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

        return isBtOn && isGpsOn
    }

    @SuppressLint("MissingPermission")
    fun syncHardwareState() {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val isBtOn = btManager.adapter?.isEnabled == true

        if (bluetoothEnabled.value != isBtOn) {
            setBluetoothEnabled(isBtOn)
        }

        if (isBtOn) {
            kickstartMeshEars()
        } else {
            bleMeshManager.stopScanning()
            bleMeshManager.stopAdvertising()
        }
    }
}
