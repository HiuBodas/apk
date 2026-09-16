package com.esp32.smartnotif.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

object WeatherManager {

    private const val TAG = "WeatherManager"
    private const val PREFS_NAME = "weather_prefs"
    private const val KEY_LAST_WEATHER_PAYLOAD = "last_weather_payload"
    private const val KEY_LAST_LATITUDE = "last_lat"
    private const val KEY_LAST_LONGITUDE = "last_lon"
    private const val KEY_LAST_CITY = "last_city"

    // Default fallback coordinates (Jakarta) jika GPS belum mendapatkan sinyal
    private const val DEFAULT_LAT = -6.2088
    private const val DEFAULT_LON = 106.8456
    private const val DEFAULT_CITY = "Jakarta"

    /**
     * Konversi WMO Weather Code ke kode angka (0-9):
     * 0 = Cerah
     * 1-3 = Berawan
     * 51-67, 80-82 = Hujan
     * 95-99 = Badai
     */
    fun convertWeatherCodeToIcon(wmoCode: Int): Int {
        return when (wmoCode) {
            0 -> 0 // Cerah
            in 1..3 -> 1 // Berawan
            in 45..48 -> 1 // Kabut / Berawan
            in 51..67, in 80..82 -> 2 // Hujan
            in 71..77, in 85..86 -> 2 // Salju / Hujan
            in 95..99 -> 3 // Badai
            else -> 1 // Default ke Berawan
        }
    }

    /**
     * Mengambil lokasi HP terkini (GPS / Network).
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!PermissionHelper.hasLocationPermission(context)) {
            Log.w(TAG, "Izin lokasi belum diberikan. Menggunakan koordinat tersimpan atau default.")
            val savedLat = prefs.getString(KEY_LAST_LATITUDE, DEFAULT_LAT.toString())?.toDoubleOrNull() ?: DEFAULT_LAT
            val savedLon = prefs.getString(KEY_LAST_LONGITUDE, DEFAULT_LON.toString())?.toDoubleOrNull() ?: DEFAULT_LON
            return Pair(savedLat, savedLon)
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var bestLocation: Location? = null

        try {
            val providers = locationManager?.getProviders(true) ?: emptyList()
            for (provider in providers) {
                val loc = locationManager?.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membaca lokasi HP", e)
        }

        return if (bestLocation != null) {
            prefs.edit()
                .putString(KEY_LAST_LATITUDE, bestLocation.latitude.toString())
                .putString(KEY_LAST_LONGITUDE, bestLocation.longitude.toString())
                .apply()
            Pair(bestLocation.latitude, bestLocation.longitude)
        } else {
            val savedLat = prefs.getString(KEY_LAST_LATITUDE, DEFAULT_LAT.toString())?.toDoubleOrNull() ?: DEFAULT_LAT
            val savedLon = prefs.getString(KEY_LAST_LONGITUDE, DEFAULT_LON.toString())?.toDoubleOrNull() ?: DEFAULT_LON
            Pair(savedLat, savedLon)
        }
    }

    /**
     * Mengambil nama kota pengguna berdasarkan koordinat via Geocoder.
     */
    fun getCityName(context: Context, latitude: Double, longitude: Double): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCity = prefs.getString(KEY_LAST_CITY, DEFAULT_CITY) ?: DEFAULT_CITY

        return try {
            val geocoder = Geocoder(context, Locale("id", "ID"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val rawCity = addr.subAdminArea ?: addr.locality ?: addr.adminArea ?: savedCity
                val cleanedCity = rawCity
                    .replace("Kota ", "", ignoreCase = true)
                    .replace("Kabupaten ", "", ignoreCase = true)
                    .replace("Kab. ", "", ignoreCase = true)
                    .replace("|", "-")
                    .trim()

                prefs.edit().putString(KEY_LAST_CITY, cleanedCity).apply()
                cleanedCity
            } else {
                savedCity
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder gagal atau offline: ${e.localizedMessage}")
            savedCity
        }
    }

    /**
     * Mengunduh data cuaca dari Open-Meteo API dan menghasilkan string format:
     * [WEATHER] Kota|Suhu|KodeIcon|SuhuMax|SuhuMin|UV|Tekanan
     * Contoh: "[WEATHER] Jakarta|29|1|33|24|6|1010"
     */
    suspend fun fetchWeatherPayload(context: Context): String? = withContext(Dispatchers.IO) {
        val (lat, lon) = getLastKnownLocation(context)
        val city = getCityName(context, lat, lon)

        val urlString = "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,surface_pressure,uv_index,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=auto"

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ESP32SmartNotifApp/1.0")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Open-Meteo API HTTP Error: $responseCode")
                return@withContext getLastSavedPayload(context)
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val responseText = reader.use { it.readText() }

            val json = JSONObject(responseText)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val tempCurrent = current.getDouble("temperature_2m").roundToInt()
            val surfacePressure = current.getDouble("surface_pressure").roundToInt()
            val uvIndex = current.getDouble("uv_index").roundToInt()
            val weatherCode = current.getInt("weather_code")
            val iconCode = convertWeatherCodeToIcon(weatherCode)

            val maxTempArray = daily.getJSONArray("temperature_2m_max")
            val minTempArray = daily.getJSONArray("temperature_2m_min")

            val tempMax = if (maxTempArray.length() > 0) maxTempArray.getDouble(0).roundToInt() else tempCurrent
            val tempMin = if (minTempArray.length() > 0) minTempArray.getDouble(0).roundToInt() else tempCurrent

            // Format: [WEATHER] Kota|Suhu|KodeIcon|SuhuMax|SuhuMin|UV|Tekanan
            val payload = "[WEATHER] $city|$tempCurrent|$iconCode|$tempMax|$tempMin|$uvIndex|$surfacePressure"
            Log.d(TAG, "Berhasil memuat data cuaca: $payload")

            savePayload(context, payload)
            return@withContext payload
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Open-Meteo weather data", e)
            return@withContext getLastSavedPayload(context)
        } finally {
            connection?.disconnect()
        }
    }

    private fun savePayload(context: Context, payload: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_WEATHER_PAYLOAD, payload).apply()
    }

    fun getLastSavedPayload(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_WEATHER_PAYLOAD, null)
    }
}
