package com.resqmesh.app.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class BleMeshManager(context: Context, private val onMessageReceived: (lat: Float, lon: Float, type: Byte, message: String, macAddress: String) -> Unit) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private val bleAdvertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    companion object {
        val RESQMESH_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("87bd42f3-189f-4408-9bd3-07cb1bf6119f"))
        const val MANUFACTURER_ID = 0xFFFF // The standard Bluetooth testing ID
    }

    // ==========================================
    // 1. ADVERTISING & PAYLOAD PACKING
    // ==========================================

    private fun packSosPayload(lat: Float, lon: Float, emergencyType: Byte, message: String): ByteArray {
        // Manufacturer Data gives us 27 bytes total.
        // Lat(4) + Lon(4) + Type(1) = 9 bytes.
        // 27 - 9 = 18 bytes left for the message!
        val safeMessage = message.take(18).toByteArray(Charsets.UTF_8)

        val buffer = java.nio.ByteBuffer.allocate(9 + safeMessage.size)

        buffer.putFloat(lat)
        buffer.putFloat(lon)
        buffer.put(emergencyType)
        buffer.put(safeMessage)

        return buffer.array()
    }

    fun startAdvertising(messageText: String, lat: Float, lon: Float) {
        if (bleAdvertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Packet 1: The Handshake (Shouts the 16-byte UUID so other phones find us)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(RESQMESH_SERVICE_UUID)
            .build()

        val payloadBytes = packSosPayload(
            lat = lat,
            lon = lon,
            emergencyType = 1,
            message = messageText
        )

        // Packet 2: The Payload (Uses a 2-byte ID, leaving 27 bytes for our data!)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payloadBytes) // <--- THIS IS THE FIX
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
    }

    fun stopAdvertising() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d("BleMesh", "Stopped shouting.")
        } catch (e: Exception) {
            Log.e("BleMesh", "Error stopping advertiser: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BleMesh", "SUCCESS: Broadcasting ResQMesh presence to the world!")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("BleMesh", "FAILURE: Advertising failed with error code: $errorCode")
        }
    }

    // ==========================================
    // 2. SCANNING (LISTENING) - NEW!
    // ==========================================
    fun startScanning() {
        if (bleScanner == null) return

        // Filter: ONLY listen for devices shouting our specific UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(RESQMESH_SERVICE_UUID)
            .build()

        // Settings: Scan aggressively (important for emergencies)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d("BleMesh", "SUCCESS: Started listening for nearby ResQMesh devices...")
    }

    fun stopScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
            Log.d("BleMesh", "Stopped listening.")
        } catch (e: Exception) {
            Log.e("BleMesh", "Error stopping scanner: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.device?.let { device ->
                // 1. Look for our specific Manufacturer Data box
                val scanRecord = result.scanRecord
                val payloadBytes = scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)

                if (payloadBytes != null && payloadBytes.size >= 9) {
                    try {
                        // 2. Read the bytes in the EXACT same order we packed them!
                        val buffer = java.nio.ByteBuffer.wrap(payloadBytes)

                        val receivedLat = buffer.float
                        val receivedLon = buffer.float
                        val receivedType = buffer.get()

                        // 3. The remaining bytes belong to the text message
                        val messageBytes = ByteArray(payloadBytes.size - 9)
                        buffer.get(messageBytes)
                        val receivedMessage = String(messageBytes, Charsets.UTF_8)

                        // 4. Print the decoded SOS to our Logcat!
                        Log.d("BleMesh", """
                            🚨 INCOMING SOS RECEIVED! 🚨
                            From MAC: ${device.address}
                        """.trimIndent())

                        // 5. SEND IT ACROSS THE BRIDGE TO THE VIEWMODEL!
                        onMessageReceived(
                            receivedLat,
                            receivedLon,
                            receivedType,
                            receivedMessage,
                            device.address
                        )

                    } catch (e: Exception) {
                        Log.e("BleMesh", "Error unpacking payload: ${e.message}")
                    }
                } else {
                    // We saw a ResQMesh phone, but it isn't actively broadcasting an SOS
                    Log.d("BleMesh", "Found ResQMesh node: ${device.address} (No SOS payload)")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleMesh", "FAILURE: Scan failed with error code: $errorCode")
        }
    }
}