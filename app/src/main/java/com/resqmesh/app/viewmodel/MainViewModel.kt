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
import com.resqmesh.app.network.MeshService
import com.resqmesh.app.network.LoRaNodeManager
import com.resqmesh.app.network.LoRaConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- DATABASES & REPOSITORIES ---
    private val database = ResQMeshDatabase.getDatabase(application)
    private val sosRepository = SosRepository(database.sosDao())
    private val settingsRepository = SettingsRepository(application)

    // --- THE MESH ENGINE ---
    private val bleMeshManager = BleMeshManager(application) { lat, lon, type, message, senderId ->
        handleIncomingMeshMessage(lat, lon, type, message, senderId)
    }

    // NEW: LoRa - Initialize the invisible gateway manager
    private val loRaNodeManager = LoRaNodeManager(application)

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

    val myCurrentLat = MutableStateFlow<Double?>(null)
    val myCurrentLon = MutableStateFlow<Double?>(null)

    // --- INITIALIZATION ---
    init {
        syncHardwareState()
        cleanupOldMessages()

        // NEW: Listen for LoRa Hardware Acknowledgments!
        loRaNodeManager.onAckReceived = { messageId ->
            viewModelScope.launch(Dispatchers.IO) {
                Log.d("ResQMesh_LoRa", "ACK matched to DB ID: $messageId. Updating UI!")
                sosRepository.updateMessageStatus(messageId, DeliveryStatus.ACKNOWLEDGED)
            }
        }

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
                delay(10000)
            }
        }

        viewModelScope.launch {
            bluetoothEnabled.collect { isEnabled ->
                if (isEnabled) {
                    toggleForegroundService(true)
                    bleMeshManager.startScanning()

                } else {
                    toggleForegroundService(false)
                    bleMeshManager.stopAdvertising()
                    bleMeshManager.stopScanning()

                }
            }
        }
    }

    private fun cleanupOldMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val fortyEightHoursAgo = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
            Log.d("ResQMesh_DB", "Running DB Cleanup. Pruning messages older than $fortyEightHoursAgo")
            sosRepository.deleteOldMessages(fortyEightHoursAgo)
        }
    }

    private suspend fun syncPendingMessagesToCloud() {
        val pendingMessages = sosRepository.getPendingMessages()
        if (pendingMessages.isNotEmpty()) {
            Log.d("ResQMesh_Cloud", "Internet connection detected. Syncing ${pendingMessages.size} pending messages.")
            pendingMessages.forEach { message ->
                sendToWebhook(message)
                delay(200)
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
            val myDeviceId = settingsRepository.getOrGenerateDeviceId()
            val myDeviceIdHex = String.format("%06X", myDeviceId)
            val universalSmartId = "${myDeviceIdHex}_${type.name}_${messageText.hashCode()}"

            val newSos = SosMessageEntity(
                id = universalSmartId,
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = if (_isInternetActuallyWorking.value) DeliveryStatus.DELIVERED else DeliveryStatus.PENDING,
                latitude = lat,
                longitude = lon
            )
            sosRepository.saveMessage(newSos)

            Log.d("ResQMesh_Flow", "Processing SOS. Internet active: ${_isInternetActuallyWorking.value}")

            if (_isInternetActuallyWorking.value) {
                sendToWebhook(newSos)
            } else {
                // 1. Blast to BLE Mesh (Phones)
                val typeByte = typeToByte(type)
                bleMeshManager.startAdvertising(myDeviceId, messageText, lat.toFloat(), lon.toFloat(), typeByte)

                // 2. Queue for LoRa Gateway (Hit-and-Run)
                val jsonBody = """
                    {
                        "id": "${newSos.id}",
                        "type": "${newSos.type.name}",
                        "message": "${newSos.message}",
                        "timestamp": ${newSos.timestamp},
                        "status": "SENT_TO_NODE",
                        "latitude": ${newSos.latitude ?: "null"},
                        "longitude": ${newSos.longitude ?: "null"}
                    }
                """.trimIndent()

                // Just throw it in the queue. The manager will handle finding the node!
                loRaNodeManager.queueMessageForNode(jsonBody, newSos.id)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSos(type: SosType, messageText: String): SendSosResult {
        if (messageText.isBlank()) return SendSosResult.EMPTY_MESSAGE
        if (!isHardwareReady()) return SendSosResult.HARDWARE_NOT_READY

        Log.d("ResQMesh_Flow", "SEND SOS Button Pressed. Attempting fresh GPS lock (5s timeout)...")

        viewModelScope.launch {
            var lat = myCurrentLat.value ?: 0.0
            var lon = myCurrentLon.value ?: 0.0

            try {
                // 5-Second Timeout using native Kotlin (No extra libraries needed!)
                val freshLocation = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { continuation ->
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener { location ->
                                if (continuation.isActive) continuation.resume(location)
                            }
                            .addOnFailureListener {
                                if (continuation.isActive) continuation.resume(null)
                            }
                    }
                }

                if (freshLocation != null) {
                    lat = freshLocation.latitude
                    lon = freshLocation.longitude
                    myCurrentLat.value = lat
                    myCurrentLon.value = lon
                    Log.d("ResQMesh_Flow", "Fresh GPS Lock secured! ($lat, $lon)")
                } else {
                    Log.w("ResQMesh_Flow", "GPS lock timed out. Trying cached location...")
                    // Native fallback for last location
                    kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                        fusedLocationClient.lastLocation
                            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    }?.let { cachedLoc ->
                        lat = cachedLoc.latitude
                        lon = cachedLoc.longitude
                    }
                }
            } catch (e: Exception) {
                Log.e("ResQMesh_Flow", "Location fetch failed: ${e.message}")
            }

            Log.d("ResQMesh_Flow", "Final Coordinates ($lat, $lon). Routing message...")
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
                status = DeliveryStatus.RELAYED,
                latitude = lat.toDouble(),
                longitude = lon.toDouble()
            )

            sosRepository.saveMessage(incomingSos)
            Log.d("MainViewModel", "Saved incoming mesh SOS: $smartId")

            if (_isInternetActuallyWorking.value) {
            sendToWebhook(incomingSos)
        } else {
            // Throw incoming mesh messages into the gateway queue!
            val jsonBody = """
                    {
                        "id": "${incomingSos.id}",
                        "type": "${incomingSos.type.name}",
                        "message": "${incomingSos.message}",
                        "timestamp": ${incomingSos.timestamp},
                        "status": "SENT_TO_NODE",
                        "latitude": ${incomingSos.latitude ?: "null"},
                        "longitude": ${incomingSos.longitude ?: "null"}
                    }
                """.trimIndent()

            loRaNodeManager.queueMessageForNode(jsonBody, incomingSos.id)
        }
        }
    }

    private suspend fun sendToWebhook(message: SosMessageEntity) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://webhook.site/a006a0c8-102a-4b0a-9fd3-c4b3d2b670bd")
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
        SosType.MEDICAL -> 1; SosType.RESCUE -> 2; SosType.FOOD -> 3; SosType.TRAPPED -> 4; SosType.GENERAL -> 5; else -> 6
    }

    private fun byteToType(byte: Byte): SosType = when(byte.toInt()) {
        1 -> SosType.MEDICAL; 2 -> SosType.RESCUE; 3 -> SosType.FOOD; 4 -> SosType.TRAPPED; 5 -> SosType.GENERAL; else -> SosType.OTHER
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
        setBluetoothEnabled(isBtOn)

        if (isBtOn) {
            kickstartMeshEars()
        } else {
            bleMeshManager.stopScanning()
            bleMeshManager.stopAdvertising()
        }
    }

    private fun toggleForegroundService(start: Boolean) {
        val intent = android.content.Intent(getApplication(), MeshService::class.java)
        if (start) {
            intent.action = MeshService.ACTION_START
            // Removed the redundant SDK_INT >= O check to clear your warning!
            getApplication<Application>().startForegroundService(intent)
        } else {
            intent.action = MeshService.ACTION_STOP
            getApplication<Application>().startService(intent)
        }
    }
}