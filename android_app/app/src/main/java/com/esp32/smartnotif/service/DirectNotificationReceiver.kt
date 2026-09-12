package com.esp32.smartnotif.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.esp32.smartnotif.ble.BleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DirectNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DirectNotif"
        const val ACTION_SEND_TEXT = "com.esp32.smartnotif.SEND_TEXT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val rawMessage = intent.getStringExtra("message")
            ?: intent.getStringExtra("text")
            ?: intent.getStringExtra("extra_message")
            ?: intent.getStringExtra("android.intent.extra.TEXT")
            ?: ""

        val appCode = intent.getStringExtra("app") ?: "WA"
        val sender = intent.getStringExtra("sender") ?: "Pesan"
        val body = intent.getStringExtra("body") ?: rawMessage

        if (rawMessage.isEmpty() && body.isEmpty()) return

        val payload = if (rawMessage.startsWith("[")) {
            rawMessage
        } else {
            "[$appCode] $sender: $body"
        }

        Log.d(TAG, "Menerima pesan langsung dari Broadcast: $payload")
        BleManager.getInstance(context).sendData(payload)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date())

        val logIntent = Intent(NotificationReceiverService.ACTION_NEW_NOTIF_LOG).apply {
            setPackage(context.packageName)
            putExtra(NotificationReceiverService.EXTRA_APP_CODE, appCode)
            putExtra(NotificationReceiverService.EXTRA_SENDER, sender)
            putExtra(NotificationReceiverService.EXTRA_MESSAGE, body)
            putExtra(NotificationReceiverService.EXTRA_TIME, timeStr)
        }
        context.sendBroadcast(logIntent)
    }
}
