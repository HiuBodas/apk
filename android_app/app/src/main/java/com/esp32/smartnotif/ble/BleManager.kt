package com.esp32.smartnotif.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.esp32.smartnotif.model.BleDeviceItem
import java.util.concurrent.ConcurrentHashMap
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

    interface BleDiscoveryListener {
        fun onDeviceFound(devices: List<BleDeviceItem>)
        fun onDiscoveryStarted()
        fun onDiscoveryFinished()
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val prefs = context.getSharedPreferences("ble_device_prefs", Context.MODE_PRIVATE)

    var savedDeviceAddress: String?
        get() = prefs.getString("selected_device_mac", null)
        set(value) = prefs.edit().putString("selected_device_mac", value).apply()

    var savedDeviceName: String?
        get() = prefs.getString("selected_device_name", null)
        set(value) = prefs.edit().putString("selected_device_name", value).apply()

    var connectedDevice: BluetoothDevice? = null
        private set

    val connectedDeviceAddress: String?
        get() = if (currentState == ConnectionState.CONNECTED) {
            connectedDevice?.address ?: lastConnectedDevice?.address ?: savedDeviceAddress
        } else null

    val connectedDeviceName: String?
        get() = if (currentState == ConnectionState.CONNECTED) {
            connectedDevice?.name ?: lastConnectedDevice?.name ?: savedDeviceName ?: "ESP32-SmartNotif"
        } else null

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    var currentState = ConnectionState.DISCONNECTED
        private set

    private val listeners = mutableListOf<BleStateListener>()
    private val discoveryListeners = mutableListOf<BleDiscoveryListener>()
    private val discoveredDevicesMap = ConcurrentHashMap<String, BleDeviceItem>()

    private val handler = Handler(Looper.getMainLooper())
    private val sendQueue = ConcurrentLinkedQueue<String>()
    private var isWriting = false

    private val writeTimeoutRunnable = Runnable {
        if (isWriting) {
            Log.w(TAG, "Write timeout! Me-reset antrean BLE...")
            isWriting = false
            sendQueue.poll()
            processNextInQueue()
        }
    }

    private val discoveryTimeoutRunnable = Runnable {
        stopDiscovery()
    }

    private var autoReconnect = true
    var lastConnectedDevice: BluetoothDevice? = null
        private set
    private var isScanning = false

    var isDiscovering = false
        private set
    var filterEsp32Only = true
        private set

    fun addListener(listener: BleStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onStateChanged(currentState, "")
        }
    }

    fun removeListener(listener: BleStateListener) {
        listeners.remove(listener)
    }

    fun addDiscoveryListener(listener: BleDiscoveryListener) {
        if (!discoveryListeners.contains(listener)) {
            discoveryListeners.add(listener)
            if (discoveredDevicesMap.isNotEmpty()) {
                val sortedList = getSortedDiscoveredList()
                listener.onDeviceFound(sortedList)
            }
        }
    }

    fun removeDiscoveryListener(listener: BleDiscoveryListener) {
        discoveryListeners.remove(listener)
    }

    private fun updateState(newState: ConnectionState, message: String = "") {
        currentState = newState
        handler.post {
            for (listener in listeners) {
                listener.onStateChanged(newState, message)
            }
        }
        updateDiscoveryDeviceStatus()

        // Kelola Lifecycle Background Service & Auto-Sleep
        try {
            if (newState == ConnectionState.CONNECTED) {
                com.esp32.smartnotif.service.BleForegroundService.startService(context)
            } else if (newState == ConnectionState.DISCONNECTED) {
                com.esp32.smartnotif.service.BleForegroundService.stopService(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling BleForegroundService", e)
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

        if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) return

        // 1. Cek apakah ada ESP32 yang disimpan / disukai di daftar perangkat tersimpan (Bonded)
        val bondedDevices = try { adapter.bondedDevices } catch (_: Exception) { null }
        val targetMac = savedDeviceAddress
        val pairedEsp = bondedDevices?.firstOrNull {
            if (targetMac != null) {
                it.address.equals(targetMac, ignoreCase = true)
            } else {
                it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
            }
        }

        if (pairedEsp != null) {
            Log.d(TAG, "Ditemukan di Perangkat Tersimpan: ${pairedEsp.name} [${pairedEsp.address}], menghubungkan...")
            connectToDevice(pairedEsp)
            return
        }

        // 2. Cek apakah sudah terhubung di GATT system profile
        val connectedGatt = try { bluetoothManager.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Exception) { emptyList() }
        val connectedEsp = connectedGatt.firstOrNull {
            if (targetMac != null) {
                it.address.equals(targetMac, ignoreCase = true)
            } else {
                it.name == BleConstants.DEVICE_NAME || it.name?.contains("ESP32", ignoreCase = true) == true
            }
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
            val targetMac = savedDeviceAddress

            val isMatch = if (targetMac != null) {
                device.address.equals(targetMac, ignoreCase = true)
            } else {
                name == BleConstants.DEVICE_NAME || name?.contains("ESP32", ignoreCase = true) == true
            }

            if (isMatch) {
                Log.d(TAG, "Target ESP32 ditemukan: $name [${device.address}]")
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

    // Discovery dan pemindaian daftar perangkat BLE
    fun startDiscovery(esp32Only: Boolean = true) {
        val adapter = bluetoothAdapter ?: run {
            updateState(ConnectionState.DISCONNECTED, "Bluetooth tidak tersedia")
            return
        }

        if (!adapter.isEnabled) {
            updateState(ConnectionState.DISCONNECTED, "Bluetooth nonaktif")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            updateState(ConnectionState.DISCONNECTED, "BLE Scanner tidak tersedia")
            return
        }

        filterEsp32Only = esp32Only
        discoveredDevicesMap.clear()

        // Masukkan perangkat yang sedang terhubung jika ada
        connectedDevice?.let { dev ->
            val isEsp = isDeviceEsp32(dev.name ?: "", null)
            if (!filterEsp32Only || isEsp) {
                discoveredDevicesMap[dev.address] = BleDeviceItem(
                    device = dev,
                    name = dev.name ?: "ESP32",
                    address = dev.address,
                    rssi = -50,
                    isEsp32 = isEsp,
                    isConnected = true,
                    isConnecting = false
                )
            }
        }

        if (isDiscovering) {
            try { scanner.stopScan(discoveryScanCallback) } catch (_: Exception) {}
        }
        handler.removeCallbacks(discoveryTimeoutRunnable)

        isDiscovering = true
        handler.post {
            for (listener in discoveryListeners) {
                listener.onDiscoveryStarted()
                listener.onDeviceFound(getSortedDiscoveredList())
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        handler.postDelayed(discoveryTimeoutRunnable, BleConstants.SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(null, scanSettings, discoveryScanCallback)
        } catch (e: Exception) {
            isDiscovering = false
            Log.e(TAG, "Gagal memulai discovery scan", e)
            handler.post {
                for (listener in discoveryListeners) {
                    listener.onDiscoveryFinished()
                }
            }
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false
        handler.removeCallbacks(discoveryTimeoutRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(discoveryScanCallback)
        } catch (_: Exception) {}

        handler.post {
            for (listener in discoveryListeners) {
                listener.onDiscoveryFinished()
            }
        }
    }

    private fun getSortedDiscoveredList(): List<BleDeviceItem> {
        val currConnectedMac = connectedDeviceAddress
        val currConnectingMac = if (currentState == ConnectionState.CONNECTING) lastConnectedDevice?.address else null

        return discoveredDevicesMap.values.map { item ->
            item.copy(
                isConnected = item.address.equals(currConnectedMac, ignoreCase = true),
                isConnecting = item.address.equals(currConnectingMac, ignoreCase = true)
            )
        }.sortedWith(
            compareByDescending<BleDeviceItem> { it.isConnected }
                .thenByDescending { it.isEsp32 }
                .thenByDescending { it.rssi }
        )
    }

    private fun updateDiscoveryDeviceStatus() {
        if (discoveredDevicesMap.isNotEmpty()) {
            val sortedList = getSortedDiscoveredList()
            handler.post {
                for (listener in discoveryListeners) {
                    listener.onDeviceFound(sortedList)
                }
            }
        }
    }

    private fun isDeviceEsp32(name: String, result: ScanResult?): Boolean {
        if (name.contains("ESP32", ignoreCase = true) ||
            name.contains("ESP", ignoreCase = true) ||
            name.contains("SmartNotif", ignoreCase = true) ||
            name.contains("C3", ignoreCase = true)) {
            return true
        }
        val serviceUuids = result?.scanRecord?.serviceUuids
        if (serviceUuids != null) {
            for (uuid in serviceUuids) {
                if (uuid.uuid == BleConstants.SERVICE_UUID ||
                    uuid.uuid.toString().lowercase().contains("6e400001")) {
                    return true
                }
            }
        }
        return false
    }

    private val discoveryScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val rawName = device.name ?: result.scanRecord?.deviceName ?: ""
            val address = device.address ?: return
            val rssi = result.rssi

            val isEsp = isDeviceEsp32(rawName, result)

            if (filterEsp32Only && !isEsp) {
                return
            }

            val displayName = when {
                rawName.isNotBlank() -> rawName
                isEsp -> "ESP32 C3"
                else -> "Perangkat BLE"
            }

            val isConnected = (currentState == ConnectionState.CONNECTED && address.equals(connectedDeviceAddress, ignoreCase = true))
            val isConnecting = (currentState == ConnectionState.CONNECTING && address.equals(lastConnectedDevice?.address, ignoreCase = true))

            val existing = discoveredDevicesMap[address]
            if (existing != null) {
                existing.rssi = rssi
                existing.isConnected = isConnected
                existing.isConnecting = isConnecting
            } else {
                discoveredDevicesMap[address] = BleDeviceItem(
                    device = device,
                    name = displayName,
                    address = address,
                    rssi = rssi,
                    isEsp32 = isEsp,
                    isConnected = isConnected,
                    isConnecting = isConnecting
                )
            }

            val sortedList = getSortedDiscoveredList()
            handler.post {
                for (listener in discoveryListeners) {
                    listener.onDeviceFound(sortedList)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan discovery gagal: $errorCode")
            stopDiscovery()
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (isDiscovering) {
            stopDiscovery()
        }
        if (isScanning) {
            isScanning = false
            try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }

        savedDeviceAddress = device.address
        savedDeviceName = device.name ?: "ESP32"
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
        stopDiscovery()
        isScanning = false
        connectedDevice = null
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
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = gatt.device
                updateDiscoveryDeviceStatus()
                Log.d(TAG, "Tersambung ke GATT Server ESP32. Menegosiasikan MTU 512...")
                handler.postDelayed({
                    try {
                        val requested = gatt.requestMtu(512)
                        if (!requested) {
                            Log.w(TAG, "requestMtu gagal dipanggil, fallback ke discoverServices...")
                            gatt.discoverServices()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requestMtu", e)
                        gatt.discoverServices()
                    }
                }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Terputus dari GATT Server (status: $status)")
                handler.removeCallbacks(writeTimeoutRunnable)
                isWriting = false
                connectedDevice = null
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

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU berhasil diubah: $mtu (status: $status). Menjalankan discoverServices...")
            handler.postDelayed({
                try {
                    gatt.discoverServices()
                } catch (e: Exception) {
                    Log.e(TAG, "Error discoverServices setelah onMtuChanged", e)
                }
            }, 200)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
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
                                @Suppress("DEPRECATION")
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
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
                    updateState(ConnectionState.CONNECTED, "Terhubung ke ESP32")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.removeCallbacks(writeTimeoutRunnable)
            isWriting = false
            val sentMsg = sendQueue.poll() ?: ""
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            Log.d(TAG, "onCharacteristicWrite status: $status (success: $success), msg: $sentMsg")
            handler.post {
                for (listener in listeners) {
                    listener.onDataSent(sentMsg, success)
                }
            }
            processNextInQueue()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.getStringValue(0) ?: return
            handler.post {
                for (listener in listeners) {
                    listener.onDataReceived(value)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val str = String(value, Charsets.UTF_8)
            handler.post {
                for (listener in listeners) {
                    listener.onDataReceived(str)
                }
            }
        }
    }

    fun sendData(message: String) {
        sendQueue.add(message)
        if (currentState != ConnectionState.CONNECTED) {
            Log.d(TAG, "Data diantrekan, mencoba menyambungkan ulang ke ESP32...")
            startScanAndConnect()
        } else {
            processNextInQueue()
        }
    }

    @Synchronized
    private fun processNextInQueue() {
        if (isWriting || currentState != ConnectionState.CONNECTED) return
        val msg = sendQueue.peek() ?: return

        val gatt = bluetoothGatt ?: return
        val rx = rxCharacteristic ?: return

        val bytes = msg.toByteArray(Charsets.UTF_8)
        isWriting = true
        handler.removeCallbacks(writeTimeoutRunnable)
        handler.postDelayed(writeTimeoutRunnable, 1500) // Watchdog 1.5 detik

        var started = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeResult = gatt.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                started = (writeResult == 0)
            } else {
                @Suppress("DEPRECATION")
                rx.value = bytes
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                started = gatt.writeCharacteristic(rx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saat menulis data ke characteristic", e)
            started = false
        }

        if (!started) {
            Log.e(TAG, "Gagal mengirim data write characteristic")
            handler.removeCallbacks(writeTimeoutRunnable)
            isWriting = false
            sendQueue.poll() // Hapus item gagal agar tidak memblokir antrean
            processNextInQueue()
        }
    }
}
