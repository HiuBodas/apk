package com.esp32.smartnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
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

    companion object {
        private const val TAB_MENU = 0
        private const val TAB_SETTING = 1
        private const val SWIPE_THRESHOLD_DP = 48
        private const val SWIPE_VELOCITY_THRESHOLD_DP = 140
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var appFilterManager: AppFilterManager
    private val logAdapter = LogAdapter()
    private lateinit var bleDeviceAdapter: BleDeviceAdapter

    private var currentTab = TAB_MENU
    private var startTouchX = 0f
    private var startTouchY = 0f

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
        updateTestPreviews()

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
        binding.rvNotifLogs.layoutManager = LinearLayoutManager(this)
        binding.rvNotifLogs.adapter = logAdapter
        updateEmptyLogsView()

        bleDeviceAdapter = BleDeviceAdapter { selectedDeviceItem ->
            onBleDeviceSelected(selectedDeviceItem)
        }
        binding.rvBleDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBleDevices.adapter = bleDeviceAdapter

        // Sinkronisasi awal saklar setting & kartu BLE
        syncSettingSwitches()
        updateSettingBleDeviceCard()
        updateTestPreviews()
    }

    private fun setupListeners() {
        // Navigasi tab menu dan setting
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentTab == TAB_MENU) {
                        binding.layoutMenu.smoothScrollTo(0, 0)
                    } else {
                        switchToMenu(animated = true)
                    }
                    true
                }
                R.id.nav_settings -> {
                    if (currentTab == TAB_SETTING) {
                        binding.layoutSetting.smoothScrollTo(0, 0)
                    } else {
                        switchToSetting(animated = true)
                    }
                    true
                }
                else -> false
            }
        }

        // Panel pengujian fitur
        binding.btnSendTest.setOnClickListener {
            val inputSender = binding.etTestSender.text?.toString()?.trim()
            val sender = if (!inputSender.isNullOrEmpty()) inputSender else getString(R.string.sample_sender)
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

        // Sinkronisasi jam dan baterai
        binding.btnTestSyncTime.setOnClickListener {
            val payload = DeviceSyncHelper.buildTimeBatteryPayload(this)
            binding.tvTimeBatteryPreview.text = payload
            bleManager.sendData(payload)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("TIME", "Sinkronisasi Jam HP", payload, timeFormat.format(Date())))
            Toast.makeText(this, "Terkirim: $payload", Toast.LENGTH_SHORT).show()
        }

        // Prakiraan cuaca live GPS
        binding.btnTestSyncWeather.setOnClickListener {
            Toast.makeText(this, "Mengambil data cuaca Open-Meteo...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val payload = WeatherManager.fetchWeatherPayload(this@MainActivity)
                if (payload != null) {
                    binding.tvWeatherPreview.text = payload
                    bleManager.sendData(payload)
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    addLogItem(NotifLogItem("WEATHER", "Cuaca Terkini", payload, timeFormat.format(Date())))
                    Toast.makeText(this@MainActivity, "Terkirim: $payload", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Gagal mengambil data cuaca", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Prakiraan cuaca sampel offline
        binding.btnTestSampleWeather.setOnClickListener {
            val sampleWeather = "[WEATHER] Jakarta|30|1|33|24|6|1012"
            binding.tvWeatherPreview.text = sampleWeather
            bleManager.sendData(sampleWeather)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("WEATHER", "Cuaca Sample (Offline)", sampleWeather, timeFormat.format(Date())))
            Toast.makeText(this, "Terkirim Sample Cuaca: $sampleWeather", Toast.LENGTH_SHORT).show()
        }

        // Uji navigasi Google Maps
        binding.etNavInstruction.doAfterTextChanged { updateNavPreview() }
        binding.etNavDistance.doAfterTextChanged { updateNavPreview() }
        binding.etNavEta.doAfterTextChanged { updateNavPreview() }

        binding.btnTestNav.setOnClickListener {
            val navPayload = updateNavPreview()
            bleManager.sendData(navPayload)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("NAV", "Google Maps (Tes)", navPayload, timeFormat.format(Date())))
            Toast.makeText(this, "Terkirim: $navPayload", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestNavStop.setOnClickListener {
            val stopPayload = "[NAV] STOP"
            binding.tvNavPreview.text = stopPayload
            bleManager.sendData(stopPayload)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            addLogItem(NotifLogItem("NAV", "Google Maps (Stop)", stopPayload, timeFormat.format(Date())))
            Toast.makeText(this, "Navigasi Dihentikan: $stopPayload", Toast.LENGTH_SHORT).show()
        }

        // Hapus log notifikasi
        binding.btnClearLogs.setOnClickListener {
            logAdapter.clear()
            updateEmptyLogsView()
        }

        // Akses setelan notifikasi sistem
        binding.btnMenuNotifAccess.setOnClickListener {
            PermissionHelper.openNotificationAccessSettings(this)
        }

        // Setelan info aplikasi
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

        // F. Tombol Putuskan / Batalkan Koneksi Bluetooth
        binding.btnDisconnectBle.setOnClickListener {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
            bleManager.disconnect()
            Toast.makeText(this, "Koneksi Bluetooth ke $devName telah diputus", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
        }

        // G. Tombol Hubungkan Kembali ke Perangkat Tersimpan
        binding.btnReconnectSavedBle.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }
            Toast.makeText(this, "Menghubungkan kembali ke ESP32...", Toast.LENGTH_SHORT).show()
            bleManager.startScanAndConnect()
            updateSettingBleDeviceCard()
        }

        // H. Tombol Lupakan Perangkat Tersimpan
        binding.btnForgetSavedBle.setOnClickListener {
            bleManager.savedDeviceAddress = null
            bleManager.savedDeviceName = null
            Toast.makeText(this, "Perangkat tersimpan telah dilupakan", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
        }
    }

        // Transisi slide antar tab menu dan setting

    private fun switchToMenu(animated: Boolean = true) {
        if (currentTab == TAB_MENU && binding.layoutMenu.visibility == View.VISIBLE) return
        currentTab = TAB_MENU
        if (binding.bottomNav.selectedItemId != R.id.nav_home) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        if (!animated) {
            binding.layoutSetting.animate().cancel()
            binding.layoutMenu.animate().cancel()
            binding.layoutSetting.visibility = View.GONE
            binding.layoutMenu.visibility = View.VISIBLE
            binding.layoutMenu.translationX = 0f
            binding.layoutMenu.alpha = 1f
            return
        }

        val containerWidth = binding.contentContainer.width.toFloat().let {
            if (it <= 0f) resources.displayMetrics.widthPixels.toFloat() else it
        }

        binding.layoutSetting.animate().cancel()
        binding.layoutMenu.animate().cancel()

        // Tab Menu muncul dari kiri (slide ke kanan)
        binding.layoutMenu.visibility = View.VISIBLE
        binding.layoutMenu.translationX = -containerWidth
        binding.layoutMenu.alpha = 0.5f
        binding.layoutMenu.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        // Tab Setting keluar ke arah kanan
        binding.layoutSetting.animate()
            .translationX(containerWidth * 0.35f)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                binding.layoutSetting.visibility = View.GONE
                binding.layoutSetting.translationX = 0f
                binding.layoutSetting.alpha = 1f
            }
            .start()
    }

    private fun switchToSetting(animated: Boolean = true) {
        if (currentTab == TAB_SETTING && binding.layoutSetting.visibility == View.VISIBLE) return
        currentTab = TAB_SETTING
        if (binding.bottomNav.selectedItemId != R.id.nav_settings) {
            binding.bottomNav.selectedItemId = R.id.nav_settings
        }

        if (!animated) {
            binding.layoutMenu.animate().cancel()
            binding.layoutSetting.animate().cancel()
            binding.layoutMenu.visibility = View.GONE
            binding.layoutSetting.visibility = View.VISIBLE
            binding.layoutSetting.translationX = 0f
            binding.layoutSetting.alpha = 1f
            syncSettingSwitches()
            updateSettingBleDeviceCard()
            return
        }

        val containerWidth = binding.contentContainer.width.toFloat().let {
            if (it <= 0f) resources.displayMetrics.widthPixels.toFloat() else it
        }

        binding.layoutMenu.animate().cancel()
        binding.layoutSetting.animate().cancel()

        // Tab Setting muncul dari kanan (slide ke kiri)
        binding.layoutSetting.visibility = View.VISIBLE
        binding.layoutSetting.translationX = containerWidth
        binding.layoutSetting.alpha = 0.5f
        binding.layoutSetting.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        // Tab Menu keluar ke arah kiri
        binding.layoutMenu.animate()
            .translationX(-containerWidth * 0.35f)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                binding.layoutMenu.visibility = View.GONE
                binding.layoutMenu.translationX = 0f
                binding.layoutMenu.alpha = 1f
            }
            .start()

        syncSettingSwitches()
        updateSettingBleDeviceCard()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val dragThreshold = SWIPE_THRESHOLD_DP * density

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.rawX
                startTouchY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = ev.rawX - startTouchX
                val deltaY = ev.rawY - startTouchY

                val isInsideActiveInput = isTouchInsideActiveInput(ev.rawX, ev.rawY)

                if (!isInsideActiveInput &&
                    Math.abs(deltaX) > Math.abs(deltaY) * 1.35f &&
                    Math.abs(deltaX) > dragThreshold
                ) {
                    if (deltaX < 0 && currentTab == TAB_MENU) {
                        switchToSetting(animated = true)
                    } else if (deltaX > 0 && currentTab == TAB_SETTING) {
                        switchToMenu(animated = true)
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInsideActiveInput(rawX: Float, rawY: Float): Boolean {
        val focused = currentFocus ?: return false
        if (focused is EditText) {
            val loc = IntArray(2)
            focused.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX <= loc[0] + focused.width &&
                   rawY >= loc[1] && rawY <= loc[1] + focused.height
        }
        return false
    }

    private fun onBleDeviceSelected(item: BleDeviceItem) {
        if (item.address.equals(bleManager.connectedDeviceAddress, ignoreCase = true) &&
            bleManager.currentState == BleManager.ConnectionState.CONNECTED) {
            val devName = item.name.ifBlank { "ESP32" }
            bleManager.disconnect()
            Toast.makeText(this, "Koneksi Bluetooth ke $devName telah diputus", Toast.LENGTH_SHORT).show()
            updateSettingBleDeviceCard()
            return
        }

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

    private fun updateTestPreviews() {
        // 1. Preview Waktu & Baterai
        val timePayload = DeviceSyncHelper.buildTimeBatteryPayload(this)
        binding.tvTimeBatteryPreview.text = timePayload

        // 2. Preview Cuaca (dari cache atau default)
        val savedWeather = WeatherManager.getLastSavedPayload(this)
        binding.tvWeatherPreview.text = savedWeather ?: "[WEATHER] Jakarta|30|1|33|24|6|1012"

        // 3. Preview Navigasi
        updateNavPreview()
    }

    private fun updateNavPreview(): String {
        val instruction = binding.etNavInstruction.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "Belok Kanan Jl. Sudirman" else it.replace("|", "-")
        }
        val distance = binding.etNavDistance.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "200 m" else it.replace("|", "-")
        }
        val eta = binding.etNavEta.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "15 mnt" else it.replace("|", "-")
        }
        val payload = "[NAV] $instruction|$distance|$eta|1"
        binding.tvNavPreview.text = payload
        return payload
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
            binding.tvNotifStatusBadge.text = getString(R.string.status_notif_active)
            binding.tvNotifStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            binding.tvNotifStatusBadge.text = getString(R.string.status_notif_inactive)
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
        val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
        val devAddress = bleManager.connectedDeviceAddress ?: bleManager.savedDeviceAddress

        when {
            isConnected -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "MAC: ${devAddress ?: "Tersambung"} • Terhubung"
                binding.tvSettingBleBadge.text = getString(R.string.badge_connected)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

                binding.btnDisconnectBle.visibility = View.VISIBLE
                binding.btnDisconnectBle.text = getString(R.string.btn_disconnect_ble)
                binding.btnDisconnectBle.setIconResource(R.drawable.ic_bluetooth_disabled)

                binding.layoutSavedDeviceActions.visibility = View.GONE
            }
            isConnecting -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                binding.tvSettingDeviceName.text = devName
                binding.tvSettingDeviceAddress.text = "Menghubungkan ke ${devAddress ?: "perangkat"}..."
                binding.tvSettingBleBadge.text = getString(R.string.badge_connecting)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))

                binding.btnDisconnectBle.visibility = View.VISIBLE
                binding.btnDisconnectBle.text = getString(R.string.btn_cancel_connecting)
                binding.btnDisconnectBle.setIconResource(R.drawable.ic_stop)

                binding.layoutSavedDeviceActions.visibility = View.GONE
            }
            else -> {
                binding.viewSettingBleDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected))
                binding.tvSettingDeviceName.text = if (devAddress != null) "Terputus dari $devName" else getString(R.string.status_no_device)
                binding.tvSettingDeviceAddress.text = if (devAddress != null) "MAC: $devAddress (Siap dihubungkan)" else getString(R.string.desc_start_scan_prompt)
                binding.tvSettingBleBadge.text = getString(R.string.badge_disconnected)
                binding.tvSettingBleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))

                binding.btnDisconnectBle.visibility = View.GONE

                if (devAddress != null) {
                    binding.layoutSavedDeviceActions.visibility = View.VISIBLE
                } else {
                    binding.layoutSavedDeviceActions.visibility = View.GONE
                }
            }
        }

        bleDeviceAdapter.updateConnectionStatus(
            bleManager.connectedDeviceAddress,
            if (isConnecting) bleManager.lastConnectedDevice?.address else null
        )
    }

    // Callback status koneksi Bluetooth
    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            val devName = bleManager.connectedDeviceName ?: bleManager.savedDeviceName ?: getString(R.string.device_target_name)
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
                    binding.tvBleDetail.text = if (message.isNotEmpty()) message else "Koneksi Bluetooth terputus"
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

    // Callback pemindaian perangkat Bluetooth
    override fun onDiscoveryStarted() {
        runOnUiThread {
            binding.btnMenuRescanBle.text = getString(R.string.btn_stop_scan)
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
            binding.btnMenuRescanBle.text = getString(R.string.btn_start_scan)
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

