package com.esp32.smartnotif.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.utils.WeatherManager
import java.util.concurrent.TimeUnit

class WeatherWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "WeatherWorker"
        const val PERIODIC_WORK_NAME = "esp32_weather_periodic_work"
        const val ONE_TIME_WORK_NAME = "esp32_weather_immediate_work"

        /**
         * Menjadwalkan pembaruan cuaca berkala setiap 30-45 menit.
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequest.Builder(WeatherWorker::class.java, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d(TAG, "Penjadwalan update cuaca berkala (30 menit) aktif.")
        }

        /**
         * Memicu pembaruan cuaca segera satu kali (misal saat BLE baru terhubung).
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequest.Builder(WeatherWorker::class.java)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
            Log.d(TAG, "Pembaruan cuaca segera (One-time) dipicu.")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Memulai tugas latar belakang pembaruan cuaca Open-Meteo...")
        return try {
            val payload = WeatherManager.fetchWeatherPayload(applicationContext)
            if (!payload.isNullOrEmpty()) {
                val bleManager = BleManager.getInstance(applicationContext)
                if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
                    Log.d(TAG, "ESP32 terhubung. Mengirimkan string cuaca: $payload")
                    bleManager.sendData(payload)
                } else {
                    Log.d(TAG, "Data cuaca tersimpan di cache (ESP32 tidak dalam status CONNECTED).")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan tugas cuaca", e)
            Result.retry()
        }
    }
}
