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
    private val context: Context, // Add context to check permissions
    private val onMessageReceived: (lat: Float, lon: Float, type: Byte, message: String, macAddress: String) -> Unit
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

    private fun packSosPayload(lat: Float, lon: Float, emergencyType: Byte, message: String): ByteArray {
        val safeMessage = message.take(18).toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(9 + safeMessage.size)
        buffer.putFloat(lat)
        buffer.putFloat(lon)
        buffer.put(emergencyType)
        buffer.put(safeMessage)
        return buffer.array()
    }

    fun startAdvertising(messageText: String, lat: Float, lon: Float, typeId: Byte) {
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

        val payloadBytes = packSosPayload(lat, lon, typeId, messageText)
        processedHashes.add(payloadBytes.contentHashCode())

        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payloadBytes)
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
    }

    fun stopAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
             return // Don't even try if we don't have permission
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
            return // Don't even try if we don't have permission
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

                if (payloadBytes != null && payloadBytes.size >= 9) {
                    val payloadHash = payloadBytes.contentHashCode()
                    if (processedHashes.contains(payloadHash)) return
                    processedHashes.add(payloadHash)

                    try {
                        val buffer = java.nio.ByteBuffer.wrap(payloadBytes)
                        val receivedLat = buffer.float
                        val receivedLon = buffer.float
                        val receivedType = buffer.get()

                        val messageBytes = ByteArray(payloadBytes.size - 9)
                        buffer.get(messageBytes)
                        val receivedMessage = String(messageBytes, Charsets.UTF_8)

                        Log.d("BleMesh", "🚨 NEW SOS RECEIVED from ${device.address}: $receivedMessage")
                        onMessageReceived(receivedLat, receivedLon, receivedType, receivedMessage, device.address)
                    } catch (e: Exception) {
                        Log.e("BleMesh", "Error unpacking: ${e.message}")
                    }
                }
            }
        }
    }
}