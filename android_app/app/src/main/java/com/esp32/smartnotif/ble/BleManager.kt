package com.esp32.smartnotif.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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

        updateState(ConnectionState.SCANNING, "Memindai ESP32-SmartNotif...")

        val scanner = adapter.bluetoothLeScanner ?: run {
            updateState(ConnectionState.DISCONNECTED, "BLE Scanner tidak tersedia")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(BleConstants.DEVICE_NAME).build()
        )

        handler.postDelayed({
            if (currentState == ConnectionState.SCANNING) {
                scanner.stopScan(scanCallback)
                updateState(ConnectionState.DISCONNECTED, "Perangkat tidak ditemukan")
            }
        }, BleConstants.SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(filters, scanSettings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai scan", e)
            updateState(ConnectionState.DISCONNECTED, e.localizedMessage ?: "Scan error")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name
                if (name == BleConstants.DEVICE_NAME || name?.contains("ESP32") == true) {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
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

        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        autoReconnect = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        updateState(ConnectionState.DISCONNECTED, "Koneksi diputus")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Tersambung ke GATT Server, memulai discovery service...")
                gatt?.requestMtu(128)
                gatt?.discoverServices()
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
                    }, 3000)
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
