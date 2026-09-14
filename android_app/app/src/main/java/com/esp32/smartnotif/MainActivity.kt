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
import androidx.recyclerview.widget.LinearLayoutManager
import com.esp32.smartnotif.ble.BleManager
import com.esp32.smartnotif.databinding.ActivityMainBinding
import com.esp32.smartnotif.model.NotifLogItem
import com.esp32.smartnotif.service.BleForegroundService
import com.esp32.smartnotif.service.NotificationReceiverService
import com.esp32.smartnotif.ui.LogAdapter
import com.esp32.smartnotif.utils.AppFilterManager
import com.esp32.smartnotif.utils.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BleManager.BleStateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var appFilterManager: AppFilterManager
    private val logAdapter = LogAdapter()

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

        // Mulai Foreground Service agar koneksi background tetap hidup
        BleForegroundService.startService(this)
    }

    override fun onResume() {
        super.onResume()
        bleManager.addListener(this)
        checkNotificationAccess()
        syncSettingSwitches()

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
        try {
            unregisterReceiver(logBroadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        // Setup RecyclerView Log di Menu Utama
        binding.rvNotifLogs.layoutManager = LinearLayoutManager(this)
        binding.rvNotifLogs.adapter = logAdapter
        updateEmptyLogsView()

        // Sinkronisasi awal saklar setting
        syncSettingSwitches()
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

        // D. Tombol Pindai Ulang Bluetooth ESP32
        binding.btnMenuRescanBle.setOnClickListener {
            if (PermissionHelper.hasAllRuntimePermissions(this)) {
                bleManager.disconnect()
                bleManager.startScanAndConnect()
                Toast.makeText(this, "Memindai ulang ESP32...", Toast.LENGTH_SHORT).show()
            } else {
                requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            }
        }
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

    // --- BLE State Listener Callbacks ---
    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            when (state) {
                BleManager.ConnectionState.CONNECTED -> {
                    binding.tvBleStatus.text = getString(R.string.status_connected)
                    binding.tvBleDetail.text = "ESP32-SmartNotif Aktif - Siap Menerima Chat"
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                }
                BleManager.ConnectionState.CONNECTING -> {
                    binding.tvBleStatus.text = getString(R.string.status_connecting)
                    binding.tvBleDetail.text = "Menghubungkan ke ESP32-SmartNotif..."
                    binding.viewStatusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting))
                }
                BleManager.ConnectionState.SCANNING -> {
                    binding.tvBleStatus.text = getString(R.string.status_scanning)
                    binding.tvBleDetail.text = "Mencari perangkat ESP32-SmartNotif di sekitar..."
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
}
