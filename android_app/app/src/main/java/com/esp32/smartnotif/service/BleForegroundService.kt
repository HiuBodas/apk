package com.esp32.smartnotif.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.esp32.smartnotif.MainActivity
import com.esp32.smartnotif.ble.BleManager

class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "esp32_smartnotif_service_channel"
        const val NOTIF_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val bleStateListener = object : BleManager.BleStateListener {
        override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
            updateNotification("Status: ${state.name}")
        }

        override fun onDataReceived(data: String) {}
        override fun onDataSent(data: String, success: Boolean) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BleManager.getInstance(this).addListener(bleStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification("Layanan pengirim notifikasi aktif")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESP32 Smart Notif Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi Bluetooth ESP32 tetap tersambung"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 Smart Notif")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildForegroundNotification(text))
    }

    override fun onDestroy() {
        BleManager.getInstance(this).removeListener(bleStateListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
