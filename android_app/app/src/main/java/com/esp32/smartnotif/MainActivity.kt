package com.esp32.smartnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.databinding.ActivityMainBinding
import com.esp32.smartnotif.model.BleDeviceItem
import com.esp32.smartnotif.model.NotifLogItem
import com.esp32.smartnotif.service.BleForegroundService
import com.esp32.smartnotif.service.NotificationReceiverService
import com.esp32.smartnotif.service.WeatherWorker
import com.esp32.smartnotif.ui.BleDeviceAdapter
import com.esp32.smartnotif.ui.LogAdapter
import com.esp32.smartnotif.utils.AppFilterManager
import com.esp32.smartnotif.utils.DeviceSyncHelper
import com.esp32.smartnotif.utils.PermissionHelper
import com.esp32.smartnotif.utils.WeatherManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BleManager.BleStateListener, BleManager.BleDiscoveryListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var appFilterManager: AppFilterManager
    private val logAdapter = LogAdapter()
    private lateinit var bleDeviceAdapter: BleDeviceAdapter

    // Request permissions launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Izin Bluetooth aktif", Toast.LENGTH_SHORT).show()
            bleManager.startScanAndConnect()
        } else {
            Toast.makeText(this, "Izin diperlukan untuk menghubungkan ke ESP32", Toast.LENGTH_LONG).show()
        }
    }

    // Receiver untuk menerima update log notifikasi secara langsung dari service
    private val logBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationReceiverService.ACTION_NEW_NOTIF_LOG) {
                val appCode = intent.getStringExtra(NotificationReceiverService.EXTRA_APP_CODE) ?: "NOTIF"
                val sender = intent.getStringExtra(NotificationReceiverService.EXTRA_SENDER) ?: ""
                val message = intent.getStringExtra(NotificationReceiverService.EXTRA_MESSAGE) ?: ""
                val timeStr = intent.getStringExtra(NotificationReceiverService.EXTRA_TIME) ?: ""

                val item = NotifLogItem(appCode, sender, message, timeStr)
                addLogItem(item)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager.getInstance(this)
        appFilterManager = AppFilterManager(this)

        setupUI()
        setupListeners()
        checkAndRequestPermissions()

        // Jadwalkan update cuaca berkala setiap 30-45 menit via WorkManager
        WeatherWorker.schedulePeriodicWork(this)

        // Jika sudah dalam kondisi terhubung, pastikan Foreground Service tetap berjalan aktif
        if (bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            BleForegroundService.startService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        bleManager.addListener(this)
        bleManager.addDiscoveryListener(this)
        checkNotificationAccess()
        syncSettingSwitches()
        updateSettingBleDeviceCard()

        // Otomatis hubungkan jika sedang terputus
        if (bleManager.currentState == BleManager.ConnectionState.DISCONNECTED &&
            PermissionHelper.hasAllRuntimePermissions(this)) {
            bleManager.startScanAndConnect()
        }

        val filter = IntentFilter(NotificationReceiverService.ACTION_NEW_NOTIF_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logBroadcastReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        bleManager.removeListener(this)
        bleManager.removeDiscoveryListener(this)
        try {
            unregisterReceiver(logBroadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        // Setup RecyclerView Log di Menu Utama
        binding.rvNotifLogs.layoutManager = LinearLayoutManager(this)
        binding.rvNotifLogs.adapter = logAdapter
        updateEmptyLogsView()

        // Setup RecyclerView Daftar Perangkat BLE di Tab Setting
        bleDeviceAdapter = BleDeviceAdapter { selectedDeviceItem ->
            onBleDeviceSelected(selectedDeviceItem)
        }
        binding.rvBleDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBleDevices.adapter = bleDeviceAdapter

        // Sinkronisasi awal saklar setting & kartu BLE
        syncSettingSwitches()
        updateSettingBleDeviceCard()
    }

    private fun setupListeners() {
        // 1. Bottom Navigation Bar (Navbar di bawah)
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.layoutMenuUtama.visibility = View.VISIBLE
                    binding.layoutSetting.visibility = View.GONE
                    true
                }
                R.id.nav_settings -> {
                    binding.layoutMenuUtama.visibility = View.GONE
                    binding.layoutSetting.visibility = View.VISIBLE
                    syncSettingSwitches()
                    updateSettingBleDeviceCard()
                    true
                }
                else -> false
            }
        }

        // 2. Tombol Kirim Tes Notifikasi di Menu Utama
        binding.btnSendTest.setOnClickListener {
            val sender = binding.etTestSender.text?.toString()?.trim() ?: "Pengirim"
            val msg = binding.etTestMessage.text?.toString()?.trim() ?: ""

            if (msg.isEmpty()) {
                Toast.makeText(this, "Pesan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val payload = "[WA] $sender: $msg"
            bleManager.sendData(payload)

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr = timeFormat.format(Date())
            addLogItem(NotifLogItem("WA", sender, msg, timeStr))

            Toast.makeText(this, "Pesan dikirim ke ESP32", Toast.LENGTH_SHORT).show()
        }

        // Tes Sinkronisasi Jam & Baterai HP [TIME]
        binding.btnTestSyncTime.setOnClickListener {
            val payload = DeviceSyncHelper.buildTimeBatteryPayload(this)
            bleManager.sendData(payload)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("TIME", "Sinkronisasi Jam HP", payload, timeFormat.format(Date())))
            Toast.makeText(this, "Terkirim: $payload", Toast.LENGTH_SHORT).show()
        }

        // Tes Sinkronisasi Cuaca Open-Meteo [WEATHER]
        binding.btnTestSyncWeather.setOnClickListener {
            Toast.makeText(this, "Mengambil data cuaca Open-Meteo...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val payload = WeatherManager.fetchWeatherPayload(this@MainActivity)
                if (payload != null) {
                    bleManager.sendData(payload)
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    addLogItem(NotifLogItem("WEATHER", "Cuaca Terkini", payload, timeFormat.format(Date())))
                    Toast.makeText(this@MainActivity, "Terkirim: $payload", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Gagal mengambil data cuaca", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Tes Navigasi Google Maps [NAV]
        binding.btnTestNav.setOnClickListener {
            val sampleNav = "[NAV] Jl. Sudirman|200 m|15 mnt|1"
            bleManager.sendData(sampleNav)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("NAV", "Google Maps (Tes)", sampleNav, timeFormat.format(Date())))
            Toast.makeText(this, "Terkirim: $sampleNav", Toast.LENGTH_SHORT).show()
        }

        // 3. Hapus Log di Menu Utama
        binding.btnClearLogs.setOnClickListener {
            logAdapter.clear()
            updateEmptyLogsView()
        }

        // 4. Kontrol di Tab Setting:
        // A. Tombol Pengaturan Akses Notifikasi
        binding.btnMenuNotifAccess.setOnClickListener {
            PermissionHelper.openNotificationAccessSettings(this)
        }

        // B. Tombol Buka Info Aplikasi
        binding.btnMenuAppInfo.setOnClickListener {
            PermissionHelper.openAppDetailsSettings(this)
            Toast.makeText(this, "Jika titik tiga tidak muncul, buka Pengaturan HP -> Aplikasi -> Lihat semua", Toast.LENGTH_LONG).show()
        }

        // C. Saklar Filter Aplikasi (ada di Menu Utama)
        binding.switchWA.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isWhatsAppEnabled = isChecked
        }
        binding.switchTelegram.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isTelegramEnabled = isChecked
        }
        binding.switchSMS.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isSmsEnabled = isChecked
        }
        binding.switchIG.setOnCheckedChangeListener { _, isChecked ->
            appFilterManager.isInstagramEnabled = isChecked
        }

        // D. Tombol Mulai / Hentikan Pindai Bluetooth ESP32
        binding.btnMenuRescanBle.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }

            if (bleManager.isDiscovering) {
                bleManager.stopDiscovery()
            } else {
                val filterEsp = binding.switchFilterEspOnly.isChecked
                bleManager.startDiscovery(filterEsp)
                Toast.makeText(
                    this,
                    if (filterEsp) "Memindai modul ESP32-C3 di sekitar..." else "Memindai semua perangkat Bluetooth...",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // E. Saklar Filter Khusus ESP32
        binding.switchFilterEspOnly.setOnCheckedChangeListener { _, isChecked ->
            if (bleManager.isDiscovering) {
                bleManager.startDiscovery(isChecked)
            }
        }

        // F. Tombol Putuskan Koneksi
        binding.btnDisconnectBle.setOnClickListener {
            bleManager.disconnect()
            Toast.makeText(this, "Koneksi Bluetooth diputus", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
        }
    }

    private fun onBleDeviceSelected(item: BleDeviceItem) {
        Toast.makeText(this, "Menghubungkan ke ${item.name}...", Toast.LENGTH_SHORT).show()
        bleDeviceAdapter.updateConnectionStatus(null, item.address)
        bleManager.connectToDevice(item.device)
        updateSettingBleDeviceCard()
    }

    private fun syncSettingSwitches() {
        binding.switchWA.isChecked = appFilterManager.isWhatsAppEnabled
        binding.switchTelegram.isChecked = appFilterManager.isTelegramEnabled
        binding.switchSMS.isChecked = appFilterManager.isSmsEnabled
        binding.switchIG.isChecked = appFilterManager.isInstagramEnabled
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        } else {
            bleManager.startScanAndConnect()
        }
    }

    private fun checkNotificationAccess() {
        val hasAccess = PermissionHelper.isNotificationAccessGranted(this)
        if (hasAccess) {
            binding.tvNotifStatusBadge.text = "Izin Aktif"
            binding.tvNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            binding.tvNotifStatusBadge.text = "Belum Aktif"
            binding.tvNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
        }
    }

    private fun addLogItem(item: NotifLogItem) {
        logAdapter.addItem(item)
        updateEmptyLogsView()
    }

    private fun updateEmptyLogsView() {
        val isEmpty = logAdapter.itemCount == 0
        binding.tvEmptyLogs.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvNotifLogs.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateSettingBleDeviceCard() {
        val isConnected = bleManager.currentState == BleManager.ConnectionState.CONNECTED
        val isConnecting = bleManager.currentState == BleManager.ConnectionState.CONNECTING
        val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: "ESP32-SmartNotif"
        val devAddress = bleManager.connectedDeviceAddress ?: bleManager.savedDeviceAddress

        when {
            isConnected -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "MAC: ${devAddress ?: "Tersambung"} • Terhubung"
                binding.btnDisconnectBle.visibility = View.VISIBLE
            }
            isConnecting -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "Menghubungkan ke ${devAddress ?: "perangkat"}..."
                binding.btnDisconnectBle.visibility = View.GONE
            }
            else -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected))
                binding.tvSettingDeviceName.text = if (devAddress != null) "Terputus dari $devName" else "Tidak Ada Perangkat Terhubung"
                binding.tvSettingDeviceAddress.text = if (devAddress != null) "MAC: $devAddress (Siap dihubungkan)" else "Tekan Mulai Pindai untuk mendeteksi ESP32-C3"
                binding.btnDisconnectBle.visibility = View.GONE
            }
        }

        bleDeviceAdapter.updateConnectionStatus(
            bleManager.connectedDeviceAddress,
            if (isConnecting) bleManager.lastConnectedDevice?.address else null
        )
    }

    // --- BLE State Listener Callbacks ---
    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: "ESP32-SmartNotif"
            when (state) {
                BleManager.ConnectionState.CONNECTED -> {
                    binding.tvBleStatus.text = getString(R.string.status_connected)
                    binding.tvBleDetail.text = "$devName Aktif - Siap Menerima Chat"
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                }
                BleManager.ConnectionState.CONNECTING -> {
                    binding.tvBleStatus.text = getString(R.string.status_connecting)
                    binding.tvBleDetail.text = "Menghubungkan ke $devName..."
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                }
                BleManager.ConnectionState.SCANNING -> {
                    binding.tvBleStatus.text = getString(R.string.status_scanning)
                    binding.tvBleDetail.text = "Mencari perangkat $devName di sekitar..."
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                }
                BleManager.ConnectionState.DISCONNECTED -> {
                    binding.tvBleStatus.text = getString(R.string.status_disconnected)
                    binding.tvBleDetail.text = "ESP32 tidak terdeteksi (Nyalakan saklar power ESP32)"
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected))
                }
            }
            updateSettingBleDeviceCard()
        }
    }

    override fun onDataReceived(data: String) {
        runOnUiThread {
            if (data == "ACK_OK") {
                Toast.makeText(this, "ESP32: Pesan ditampilkan di OLED!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDataSent(data: String, success: Boolean) {}

    // --- BleDiscoveryListener Callbacks ---
    override fun onDiscoveryStarted() {
        runOnUiThread {
            binding.btnMenuRescanBle.text = "Hentikan Pemindaian"
            binding.pbScanLoading.visibility = View.VISIBLE
            binding.tvScanStatusInfo.visibility = View.VISIBLE
            val filterEsp = binding.switchFilterEspOnly.isChecked
            binding.tvScanStatusInfo.text = if (filterEsp) {
                "Memindai modul ESP32-C3 di sekitar..."
            } else {
                "Memindai semua perangkat Bluetooth BLE di sekitar..."
            }
        }
    }

    override fun onDiscoveryFinished() {
        runOnUiThread {
            binding.btnMenuRescanBle.text = "Mulai Pindai Bluetooth"
            binding.pbScanLoading.visibility = View.GONE
            binding.tvScanStatusInfo.visibility = View.GONE
        }
    }

    override fun onDeviceFound(devices: List<BleDeviceItem>) {
        runOnUiThread {
            bleDeviceAdapter.setDevices(
                devices,
                bleManager.connectedDeviceAddress,
                if (bleManager.currentState == BleManager.ConnectionState.CONNECTING) bleManager.lastConnectedDevice?.address else null
            )
            val isEmpty = devices.isEmpty()
            binding.layoutEmptyBleDevices.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvBleDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
            if (isEmpty) {
                val filterEsp = binding.switchFilterEspOnly.isChecked
                binding.tvEmptyBleDevicesMsg.text = if (filterEsp) {
                    "Belum ada ESP32 terdeteksi.\nPastikan ESP32-C3 menyala lalu tekan Mulai Pindai."
                } else {
                    "Belum ada perangkat BLE terdeteksi.\nPastikan Bluetooth HP menyala."
                }
            }
        }
    }
}

