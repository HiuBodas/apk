package com.esp32.smartnotif.utils

import android.content.Context
import android.content.SharedPreferences

class AppFilterManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_filter_prefs", Context.MODE_PRIVATE)

    var isWhatsAppEnabled: Boolean
        get() = prefs.getBoolean("filter_wa", true)
        set(value) = prefs.edit().putBoolean("filter_wa", value).apply()

    var isTelegramEnabled: Boolean
        get() = prefs.getBoolean("filter_telegram", true)
        set(value) = prefs.edit().putBoolean("filter_telegram", value).apply()

    var isSmsEnabled: Boolean
        get() = prefs.getBoolean("filter_sms", true)
        set(value) = prefs.edit().putBoolean("filter_sms", value).apply()

    var isInstagramEnabled: Boolean
        get() = prefs.getBoolean("filter_ig", true)
        set(value) = prefs.edit().putBoolean("filter_ig", value).apply()

    var isOtherAppsEnabled: Boolean
        get() = prefs.getBoolean("filter_other", false)
        set(value) = prefs.edit().putBoolean("filter_other", value).apply()

    fun isPackageAllowed(packageName: String): Boolean {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> isWhatsAppEnabled
            packageName.contains("telegram", ignoreCase = true) -> isTelegramEnabled
            packageName.contains("mms", ignoreCase = true) ||
            packageName.contains("messaging", ignoreCase = true) ||
            packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("telecom", ignoreCase = true) -> isSmsEnabled
            packageName.contains("instagram", ignoreCase = true) -> isInstagramEnabled
            else -> isOtherAppsEnabled
        }
    }

    fun getAppCode(packageName: String): String {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> "WA"
            packageName.contains("telegram", ignoreCase = true) -> "TG"
            packageName.contains("mms", ignoreCase = true) ||
            packageName.contains("messaging", ignoreCase = true) -> "SMS"
            packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("telecom", ignoreCase = true) -> "CALL"
            packageName.contains("instagram", ignoreCase = true) -> "IG"
            else -> "APP"
        }
    }
}
