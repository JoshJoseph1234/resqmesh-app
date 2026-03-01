package com.resqmesh.app.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class LoRaConnectionState { DISCONNECTED, SCANNING, CONNECTED }

@SuppressLint("MissingPermission")
class LoRaNodeManager(private val context: Context) {

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val WRITE_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val NOTIFY_CHAR_UUID = UUID.fromString("cc821ea3-9b9f-4eb8-8884-25b57d00f77b")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(LoRaConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LoRaConnectionState> = _connectionState

    var onAckReceived: ((String) -> Unit)? = null
    private var lastSentMessageId: String? = null

    private val messageQueue = ConcurrentLinkedQueue<Pair<String, String>>()
    private var isProcessing = false

    fun queueMessageForNode(jsonPayload: String, messageId: String) {
        messageQueue.add(Pair(messageId, jsonPayload))
        Log.d("ResQMesh_LoRa", "Message queued. Queue size: ${messageQueue.size}")

        if (_connectionState.value == LoRaConnectionState.DISCONNECTED && !isProcessing) {
            startScanning()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            Log.d("ResQMesh_LoRa", "Found Gateway! Stopping scan and connecting...")

            // FIX 1: Fetch fresh scanner to stop safely
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            isProcessing = false
            _connectionState.value = LoRaConnectionState.DISCONNECTED
        }
    }

    private fun startScanning() {
        // FIX 2: Dynamically grab a fresh scanner in case Bluetooth was toggled!
        val currentScanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (bluetoothManager.adapter?.isEnabled == false || currentScanner == null || messageQueue.isEmpty()) {
            isProcessing = false
            return
        }

        isProcessing = true
        _connectionState.value = LoRaConnectionState.SCANNING

        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        currentScanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("ResQMesh_LoRa", "GATT Connected! Requesting MTU 512...")
                // FIX 3: Wait 300ms before negotiating MTU to stabilize Android's BT stack
                Handler(Looper.getMainLooper()).postDelayed({
                    gatt.requestMtu(512)
                }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("ResQMesh_LoRa", "GATT Disconnected.")
                disconnectAndFreeNode()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ResQMesh_LoRa", "MTU changed to $mtu. Discovering services...")
                gatt?.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                writeCharacteristic = service?.getCharacteristic(WRITE_CHAR_UUID)
                val notifyChar = service?.getCharacteristic(NOTIFY_CHAR_UUID)

                if (notifyChar != null && writeCharacteristic != null) {
                    Log.d("ResQMesh_LoRa", "Services found. Enabling notifications...")
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                    _connectionState.value = LoRaConnectionState.CONNECTED
                    // NOTE: We do NOT call sendNextInQueue() here anymore!
                } else {
                    disconnectAndFreeNode()
                }
            }
        }

        // FIX 4: Wait until the OS confirms we are subscribed before sending data!
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ResQMesh_LoRa", "Notifications enabled! Now safe to write JSON.")
                sendNextInQueue()
            } else {
                Log.e("ResQMesh_LoRa", "Failed to subscribe to notifications.")
                disconnectAndFreeNode()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingAck(value)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncomingAck(characteristic.value)
        }

        private fun handleIncomingAck(value: ByteArray) {
            val response = String(value, Charsets.UTF_8)
            Log.d("ResQMesh_LoRa", "ESP32 says: $response")

            if (response.contains("received") && lastSentMessageId != null) {
                onAckReceived?.invoke(lastSentMessageId!!)
                messageQueue.poll()
                lastSentMessageId = null
                disconnectAndFreeNode() // Drop connection instantly so other phones can use the ESP32!
            }
        }
    }

    private fun sendNextInQueue() {
        val nextMsg = messageQueue.peek()
        if (nextMsg != null && writeCharacteristic != null) {
            lastSentMessageId = nextMsg.first
            Log.d("ResQMesh_LoRa", "Sending JSON to ESP32: ${nextMsg.second}")
            writeCharacteristic?.value = nextMsg.second.toByteArray(Charsets.UTF_8)
            writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(writeCharacteristic)
        } else {
            disconnectAndFreeNode()
        }
    }

    private fun disconnectAndFreeNode() {
        _connectionState.value = LoRaConnectionState.DISCONNECTED
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        isProcessing = false

        if (messageQueue.isNotEmpty()) {
            Log.d("ResQMesh_LoRa", "Queue not empty. Restarting sequence for next message in 1s...")
            Handler(Looper.getMainLooper()).postDelayed({
                startScanning()
            }, 1000)
        }
    }
}