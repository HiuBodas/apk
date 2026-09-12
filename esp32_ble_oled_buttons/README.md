# ESP32-C3 SuperMini Smart Notification BLE & OLED SSD1306

Firmware ESP32-C3 SuperMini untuk menerima notifikasi smartphone (Android & iPhone / iOS) via Bluetooth Low Energy (BLE) dan menampilkannya pada layar OLED SSD1306 0.96" (128x64).

---

## Fitur Utama

- **Bluetooth Low Energy (BLE) On-Demand:** Bluetooth tidak langsung menyala saat boot untuk menghemat daya. Diaktifkan melalui menu `Sambung Android` atau `Sambung iOS`.
- **Dukungan Negosiasi MTU 512 Bytes:** Menerima pesan teks panjang (seperti chat WhatsApp) secara penuh tanpa terpotong.
- **Tampilan Bersih & Minimalis (Clean UI):**
  - **Layar Standby:** Status koneksi `[ON]` / `[OFF]`, badge pesan `(X)`, dan ringkasan notifikasi terbaru.
  - **Popup Notifikasi Masuk:** Tampilan rapi dengan badge nama aplikasi `[WA]`, nama pengirim, dan isi pesan tanpa garis-garis yang mengganggu keterbacaan.
  - **Riwayat Pesan:** Menyimpan hingga 5 pesan terakhir dengan kursor navigasi `>`.
  - **Detail Pesan:** Tampilan lega membaca pesan panjang secara utuh.
- **Menu Navigasi 2 Tombol:**
  - `1. Sambung Android`
  - `2. Sambung iOS (iPhone)`
  - `3. Matikan Bluetooth`
  - `4. Riwayat Pesan`
  - `5. Hapus Semua Pesan`
  - `6. Status Perangkat`

---

## Skematik Pinout & Wiring

| Komponen | Pin Modul | Pin ESP32-C3 SuperMini | Keterangan |
|---|---|---|---|
| **OLED SSD1306** | GND | GND (Pin 15) | Ground |
| **OLED SSD1306** | VCC | 3V3 (Pin 14) | Power 3.3V |
| **OLED SSD1306** | SDA | GPIO 8 (Pin 4) | I2C Data |
| **OLED SSD1306** | SCL | GPIO 9 (Pin 5) | I2C Clock |
| **Tombol B1** | Pin 1 & 2 | GPIO 4 (Pin 13) & GND | Tombol Geser / Next / Riwayat |
| **Tombol B2** | Pin 1 & 2 | GPIO 3 (Pin 12) & GND | Tombol Pilih / OK / Menu / Tutup |

---

## Library yang Dibutuhkan (Arduino IDE)

Instal library berikut melalui **Library Manager** di Arduino IDE:
1. `Adafruit SSD1306` by Adafruit
2. `Adafruit GFX Library` by Adafruit
3. `BLE Built-in Library` (Otomatis tersedia saat menginstal Board Package ESP32 by Espressif)

---

## Pengaturan Board di Arduino IDE

- **Board:** `ESP32C3 Dev Module` (atau sesuai varian ESP32-C3 SuperMini Anda)
- **Upload Speed:** `921600` atau `115200`
- **USB CDC On Boot:** `Enabled`
- **Flash Size:** `4MB`
- **Port:** Pilih COM Port ESP32-C3 yang terdeteksi

---

## Panduan Penggunaan

1. **Nyalakan ESP32-C3:** Layar akan masuk ke mode standby dengan status `[OFF]`.
2. **Nyalakan Bluetooth:**
   - Tekan tombol **B2** untuk membuka Menu.
   - Tekan **B1** untuk memilih `1. Sambung Android` (atau `2. Sambung iOS`).
   - Tekan **B2** untuk konfirmasi. Bluetooth akan aktif (`[ON]`).
3. **Buka Aplikasi HP:**
   - Sambungkan aplikasi Android ke perangkat bernama **`ESP32-SmartNotif`**.
4. **Menerima Notifikasi:**
   - Notifikasi masuk akan otomatis memicu popup di layar OLED.
   - Tekan **B1** untuk melihat detail atau **B2** untuk menutup popup.
