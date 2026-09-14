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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.esp32.smartnotif.MainActivity
import com.esp32.smartnotif.R
import com.esp32.smartnotif.ble.BleManager

class BleForegroundService : Service(), BleManager.BleStateListener {

    companion object {
        const val CHANNEL_ID = "esp32_smartnotif_service_channel"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "com.esp32.smartnotif.ACTION_DISCONNECT"

        fun startService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        bleManager.addListener(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            bleManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: "ESP32-C3"
        val notification = buildForegroundNotification("Terhubung ke $devName • Meneruskan notifikasi real-time")

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

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ESP32SmartNotif:BleWakeLock")
            wakeLock?.setReferenceCounted(false)
        }
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // Safeguard batas waktu 24 jam
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        if (state == BleManager.ConnectionState.DISCONNECTED) {
            // Ketika Bluetooth diputus, hentikan service agar HP benar-benar masuk mode sleep!
            stopSelf()
        } else if (state == BleManager.ConnectionState.CONNECTED) {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: "ESP32-C3"
            val notif = buildForegroundNotification("Terhubung ke $devName • Meneruskan notifikasi real-time")
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, notif)
        }
    }

    override fun onDataReceived(data: String) {}
    override fun onDataSent(data: String, success: Boolean) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESP32 Smart Notif Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi Bluetooth ESP32 tetap aktif di latar belakang"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BleForegroundService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 Smart Notif")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_delete, "Putuskan", disconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.removeListener(this)
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
