# ESP32-C3 SuperMini: Menu Sambung Android & iPhone + Aplikasi Android Otomatis

Proyek ini mencakup firmware **ESP32-C3 SuperMini** untuk penerima notifikasi Bluetooth Low Energy (BLE) dengan layar OLED 0.96", serta **Aplikasi Android khusus** (`android_app/`) yang otomatis mengompilasi menjadi file **APK** via GitHub Actions.

---

## 🔌 1. Skema Rangkaian Pinout (Sesuai Skematik)

### A. Tombol Push Button (B1 & B2)
| Tombol di Skematik | Pin ESP32-C3 | Pin Fisik Board | Kaki Lainnya | Fungsi Utama |
| :--- | :--- | :--- | :--- | :--- |
| **B1** (`SWITCH5 B1`) | **GPIO 4** | **Pin 13** | **GND (Pin 15)** | **Geser / Next Item / Pindah Pilihan** |
| **B2** (`SWITCH5 B2`) | **GPIO 3** | **Pin 12** | **GND (Pin 15)** | **Pilih (OK) / Kembali ke Menu / Tutup Pesan** |

> *Catatan: Pin 9 (GPIO0), Pin 10 (GPIO1), Pin 11 (GPIO2) pada skematik memiliki tanda **X** (tidak terhubung / No Connect).*

### B. Layar OLED 0.96" I2C (SSD1306 128x64)
| Pin Modul OLED | Pin ESP32-C3 SuperMini | Pin Fisik Board | Keterangan |
| :--- | :--- | :--- | :--- |
| **GND** | **GND** | **Pin 15** | Ground bersama |
| **VCC** | **3V3** | **Pin 14** | Tegangan 3.3V |
| **SCL** | **GPIO 9** | **Pin 5** | I2C SCL Clock |
| **SDA** | **GPIO 8** | **Pin 4** | I2C SDA Data |

### C. Modul Charger TP4056 & Switch SW1
- **TP4056 OUT+** $\rightarrow$ **SW1** $\rightarrow$ **Pin 16 (5V)** ESP32-C3
- **TP4056 OUT- / BAT-** $\rightarrow$ **Pin 15 (GND)** ESP32-C3

---

## 🎮 2. Cara Kerja Tombol pada Menu

### 📋 A. Di Menu Utama:
- **Tekan Tombol B1 (GPIO 4)**: Menggeser kursor ke menu berikutnya:
  1. `1. Hubungkan Android`
  2. `2. Hubungkan iPhone`
  3. `3. Riwayat Pesan`
- **Tekan Tombol B2 (GPIO 3)**: Membuka (*Enter/Pilih*) menu yang dipilih.

### 📱 B. Di Layar Hubungkan Android / iPhone:
- Menampilkan nama Bluetooth (`ESP32-SmartNotif`) dan status koneksi (`TERHUBUNG` / `MENUNGGU HP`).
- **Tekan Tombol B2 (GPIO 3)**: Kembali ke Menu Utama.

### 📩 C. Di Layar Riwayat Pesan:
- **Tekan Tombol B1 (GPIO 4)**: Memilih pesan berikutnya.
- **Tekan Tombol B2 (GPIO 3)**: Membuka detail pesan / Kembali ke daftar.

### 🔔 D. Saat Ada Notifikasi Masuk (Popup Card):
- Layar otomatis menampilkan kotak pesan masuk (Nama App, Pengirim, dan Isi Teks).
- **Tekan Tombol B2 (GPIO 3)**: Menutup notifikasi (*Dismiss*) dan kembali ke menu sebelumnya.
