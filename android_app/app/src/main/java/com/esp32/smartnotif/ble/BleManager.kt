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

        // 1. Prioritas Utama: Cek apakah ESP32 sudah terpasang/tersimpan di sistem Bluetooth HP
        val bondedDevices = try { adapter.bondedDevices } catch (_: Exception) { null }
        val pairedEsp = bondedDevices?.firstOrNull {
            it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
        }

        if (pairedEsp != null) {
            Log.d(TAG, "Ditemukan di Perangkat Tersimpan: ${pairedEsp.name} [${pairedEsp.address}], langsung menyambungkan...")
            connectToDevice(pairedEsp)
            return
        }

        // 2. Cek apakah sudah terhubung di GATT system profile
        val connectedGatt = try { bluetoothManager.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Exception) { emptyList() }
        val connectedEsp = connectedGatt.firstOrNull {
            it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
        }

        if (connectedEsp != null) {
            Log.d(TAG, "Ditemukan di profil GATT: ${connectedEsp.name}, langsung menyambungkan...")
            connectToDevice(connectedEsp)
            return
        }

        // 3. Jika belum dipairing, lakukan pemindaian BLE aktif
        updateState(ConnectionState.SCANNING, "Memindai ESP32-SmartNotif...")

        val scanner = adapter.bluetoothLeScanner ?: run {
            updateState(ConnectionState.DISCONNECTED, "BLE Scanner tidak tersedia")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        handler.postDelayed({
            if (currentState == ConnectionState.SCANNING) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {}
                updateState(ConnectionState.DISCONNECTED, "Perangkat tidak ditemukan")
            }
        }, BleConstants.SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(null, scanSettings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai scan", e)
            updateState(ConnectionState.DISCONNECTED, e.localizedMessage ?: "Scan error")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: result.scanRecord?.deviceName
                if (name == BleConstants.DEVICE_NAME || name?.contains("ESP32", ignoreCase = true) == true) {
                    Log.d(TAG, "ESP32 ditemukan via scanning: $name [${device.address}]")
                    try {
                        bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                    } catch (_: Exception) {}
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan BLE gagal dengan kode: $errorCode")
            updateState(ConnectionState.DISCONNECTED, "Gagal memindai (Kode: $errorCode)")
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        lastConnectedDevice = device
        updateState(ConnectionState.CONNECTING, "Menyambungkan ke ${device.name ?: "ESP32"}...")

        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect() {
        autoReconnect = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
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
                Log.d(TAG, "Tersambung ke GATT Server ESP32, mencari Service...")
                try {
                    gatt?.requestMtu(128)
                    gatt?.discoverServices()
                } catch (e: Exception) {
                    Log.e(TAG, "Error discoverServices", e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Terputus dari GATT Server")
                rxCharacteristic = null
                txCharacteristic = null
                updateState(ConnectionState.DISCONNECTED, "Terputus")

                if (autoReconnect && lastConnectedDevice != null) {
                    handler.postDelayed({
                        if (currentState == ConnectionState.DISCONNECTED) {
                            startScanAndConnect()
                        }
                    }, 2500)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(BleConstants.CHARACTERISTIC_RX_UUID)
                    txCharacteristic = service.getCharacteristic(BleConstants.CHARACTERISTIC_TX_UUID)

                    // Aktifkan Notifikasi dari TX Characteristic jika ada
                    txCharacteristic?.let { tx ->
                        gatt.setCharacteristicNotification(tx, true)
                        val descriptor = tx.getDescriptor(BleConstants.CCCD_UUID)
                        descriptor?.let { desc ->
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        }
                    }

                    autoReconnect = true
                    updateState(ConnectionState.CONNECTED, "Terhubung ke ESP32-SmartNotif")
                    processNextInQueue()
                } else {
                    Log.e(TAG, "Nordic UART Service tidak ditemukan pada ESP32")
                    updateState(ConnectionState.DISCONNECTED, "Service BLE tidak cocok")
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
            Log.e(TAG, "Gagal memulai write characteristic")
        }
    }
}
