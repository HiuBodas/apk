# 📱 Aplikasi Android: ESP32 Smart Notif

Aplikasi Android Native (Kotlin) yang dirancang khusus untuk menghubungkan smartphone Android langsung ke perangkat **ESP32-C3 SuperMini** via **Bluetooth Low Energy (BLE)** dan meneruskan notifikasi HP (WhatsApp, Telegram, SMS, Instagram, dll.) secara otomatis dan real-time ke layar OLED.

---

## 🌟 Fitur Utama

1. **Izin Otomatis & Terpandu di Awal**:
   - Meminta izin Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), Lokasi, dan Notifikasi Foreground secara otomatis saat aplikasi pertama kali dibuka.
   - Menyediakan tombol cepat untuk mengaktifkan **Akses Notifikasi (Notification Access)** di pengaturan Android.
2. **Koneksi BLE Otomatis**:
   - Otomatis memindai dan terhubung ke perangkat bernama `ESP32-SmartNotif`.
   - Fitur *auto-reconnect* jika koneksi sempat terputus.
3. **Pengiriman Notifikasi Real-Time (Otomatis)**:
   - Berjalan di latar belakang (*Foreground & Notification Listener Service*) sehingga tetap aktif meskipun aplikasi diminimalkan atau layar HP dalam keadaan terkunci.
   - Format pesan terkirim: `[WA] Pengirim: Isi Pesan`.
4. **Filter Aplikasi**:
   - Saklar ON/OFF untuk memilih aplikasi mana saja yang notifikasinya ingin diteruskan (WhatsApp, Telegram, SMS/Panggilan, Instagram).
5. **Dashboard & Log Real-Time**:
   - Kartu status Bluetooth dengan indikator warna (*Connected, Connecting, Disconnected*).
   - Log riwayat pesan yang baru saja dikirim ke OLED.
   - Fitur kirim pesan tes manual ke OLED ESP32.

---

## ☁️ Cara Build APK Otomatis di GitHub (Tanpa Perlu Pasang Android Studio!)

Repository ini sudah dilengkapi dengan **GitHub Actions Workflow** (`.github/workflows/build_apk.yml`). Setiap kali kode di-push ke GitHub:
1. **GitHub akan otomatis meng-compile aplikasi** di cloud.
2. Anda cukup **mengunduh file APK langsung dari tab Actions** di GitHub:
   - Buka halaman repository Anda di GitHub.
   - Klik tab **Actions** di menu atas.
   - Klik alur kerja terbaru: **"Compile Android App to APK"** (centang hijau).
   - Scroll ke bagian bawah pada **Artifacts**, klik **`ESP32-SmartNotif-App-Debug`**.
   - Ekstrak file zip yang terunduh, di dalamnya langsung terdapat file `.apk` siap pasang di HP!

---

## 💻 (Opsional) Jika Ingin Mengedit / Membuka di Android Studio


---

## ⚙️ Langkah Pertama Setelah Install di HP

1. **Buka Aplikasi "ESP32 Smart Notif"**.
2. **Izinkan Permintaan Bluetooth & Lokasi** saat dialog muncul.
3. **Aktifkan Akses Notifikasi**:
   - Tekan tombol **"Buka Pengaturan"** pada kotak kuning di bagian atas.
   - Cari **ESP32 Smart Notif** lalu aktifkan saklarnya ke posisi **ON**.
4. **Nyalakan ESP32-C3**:
   - Pastikan switch ESP32 menyala.
   - Aplikasi di HP akan otomatis mendeteksi dan menampilkan status **TERHUBUNG (Hijau)**.
5. **Uji Coba**:
   - Kirim pesan tes melalui menu "Kirim Tes Notifikasi" atau minta seseorang mengirim pesan WhatsApp ke HP Anda!

---

## 📡 Protokol Bluetooth BLE yang Digunakan

- **Device Name**: `ESP32-SmartNotif`
- **Nordic UART Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX Characteristic UUID (Write to ESP32)**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- **TX Characteristic UUID (ESP32 to Phone)**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- **Format Payload**: `[KODE_APP] Nama Pengirim: Isi Pesan Teks`
