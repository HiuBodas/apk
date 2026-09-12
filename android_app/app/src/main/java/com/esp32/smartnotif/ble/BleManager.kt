package com.esp32.smartnotif.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    interface BleStateListener {
        fun onStateChanged(state: ConnectionState, message: String)
        fun onDataReceived(data: String)
        fun onDataSent(data: String, success: Boolean)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    var currentState = ConnectionState.DISCONNECTED
        private set

    private val listeners = mutableListOf<BleStateListener>()
    private val handler = Handler(Looper.getMainLooper())
    private val sendQueue = ConcurrentLinkedQueue<String>()
    private var isWriting = false

    private var autoReconnect = true
    private var lastConnectedDevice: BluetoothDevice? = null
    private var isScanning = false

    fun addListener(listener: BleStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onStateChanged(currentState, "")
        }
    }

    fun removeListener(listener: BleStateListener) {
        listeners.remove(listener)
    }

    private fun updateState(newState: ConnectionState, message: String = "") {
        currentState = newState
        handler.post {
            for (listener in listeners) {
                listener.onStateChanged(newState, message)
            }
        }
    }

    fun startScanAndConnect() {
        val adapter = bluetoothAdapter ?: run {
            updateState(ConnectionState.DISCONNECTED, "Bluetooth tidak tersedia")
            return
        }

        if (!adapter.isEnabled) {
            updateState(ConnectionState.DISCONNECTED, "Bluetooth nonaktif")
            return
        }

        if (currentState == ConnectionState.CONNECTED) return

        // 1. Cek apakah ada ESP32 di daftar perangkat tersimpan (Bonded)
        val bondedDevices = try { adapter.bondedDevices } catch (_: Exception) { null }
        val pairedEsp = bondedDevices?.firstOrNull {
            it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
        }

        if (pairedEsp != null) {
            Log.d(TAG, "Ditemukan di Perangkat Tersimpan: ${pairedEsp.name} [${pairedEsp.address}], menghubungkan...")
            connectToDevice(pairedEsp)
            return
        }

        // 2. Cek apakah sudah terhubung di GATT system profile
        val connectedGatt = try { bluetoothManager.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Exception) { emptyList() }
        val connectedEsp = connectedGatt.firstOrNull {
            it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
        }

        if (connectedEsp != null) {
            Log.d(TAG, "Ditemukan di profil GATT: ${connectedEsp.name}, menghubungkan...")
            connectToDevice(connectedEsp)
            return
        }

        // 3. Scan BLE aktif
        val scanner = adapter.bluetoothLeScanner ?: run {
            updateState(ConnectionState.DISCONNECTED, "BLE Scanner tidak tersedia")
            return
        }

        if (isScanning) {
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
        }

        updateState(ConnectionState.SCANNING, "Memindai ESP32-SmartNotif...")
        isScanning = true

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        handler.postDelayed({
            if (isScanning && currentState == ConnectionState.SCANNING) {
                isScanning = false
                try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                updateState(ConnectionState.DISCONNECTED, "ESP32 belum ditemukan")
            }
        }, BleConstants.SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(null, scanSettings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            Log.e(TAG, "Gagal memulai scan", e)
            updateState(ConnectionState.DISCONNECTED, e.localizedMessage ?: "Scan error")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName

            if (name == BleConstants.DEVICE_NAME || name?.contains("ESP32", ignoreCase = true) == true) {
                Log.d(TAG, "ESP32 ditemukan: $name [${device.address}]")
                isScanning = false
                try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Exception) {}
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan BLE gagal: $errorCode")
            updateState(ConnectionState.DISCONNECTED, "Scan gagal ($errorCode)")
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        lastConnectedDevice = device
        updateState(ConnectionState.CONNECTING, "Menghubungkan ke ${device.name ?: "ESP32"}...")

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}

        // Menghubungkan via Transport LE
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect() {
        autoReconnect = false
        isScanning = false
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        updateState(ConnectionState.DISCONNECTED, "Koneksi diputus")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Tersambung ke GATT Server ESP32. Delay 400ms sebelum discoverServices...")
                // Delay 400ms penting untuk stabilitas GATT Android
                handler.postDelayed({
                    try {
                        gatt?.discoverServices()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error discoverServices", e)
                    }
                }, 400)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Terputus dari GATT Server (status: $status)")
                rxCharacteristic = null
                txCharacteristic = null
                updateState(ConnectionState.DISCONNECTED, "ESP32 Terputus")

                if (autoReconnect) {
                    handler.postDelayed({
                        if (currentState == ConnectionState.DISCONNECTED) {
                            startScanAndConnect()
                        }
                    }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                Log.d(TAG, "Layanan ditemukan! Mencari karakteristik RX & TX...")
                var foundRx: BluetoothGattCharacteristic? = null
                var foundTx: BluetoothGattCharacteristic? = null

                for (service in gatt.services) {
                    for (ch in service.characteristics) {
                        val uuidStr = ch.uuid.toString().lowercase()
                        if (uuidStr.contains("6e400002") || ch.uuid == BleConstants.CHARACTERISTIC_RX_UUID) {
                            foundRx = ch
                        }
                        if (uuidStr.contains("6e400003") || ch.uuid == BleConstants.CHARACTERISTIC_TX_UUID) {
                            foundTx = ch
                        }
                    }
                }

                if (foundRx != null) {
                    rxCharacteristic = foundRx
                    txCharacteristic = foundTx

                    // Aktifkan notifikasi jika TX ditemukan
                    foundTx?.let { tx ->
                        try {
                            gatt.setCharacteristicNotification(tx, true)
                            val descriptor = tx.getDescriptor(BleConstants.CCCD_UUID)
                            descriptor?.let { desc ->
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(desc)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error set notification", e)
                        }
                    }

                    autoReconnect = true
                    updateState(ConnectionState.CONNECTED, "Terhubung ke ESP32-SmartNotif")
                    processNextInQueue()
                } else {
                    Log.e(TAG, "Karakteristik RX tidak ditemukan pada ESP32")
                    // Tetap nyatakan terhubung jika GATT sudah connect
                    updateState(ConnectionState.CONNECTED, "Terhubung ke ESP32")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            isWriting = false
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            handler.post {
                for (listener in listeners) {
                    listener.onDataSent(characteristic?.getStringValue(0) ?: "", success)
                }
            }
            processNextInQueue()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val value = characteristic?.getStringValue(0) ?: return
            handler.post {
                for (listener in listeners) {
                    listener.onDataReceived(value)
                }
            }
        }
    }

    fun sendData(message: String) {
        sendQueue.add(message)
        processNextInQueue()
    }

    @Synchronized
    private fun processNextInQueue() {
        if (isWriting || currentState != ConnectionState.CONNECTED) return
        val msg = sendQueue.poll() ?: return

        val gatt = bluetoothGatt ?: return
        val rx = rxCharacteristic ?: return

        rx.value = msg.toByteArray(Charsets.UTF_8)
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        isWriting = true
        val started = gatt.writeCharacteristic(rx)
        if (!started) {
            isWriting = false
            Log.e(TAG, "Gagal mengirim data write characteristic")
        }
    }
}
