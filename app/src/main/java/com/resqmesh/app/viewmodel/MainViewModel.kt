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
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
                    syncPendingMessagesToCloud()
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

    private suspend fun syncPendingMessagesToCloud() {
        val pendingMessages = sosRepository.getPendingMessages()
        if (pendingMessages.isNotEmpty()) {
            Log.d("ResQMesh_Cloud", "Internet connection detected. Syncing ${pendingMessages.size} pending messages.")
            pendingMessages.forEach { message ->
                sendToWebhook(message)
                delay(200) // Simulate network latency for each message
                sosRepository.updateMessageStatus(message.id, DeliveryStatus.DELIVERED)
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

    private fun processSosMessage(type: SosType, messageText: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            val newSos = SosMessageEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = if (_isInternetActuallyWorking.value) DeliveryStatus.DELIVERED else DeliveryStatus.PENDING,
                latitude = lat,
                longitude = lon
            )
            sosRepository.saveMessage(newSos)

            if (_isInternetActuallyWorking.value) {
                sendToWebhook(newSos)
            } else {
                val typeByte = typeToByte(type)
                val myDeviceId = settingsRepository.getOrGenerateDeviceId()
                bleMeshManager.startAdvertising(myDeviceId, messageText, lat.toFloat(), lon.toFloat(), typeByte)
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

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                val lat = location?.latitude ?: myCurrentLat.value ?: 0.0
                val lon = location?.longitude ?: myCurrentLon.value ?: 0.0

                if (location != null) {
                    myCurrentLat.value = lat
                    myCurrentLon.value = lon
                }
                processSosMessage(type, messageText, lat, lon)
            }
            .addOnFailureListener {
                val lat = myCurrentLat.value ?: 0.0
                val lon = myCurrentLon.value ?: 0.0
                processSosMessage(type, messageText, lat, lon)
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
                status = DeliveryStatus.RELAYED, // Always RELAYED for incoming messages
                latitude = lat.toDouble(),
                longitude = lon.toDouble()
            )

            sosRepository.saveMessage(incomingSos)
            Log.d("MainViewModel", "Saved incoming mesh SOS: $smartId")

            if (_isInternetActuallyWorking.value) {
                sendToWebhook(incomingSos)
            }
        }
    }

    // --- HELPER FUNCTIONS ---

    private suspend fun sendToWebhook(message: SosMessageEntity) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://webhook.site/13b698d6-2410-4348-9a80-302c9a549756")
                // Basic JSON serialization
                val jsonBody = """
                    {
                        "id": "${message.id}",
                        "type": "${message.type.name}",
                        "message": "${message.message}",
                        "timestamp": ${message.timestamp},
                        "status": "${message.status.name}",
                        "latitude": ${message.latitude ?: "null"},
                        "longitude": ${message.longitude ?: "null"}
                    }
                """.trimIndent()

                (url.openConnection() as? HttpURLConnection)?.run {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    outputStream.use { os ->
                        val input = jsonBody.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }
                    Log.d("ResQMesh_Cloud", "Webhook response code: $responseCode - $responseMessage")
                    disconnect()
                }
            } catch (e: Exception) {
                Log.e("ResQMesh_Cloud", "Error sending to webhook: ${e.message}")
            }
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
