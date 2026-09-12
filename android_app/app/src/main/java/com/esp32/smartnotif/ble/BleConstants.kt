package com.esp32.smartnotif.ble

import java.util.UUID

object BleConstants {
    const val DEVICE_NAME = "ESP32-SmartNotif"

    // Nordic UART Service UUIDs (sama persis dengan yang ada di esp32_ble_oled_buttons.ino)
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val CHARACTERISTIC_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Karakteristik untuk Menulis dari HP ke ESP32
    val CHARACTERISTIC_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Karakteristik Notifikasi dari ESP32 ke HP
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val SCAN_TIMEOUT_MS = 15000L
}
