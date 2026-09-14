package com.esp32.smartnotif.model

import android.bluetooth.BluetoothDevice

data class BleDeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    var rssi: Int,
    val isEsp32: Boolean,
    var isConnected: Boolean = false,
    var isConnecting: Boolean = false
)
