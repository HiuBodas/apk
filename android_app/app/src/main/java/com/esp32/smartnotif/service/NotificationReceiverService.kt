package com.esp32.smartnotif.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.utils.AppFilterManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationReceiverService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifReceiver"
        const val ACTION_NEW_NOTIF_LOG = "com.esp32.smartnotif.ACTION_NEW_NOTIF_LOG"
        const val EXTRA_APP_CODE = "extra_app_code"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TIME = "extra_time"

        private var lastSentMessage = ""
        private var lastSentTimestamp = 0L

        private var lastSentNavPayload = ""
        private var lastSentNavTimestamp = 0L
    }

    private lateinit var appFilterManager: AppFilterManager

    override fun onCreate() {
        super.onCreate()
        appFilterManager = AppFilterManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // Abaikan notifikasi dari aplikasi ini sendiri
        if (packageName == applicationContext.packageName) return

        // Navigasi Google Maps ditangani terisolasi agar tidak terpengaruh filter chat
        if (packageName == "com.google.android.apps.maps") {
            handleGoogleMapsNavigation(sbn)
            return
        }

        // Periksa apakah aplikasi diizinkan lewat filter (WhatsApp, Telegram, SMS, IG, dll)
        if (!appFilterManager.isPackageAllowed(packageName)) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        // Abaikan notifikasi kosong atau notifikasi progress download / system
        if (title.isEmpty() && text.isEmpty()) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val sender = if (title.isNotEmpty()) title else "Pemberitahuan"
        val message = if (text.isNotEmpty()) text else "Pesan baru"
        val appCode = appFilterManager.getAppCode(packageName)

        // Cegah duplikasi notifikasi yang dikirim berulang dalam waktu sangat singkat (< 1.5 detik)
        val currentTime = System.currentTimeMillis()
        val combinedKey = "$appCode:$sender:$message"
        if (combinedKey == lastSentMessage && (currentTime - lastSentTimestamp) < 1500) {
            return
        }
        lastSentMessage = combinedKey
        lastSentTimestamp = currentTime

        // Format pesan sesuai yang dipahami ESP32: [APP] Sender: Message
        val payload = "[$appCode] $sender: $message"
        Log.d(TAG, "Meneruskan Notifikasi ke ESP32: $payload")

        BleManager.getInstance(this).sendData(payload)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date())

        // Broadcast ke UI MainActivity
        val intent = Intent(ACTION_NEW_NOTIF_LOG).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_APP_CODE, appCode)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_TIME, timeStr)
        }
        sendBroadcast(intent)
    }

    /**
     * Memproses notifikasi belokan dari Google Maps:
     * Format: [NAV] Instruksi Jalan|Jarak|ETA|1
     */
    private fun handleGoogleMapsNavigation(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Instruksi belokan atau nama jalan (EXTRA_TITLE)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""

        // Jarak dan ETA (EXTRA_TEXT / EXTRA_SUB_TEXT)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        val turnInstruction = if (title.isNotEmpty()) title.replace("|", "-") else "Navigasi"
        var distance = "-"
        var eta = "-"

        if (text.isNotEmpty()) {
            val parts = text.split(Regex("[·•\\|]")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                distance = parts[0]
                eta = parts[1]
            } else {
                distance = text.replace("|", "-")
                if (subText.isNotEmpty()) {
                    eta = subText.replace("|", "-")
                }
            }
        } else if (subText.isNotEmpty()) {
            distance = subText.replace("|", "-")
        }

        val navPayload = "[NAV] $turnInstruction|$distance|$eta|1"

        // Cegah spam update jika string navigasi sama persis
        val currentTime = System.currentTimeMillis()
        if (navPayload == lastSentNavPayload && (currentTime - lastSentNavTimestamp) < 1000) {
            return
        }

        lastSentNavPayload = navPayload
        lastSentNavTimestamp = currentTime

        Log.d(TAG, "Meneruskan Navigasi Maps ke ESP32: $navPayload")
        BleManager.getInstance(this).sendData(navPayload)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date())
        val intent = Intent(ACTION_NEW_NOTIF_LOG).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_APP_CODE, "NAV")
            putExtra(EXTRA_SENDER, turnInstruction)
            putExtra(EXTRA_MESSAGE, "$distance • $eta")
            putExtra(EXTRA_TIME, timeStr)
        }
        sendBroadcast(intent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (packageName == "com.google.android.apps.maps") {
            Log.d(TAG, "Navigasi Google Maps ditutup. Mengirim: [NAV] STOP")
            lastSentNavPayload = ""
            BleManager.getInstance(this).sendData("[NAV] STOP")

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr = timeFormat.format(Date())
            val intent = Intent(ACTION_NEW_NOTIF_LOG).apply {
                setPackage(applicationContext.packageName)
                putExtra(EXTRA_APP_CODE, "NAV")
                putExtra(EXTRA_SENDER, "Google Maps")
                putExtra(EXTRA_MESSAGE, "Navigasi Berhenti (STOP)")
                putExtra(EXTRA_TIME, timeStr)
            }
            sendBroadcast(intent)
        }
    }
}
