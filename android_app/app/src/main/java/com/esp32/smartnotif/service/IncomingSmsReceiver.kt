package com.esp32.smartnotif.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.esp32.smartnotif.ble.BleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IncomingSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingSms"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val sender = sms.displayOriginatingAddress ?: "SMS"
                    val body = sms.displayMessageBody ?: ""

                    val payload = "[SMS] $sender: $body"
                    Log.d(TAG, "Meneruskan SMS ke ESP32: $payload")
                    BleManager.getInstance(context).sendData(payload)

                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val timeStr = timeFormat.format(Date())

                    val logIntent = Intent(NotificationReceiverService.ACTION_NEW_NOTIF_LOG).apply {
                        setPackage(context.packageName)
                        putExtra(NotificationReceiverService.EXTRA_APP_CODE, "SMS")
                        putExtra(NotificationReceiverService.EXTRA_SENDER, sender)
                        putExtra(NotificationReceiverService.EXTRA_MESSAGE, body)
                        putExtra(NotificationReceiverService.EXTRA_TIME, timeStr)
                    }
                    context.sendBroadcast(logIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error membaca SMS", e)
            }
        }
    }
}
