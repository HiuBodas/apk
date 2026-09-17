package com.esp32.smartnotif.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.esp32.smartnotif.MainActivity
import com.esp32.smartnotif.R
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.utils.DeviceSyncHelper
import com.esp32.smartnotif.utils.WeatherManager

class BleForegroundService : Service(), BleManager.BleStateListener {

    companion object {
        private const val TAG = "BleForegroundService"
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

    private var lastBatteryLevel: Int = -1
    private var isBatteryReceiverRegistered = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                if (pct >= 0 && pct != lastBatteryLevel) {
                    lastBatteryLevel = pct
                    android.util.Log.d(TAG, "Persentase baterai HP diperbarui ke $pct%. Mengirimkan [TIME]...")
                    sendTimeAndBatterySync(pct)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        bleManager.addListener(this)
        createNotificationChannel()
        acquireWakeLock()

        if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            onConnectedActions()
        }
    }

    private fun registerBatteryReceiver() {
        if (!isBatteryReceiverRegistered) {
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(batteryReceiver, filter)
                }
                isBatteryReceiverRegistered = true
                Log.d(TAG, "BatteryReceiver (ACTION_BATTERY_CHANGED) berhasil didaftarkan.")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mendaftarkan batteryReceiver", e)
            }
        }
    }

    private fun unregisterBatteryReceiver() {
        if (isBatteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
            isBatteryReceiverRegistered = false
            Log.d(TAG, "BatteryReceiver dilepas.")
        }
    }

    private fun sendTimeAndBatterySync(batteryLevelOverride: Int? = null) {
        if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            val payload = com.esp32.smartnotif.utils.DeviceSyncHelper.buildTimeBatteryPayload(this, batteryLevelOverride)
            Log.d(TAG, "Sinkronisasi Waktu & Baterai ke ESP32: $payload")
            bleManager.sendData(payload)
        }
    }

    private fun onConnectedActions() {
        // Sinkronisasi jam dan baterai ponsel
        lastBatteryLevel = com.esp32.smartnotif.utils.DeviceSyncHelper.getBatteryPercentage(this)
        sendTimeAndBatterySync(lastBatteryLevel)
        registerBatteryReceiver()

        // Sinkronisasi cuaca Open-Meteo
        val cachedWeather = com.esp32.smartnotif.utils.WeatherManager.getLastSavedPayload(this)
        if (!cachedWeather.isNullOrEmpty()) {
            Log.d(TAG, "Mengirimkan data cuaca cache ke ESP32: $cachedWeather")
            bleManager.sendData(cachedWeather)
        }
        // Picu pengambilan data cuaca terbaru secara langsung
        com.esp32.smartnotif.service.WeatherWorker.triggerImmediateSync(this)
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

        if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            onConnectedActions()
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
            unregisterBatteryReceiver()
            // Ketika Bluetooth diputus, hentikan service agar HP benar-benar masuk mode sleep!
            stopSelf()
        } else if (state == BleManager.ConnectionState.CONNECTED) {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: "ESP32-C3"
            val notif = buildForegroundNotification("Terhubung ke $devName • Meneruskan notifikasi real-time")
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, notif)

            onConnectedActions()
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
        unregisterBatteryReceiver()
        bleManager.removeListener(this)
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
