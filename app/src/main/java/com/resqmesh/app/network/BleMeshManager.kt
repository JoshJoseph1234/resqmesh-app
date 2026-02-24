package com.resqmesh.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class BleMeshManager(
    private val context: Context,
    // CHANGED: We now return a 'senderId: String' instead of the hardware macAddress
    private val onMessageReceived: (lat: Float, lon: Float, type: Byte, message: String, senderId: String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bleAdapter get() = bluetoothManager.adapter
    private val bleScanner get() = bleAdapter?.bluetoothLeScanner
    private val bleAdvertiser get() = bleAdapter?.bluetoothLeAdvertiser

    private val processedHashes = mutableSetOf<Int>()

    companion object {
        val RESQMESH_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("87bd42f3-189f-4408-9bd3-07cb1bf6119f"))
        const val MANUFACTURER_ID = 0xFFFF
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    // CHANGED: Now accepts the deviceId and mathematically packs it into 3 bytes
    private fun packSosPayload(deviceId: Int, lat: Float, lon: Float, emergencyType: Byte, message: String): ByteArray {
        val safeMessage = message.take(14).toByteArray(Charsets.UTF_8) // Reduced to 14 characters to make room!
        val buffer = java.nio.ByteBuffer.allocate(12 + safeMessage.size) // 12 bytes of overhead now

        buffer.putFloat(lat)
        buffer.putFloat(lon)
        buffer.put(emergencyType)

        // Pack the 3-Byte ID manually using bitwise shifts
        buffer.put((deviceId shr 16).toByte())
        buffer.put((deviceId shr 8).toByte())
        buffer.put(deviceId.toByte())

        buffer.put(safeMessage)
        return buffer.array()
    }

    // CHANGED: Added deviceId parameter
    fun startAdvertising(deviceId: Int, messageText: String, lat: Float, lon: Float, typeId: Byte) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.e("BleMesh", "Attempted to advertise without BLUETOOTH_ADVERTISE permission")
            return
        }

        if (bleAdvertiser == null) {
            Log.e("BleMesh", "CRITICAL ERROR: Advertiser is null! Is Bluetooth actually ON?")
            return
        }

        Log.d("BleMesh", "Attempting to broadcast SOS: $messageText at $lat, $lon")

        bleAdvertiser?.stopAdvertising(advertiseCallback)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(RESQMESH_SERVICE_UUID)
            .build()

        // Pass the device ID to the packer!
        val payloadBytes = packSosPayload(deviceId, lat, lon, typeId, messageText)
        processedHashes.add(payloadBytes.contentHashCode())

        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payloadBytes)
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
    }

    fun stopAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            return
        }
        bleAdvertiser?.stopAdvertising(advertiseCallback)
    }

    fun startScanning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e("BleMesh", "Attempted to scan without BLUETOOTH_SCAN permission")
            return
        }

        if (bleScanner == null) {
            Log.e("BleMesh", "CRITICAL ERROR: Scanner is null! Is Bluetooth actually ON?")
            return
        }

        Log.d("BleMesh", "Ears are open! Scanning for Mesh Network...")

        val filter = ScanFilter.Builder().setServiceUuid(RESQMESH_SERVICE_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }
        bleScanner?.stopScan(scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BleMesh", "SUCCESS: Broadcasting!")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("BleMesh", "FAILURE: Advertising failed with code: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val payloadBytes = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)

                // CHANGED: We now require 12 minimum bytes to successfully unpack
                if (payloadBytes != null && payloadBytes.size >= 12) {
                    val payloadHash = payloadBytes.contentHashCode()
                    if (processedHashes.contains(payloadHash)) return
                    processedHashes.add(payloadHash)

                    try {
                        val buffer = java.nio.ByteBuffer.wrap(payloadBytes)
                        val receivedLat = buffer.float
                        val receivedLon = buffer.float
                        val receivedType = buffer.get()

                        // CHANGED: Unpack the 3-byte ID back into an Integer
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt() and 0xFF
                        val b3 = buffer.get().toInt() and 0xFF
                        val senderIdInt = (b1 shl 16) or (b2 shl 8) or b3

                        // Format it into a clean 6-character Hex string (e.g., "A3F9B2")
                        val senderIdString = String.format("%06X", senderIdInt)

                        val messageBytes = ByteArray(payloadBytes.size - 12)
                        buffer.get(messageBytes)
                        val receivedMessage = String(messageBytes, Charsets.UTF_8)

                        Log.d("BleMesh", "🚨 NEW SOS RECEIVED from User #$senderIdString: $receivedMessage")

                        // Pass the new senderIdString instead of the device.address!
                        onMessageReceived(receivedLat, receivedLon, receivedType, receivedMessage, senderIdString)
                    } catch (e: Exception) {
                        Log.e("BleMesh", "Error unpacking: ${e.message}")
                    }
                }
            }
        }
    }
}