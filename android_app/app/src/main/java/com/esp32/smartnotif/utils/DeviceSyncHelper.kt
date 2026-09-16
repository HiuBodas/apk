package com.esp32.smartnotif.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DeviceSyncHelper {

    /**
     * Mendapatkan nama hari dalam bahasa Indonesia.
     */
    fun getIndonesianDayName(calendar: Calendar = Calendar.getInstance()): String {
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Minggu"
            Calendar.MONDAY -> "Senin"
            Calendar.TUESDAY -> "Selasa"
            Calendar.WEDNESDAY -> "Rabu"
            Calendar.THURSDAY -> "Kamis"
            Calendar.FRIDAY -> "Jumat"
            Calendar.SATURDAY -> "Sabtu"
            else -> "Senin"
        }
    }

    /**
     * Mendapatkan persentase kapasitas baterai HP saat ini (0-100%).
     */
    fun getBatteryPercentage(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 0..100) {
            return capacity
        }

        // Fallback jika getIntProperty tidak didukung oleh hardware tertentu
        return try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, iFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Membangun payload string waktu dan baterai sesuai format ESP32:
     * [TIME] HH:mm:ss|dd/MM/yyyy|Hari|Baterai
     * Contoh: "[TIME] 14:30:00|16/09/2026|Rabu|85"
     */
    fun buildTimeBatteryPayload(context: Context, batteryLevelOverride: Int? = null): String {
        val calendar = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        val timeStr = timeFormat.format(calendar.time)
        val dateStr = dateFormat.format(calendar.time)
        val dayStr = getIndonesianDayName(calendar)
        val batteryStr = (batteryLevelOverride ?: getBatteryPercentage(context)).toString()

        return "[TIME] $timeStr|$dateStr|$dayStr|$batteryStr"
    }
}
