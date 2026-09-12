package com.esp32.smartnotif.model

data class NotifLogItem(
    val appCode: String,     // Contoh: "WA", "TG", "SMS", "IG", "NOTIF"
    val sender: String,      // Nama kontak / Pengirim
    val message: String,     // Isi pesan
    val timestamp: String    // Format jam:menit
)
