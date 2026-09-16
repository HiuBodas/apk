/*
 * ===================================================================================
 * Proyek  : ESP32-C3 SuperMini - Smart Notification BLE OLED
 * Layar   : OLED SSD1306 I2C 0.96" (128x64)
 * MCU     : ESP32-C3 SuperMini
 * ===================================================================================
 * STRUKTUR FILE (2 FILE):
 * 1. esp32_ble_oled_buttons.ino : Antarmuka Layar OLED, Navigasi Tombol, & State
 * 2. ble_manager.h              : [TERKUNCI] Logika Bluetooth Low Energy (NUS)
 * ===================================================================================
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "ble_manager.h"

// --- PIN DEFINISI ESP32-C3 SUPERMINI ---
#define OLED_SDA_PIN    8   // GPIO 8
#define OLED_SCL_PIN    9   // GPIO 9
#define BTN1_PIN        4   // GPIO 4 (B1 - Geser / Next)
#define BTN2_PIN        3   // GPIO 3 (B2 - OK / Back / Tutup)

// --- KONFIGURASI LAYAR OLED ---
#define SCREEN_WIDTH    128
#define SCREEN_HEIGHT   64
#define OLED_RESET      -1

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// --- STRUKTUR DATA NOTIFIKASI ---
struct Notification {
  String app;
  String sender;
  String message;
};

#define MAX_NOTIF 5
Notification notifHistory[MAX_NOTIF];
int notifCount = 0;
int selectedNotif = 0;

// --- STATE NAVIGASI ---
enum ScreenState {
  STATE_STANDBY,
  STATE_POPUP,
  STATE_NOTIF_LIST,
  STATE_NOTIF_DETAIL,
  STATE_MENU,
  STATE_DEVICE_STATUS
};

ScreenState currentState = STATE_STANDBY;
ScreenState lastState = STATE_STANDBY;

// Menu Navigasi
const int MENU_TOTAL = 6;
const char* menuItems[MENU_TOTAL] = {
  "1. Sambung Android",
  "2. Sambung iOS",
  "3. Putus / Matikan BLE",
  "4. Riwayat Pesan",
  "5. Hapus Semua Pesan",
  "6. Status Perangkat"
};
int currentMenuIdx = 0;

// Debounce Tombol
unsigned long lastBtn1Time = 0;
unsigned long lastBtn2Time = 0;
const unsigned long DEBOUNCE_DELAY = 220;

// Timer Auto-dismiss Popup & Durasi Dinamis
unsigned long popupTimer = 0;
const unsigned long POPUP_TIMEOUT_DEFAULT = 10000;
unsigned long currentPopupTimeout = POPUP_TIMEOUT_DEFAULT;

// --- KONFIGURASI RUNNING TEXT (MARQUEE) NOTIFIKASI ---
int popupScrollX = SCREEN_WIDTH;             // Posisi X teks berjalan (mulai dari sisi kanan layar)
unsigned long lastPopupScrollTime = 0;       // Timer animasi pergeseran frame
const unsigned long POPUP_SCROLL_SPEED = 20; // Kecepatan gerak (ms per piksel)
const int POPUP_SCROLL_STEP = 1;             // Langkah piksel per geser (1px = sangat halus & bebas getar)

// Forward Declarations UI
void showStatusToast(const char* line1, const char* line2, int delayMs);
void drawUI();
void drawStandby();
void drawPopup();
void drawNotifList();
void drawNotifDetail();
void drawMenu();
void drawDeviceStatus();
void parseMessage(String raw);
String cleanText(String str);

// Tampilan pesan konfirmasi pop-up cepat (Toast - Latar Putih, Tulisan Hitam)
void showStatusToast(const char* line1, const char* line2, int delayMs) {
  display.fillScreen(SSD1306_WHITE);
  display.drawRect(0, 0, 128, 64, SSD1306_BLACK);
  display.drawRect(1, 1, 126, 62, SSD1306_BLACK);
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(12, 20);
  display.print(line1);
  if (line2 != NULL && strlen(line2) > 0) {
    display.setCursor(12, 35);
    display.print(line2);
  }
  display.display();
  delay(delayMs);
}

// Fungsi sanitasi teks untuk mencegah glitch font, karakter rusak, dan newline berantakan
String cleanText(String str) {
  String out = "";
  for (unsigned int i = 0; i < str.length(); i++) {
    char c = str[i];
    // Hanya ambil karakter ASCII yang bisa dicetak (spasi 32 hingga ~ 126)
    if (c >= 32 && c <= 126) {
      out += c;
    } else if (c == '\n' || c == '\r' || c == '\t') {
      out += ' ';
    }
  }
  out.trim();
  while (out.indexOf("  ") >= 0) {
    out.replace("  ", " ");
  }
  return out;
}

// Parser Pesan Notifikasi: Format [APP] Pengirim: Pesan
void parseMessage(String raw) {
  raw.trim();
  Notification n;

  if (raw.startsWith("[") && raw.indexOf("]") > 0) {
    int close = raw.indexOf("]");
    n.app = raw.substring(1, close);
    n.app.toUpperCase();
    String rest = raw.substring(close + 1);
    rest.trim();
    int colon = rest.indexOf(":");
    if (colon > 0) {
      n.sender = rest.substring(0, colon);
      n.sender.trim();
      n.message = rest.substring(colon + 1);
      n.message.trim();
    } else {
      n.sender = "Pemberitahuan";
      n.message = rest;
    }
  } else {
    int colon = raw.indexOf(":");
    if (colon > 0) {
      n.app = "NOTIF";
      n.sender = raw.substring(0, colon);
      n.sender.trim();
      n.message = raw.substring(colon + 1);
      n.message.trim();
    } else {
      n.app = "PESAN";
      n.sender = "HP";
      n.message = raw;
    }
  }

  // Bersihkan dari emoji dan byte non-ASCII agar font OLED tajam & bebas glitch
  n.app = cleanText(n.app);
  n.sender = cleanText(n.sender);
  n.message = cleanText(n.message);

  if (n.sender.length() == 0) n.sender = "Pemberitahuan";
  if (n.message.length() == 0) n.message = "(Pesan Kosong)";

  // Geser riwayat pesan lama ke bawah
  for (int i = MAX_NOTIF - 1; i > 0; i--) {
    notifHistory[i] = notifHistory[i - 1];
  }
  notifHistory[0] = n;
  if (notifCount < MAX_NOTIF) notifCount++;

  selectedNotif = 0;
  if (currentState != STATE_POPUP) {
    lastState = currentState;
  }
  currentState = STATE_POPUP;

  // Reset posisi teks berjalan ke sisi kanan layar agar meluncur ke kiri
  popupScrollX = SCREEN_WIDTH;
  lastPopupScrollTime = millis();

  // Hitung durasi timeout popup secara dinamis agar pesan sempat terbaca tuntas
  int textPixelWidth = n.message.length() * 12; // TextSize 2 = 12 piksel per karakter
  unsigned long scrollDuration = (unsigned long)(textPixelWidth + SCREEN_WIDTH) * POPUP_SCROLL_SPEED / POPUP_SCROLL_STEP;
  // Berikan durasi minimal 10 detik atau 1 putaran penuh + jeda 2.5 detik
  currentPopupTimeout = max(POPUP_TIMEOUT_DEFAULT, scrollDuration + 2500UL);
  popupTimer = millis();
}

// ===================================================================================
// DESAIN TAMPILAN OLED (MINIMALIS, BERSIH, MUDAH DIBACA)
// ===================================================================================

// Header Status Bar (Latar Putih, Tulisan & Garis Hitam)
void drawTopBar(const char* title, bool showBadge) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(title);

  display.setCursor(76, 0);
  if (!bleActive) {
    display.print(F("[OFF]"));
  } else if (deviceConnected) {
    display.print(F("[CON]"));
  } else {
    display.print(F("[ON]"));
  }

  if (showBadge && notifCount > 0) {
    display.setCursor(110, 0);
    display.print("(");
    display.print(notifCount);
    display.print(")");
  }
  display.drawLine(0, 9, 127, 9, SSD1306_BLACK);
}

// Footer Navigasi Sederhana (Latar Putih, Tulisan & Garis Hitam)
void drawBottomBar(const char* btn1Text, const char* btn2Text) {
  display.drawLine(0, 53, 127, 53, SSD1306_BLACK);
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(0, 56);
  display.print(btn1Text);
  if (btn2Text != NULL && strlen(btn2Text) > 0) {
    int xPos = 128 - (strlen(btn2Text) * 6);
    display.setCursor(xPos > 64 ? xPos : 68, 56);
    display.print(btn2Text);
  }
}

// 1. LAYAR STANDBY / UTAMA (Latar Putih, Tulisan Hitam)
void drawStandby() {
  drawTopBar("ESP32 NOTIF", true);

  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);

  if (notifCount == 0) {
    if (!bleActive) {
      display.setCursor(8, 18);
      display.print(F("Bluetooth: NONAKTIF"));
      display.setCursor(4, 30);
      display.print(F("Tekan B2 utk Menu"));
      display.setCursor(4, 41);
      display.print(F("Pilih Sambung HP"));
    } else if (deviceConnected) {
      display.setCursor(8, 20);
      display.print(F("HP Terhubung"));
      display.setCursor(0, 34);
      display.print(F("Siap terima notifikasi"));
    } else {
      display.setCursor(8, 18);
      display.print(F("Mencari HP..."));
      display.setCursor(0, 30);
      display.print(F("Nama: ESP32-SmartNotif"));
      display.setCursor(0, 41);
      if (currentTargetOS == OS_ANDROID) {
        display.print(F("Target: Android"));
      } else if (currentTargetOS == OS_IOS) {
        display.print(F("Target: iOS (iPhone)"));
      } else {
        display.print(F("Mode Standby"));
      }
    }
    drawBottomBar("B1:Riwayat", "B2:Menu");
  } else {
    // Tampilkan ringkasan pesan terbaru
    Notification n = notifHistory[0];
    display.setCursor(0, 14);
    display.print(F("["));
    display.print(n.app);
    display.print(F("] "));
    String sender = n.sender;
    if (sender.length() > 14) sender = sender.substring(0, 12) + "..";
    display.print(sender);

    display.setCursor(0, 26);
    String preview = n.message;
    if (preview.length() > 38) preview = preview.substring(0, 35) + "...";
    display.print(preview);

    drawBottomBar("B1:Riwayat", "B2:Menu");
  }
}

// 2. POPUP NOTIFIKASI BARU (High-Contrast: Background Putih, Tulisan Hitam, Font Pesan Besar)
void drawPopup() {
  if (notifCount == 0) {
    currentState = STATE_STANDBY;
    return;
  }

  Notification n = notifHistory[0];

  // 1. Background PUTIH PENUH (High-Contrast Alert)
  display.fillScreen(SSD1306_WHITE);

  // 2. Header: Hitam di atas Putih
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(2, 2);
  display.print(F("["));
  display.print(n.app);
  display.print(F("] "));

  String sender = n.sender;
  if (sender.length() > 14) sender = sender.substring(0, 12) + "..";
  display.print(sender);

  // Garis pembatas Header
  display.drawLine(0, 11, 127, 11, SSD1306_BLACK);

  // 3. Pesan Notifikasi: FONT BESAR (TextSize 2) - TEKS BERJALAN DARI KANAN KE KIRI
  display.setTextSize(2);
  display.setTextWrap(false); // Nonaktifkan wrap agar teks meluncur lurus ke samping kiri
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);

  // Y=23: Tepat di tengah vertikal antara garis header (Y=11) dan garis footer (Y=50)
  display.setCursor(popupScrollX, 23);
  display.print(n.message);

  display.setTextWrap(true); // Kembalikan ke wrap normal untuk layar lainnya

  // 4. Garis pembatas Footer
  display.drawLine(0, 50, 127, 50, SSD1306_BLACK);

  // 5. Footer Navigasi Bawah (Hitam di atas Putih)
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(2, 54);
  display.print(F("B1:Detail"));
  display.setCursor(76, 54);
  display.print(F("B2:Tutup"));
}

// 3. DAFTAR RIWAYAT PESAN (Latar Putih, Tulisan Hitam, Kursor Kontras)
void drawNotifList() {
  drawTopBar("RIWAYAT", false);

  if (notifCount == 0) {
    display.setTextSize(1);
    display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
    display.setCursor(18, 26);
    display.print(F("Belum ada pesan"));
    drawBottomBar("B1:Kembali", "B2:Menu");
    return;
  }

  for (int i = 0; i < notifCount && i < 3; i++) {
    int y = 13 + (i * 13);
    display.setTextSize(1);
    if (i == selectedNotif) {
      // Highlight baris yang dipilih dengan balok hitam teks putih
      display.fillRect(0, y - 1, 128, 11, SSD1306_BLACK);
      display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);
      display.setCursor(2, y);
      display.print(F("> "));
    } else {
      display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
      display.setCursor(2, y);
      display.print(F("  "));
    }

    String line = "[" + notifHistory[i].app + "] " + notifHistory[i].sender;
    if (line.length() > 18) line = line.substring(0, 16) + "..";
    display.print(line);
  }

  drawBottomBar("B1:Pilih", "B2:Buka");
}

// 4. DETAIL PESAN (Latar Putih, Tulisan & Garis Hitam)
void drawNotifDetail() {
  if (notifCount == 0) {
    currentState = STATE_STANDBY;
    return;
  }

  Notification n = notifHistory[selectedNotif];

  // Header
  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(F("["));
  display.print(n.app);
  display.print(F("] "));
  display.print(selectedNotif + 1);
  display.print(F("/"));
  display.print(notifCount);
  display.drawLine(0, 9, 127, 9, SSD1306_BLACK);

  // Pengirim
  display.setCursor(0, 13);
  String sender = "Dari: " + n.sender;
  if (sender.length() > 21) sender = sender.substring(0, 18) + "...";
  display.print(sender);

  // Pesan Utuh (hingga 3 baris terpotong rapi)
  display.setCursor(0, 24);
  String msg = n.message;
  if (msg.length() <= 21) {
    display.print(msg);
  } else if (msg.length() <= 42) {
    display.println(msg.substring(0, 21));
    display.setCursor(0, 34);
    display.print(msg.substring(21));
  } else {
    display.println(msg.substring(0, 21));
    display.setCursor(0, 34);
    display.println(msg.substring(21, 42));
    display.setCursor(0, 44);
    if (msg.length() > 63) {
      display.print(msg.substring(42, 60) + "..");
    } else {
      display.print(msg.substring(42));
    }
  }

  drawBottomBar("B1:Next", "B2:Kembali");
}

// 5. MENU PENGATURAN (Latar Putih, Tulisan Hitam, Kursor Kontras)
void drawMenu() {
  drawTopBar("MENU", true);

  // Tampilkan jendela 3 item berdasarkan posisi kursor saat ini
  int startIdx = 0;
  if (currentMenuIdx >= 2) {
    startIdx = currentMenuIdx - 2;
    if (startIdx + 3 > MENU_TOTAL) {
      startIdx = MENU_TOTAL - 3;
    }
  }

  for (int i = startIdx; i < startIdx + 3 && i < MENU_TOTAL; i++) {
    int lineRow = i - startIdx;
    int y = 14 + (lineRow * 12);
    display.setTextSize(1);
    if (i == currentMenuIdx) {
      // Highlight menu yang dipilih dengan balok hitam teks putih
      display.fillRect(0, y - 1, 128, 11, SSD1306_BLACK);
      display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);
      display.setCursor(2, y);
      display.print(F("> "));
    } else {
      display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
      display.setCursor(2, y);
      display.print(F("  "));
    }
    display.print(menuItems[i]);
  }

  drawBottomBar("B1:Geser", "B2:Pilih");
}

// 6. STATUS PERANGKAT (Latar Putih, Tulisan Hitam)
void drawDeviceStatus() {
  drawTopBar("STATUS", false);

  display.setTextSize(1);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);

  display.setCursor(0, 14);
  display.print(F("Perangkat: ESP32-C3"));

  display.setCursor(0, 26);
  display.print(F("Bluetooth: "));
  if (!bleActive) {
    display.print(F("OFF"));
  } else if (deviceConnected) {
    display.print(F("CONNECTED"));
  } else {
    display.print(currentTargetOS == OS_ANDROID ? "CARI (AND)" : "CARI (iOS)");
  }

  display.setCursor(0, 38);
  display.print(F("Pesan    : "));
  display.print(notifCount);
  display.print(F(" tersimpan"));

  drawBottomBar("B1:Kembali", "B2:Kembali");
}

// Render Layar Utama (Latar Putih Bersih di Seluruh Layar)
void drawUI() {
  display.fillScreen(SSD1306_WHITE);

  switch (currentState) {
    case STATE_STANDBY:
      drawStandby();
      break;
    case STATE_POPUP:
      drawPopup();
      break;
    case STATE_NOTIF_LIST:
      drawNotifList();
      break;
    case STATE_NOTIF_DETAIL:
      drawNotifDetail();
      break;
    case STATE_MENU:
      drawMenu();
      break;
    case STATE_DEVICE_STATUS:
      drawDeviceStatus();
      break;
  }

  display.display();
}

// --- SETUP UTAMA ---
void setup() {
  Serial.begin(115200);
  delay(100);

  // Inisialisasi Tombol dengan INPUT_PULLUP
  pinMode(BTN1_PIN, INPUT_PULLUP);
  pinMode(BTN2_PIN, INPUT_PULLUP);

  // Inisialisasi I2C OLED (SDA: GPIO 8, SCL: GPIO 9)
  Wire.begin(OLED_SDA_PIN, OLED_SCL_PIN);
  Wire.setClock(400000); // Mode Cepat 400kHz untuk transmisi display stabil & bebas flicker

  // Inisialisasi Layar OLED (cek alamat 0x3C dan 0x3D)
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3D)) {
      Serial.println(F("[ERROR] OLED tidak ditemukan!"));
    }
  }

  // Tampilan Splash Screen (Latar Putih, Tulisan Hitam)
  display.fillScreen(SSD1306_WHITE);
  display.setTextColor(SSD1306_BLACK, SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(20, 18);
  display.print(F("ESP32-C3 NOTIF"));
  display.drawLine(20, 30, 108, 30, SSD1306_BLACK);
  display.setCursor(24, 38);
  display.print(F("Sistem Siap..."));
  display.display();
  delay(1000);

  // CATATAN: Bluetooth TIDAK dinyalakan saat boot!
  // Pengguna menyalakan Bluetooth lewat Menu -> Sambung Android / iOS.
  bleActive = false;
  deviceConnected = false;
  Serial.println(F("[SISTEM] Boot selesai. Bluetooth OFF (Pilih Sambung di Menu)."));
}

// --- LOOP UTAMA ---
void loop() {
  // Auto-dismiss popup notifikasi setelah batas waktu dinamis tercapai
  if (currentState == STATE_POPUP && (millis() - popupTimer > currentPopupTimeout)) {
    currentState = (lastState == STATE_POPUP) ? STATE_STANDBY : lastState;
  }

  // --- ANIMASI RUNNING TEXT (MARQUEE) POPUP DARI KANAN KE KIRI ---
  if (currentState == STATE_POPUP && notifCount > 0) {
    unsigned long now = millis();
    if (now - lastPopupScrollTime >= POPUP_SCROLL_SPEED) {
      lastPopupScrollTime = now;
      popupScrollX -= POPUP_SCROLL_STEP;

      int textPixelWidth = notifHistory[0].message.length() * 12; // TextSize 2 = 12 piksel per karakter
      // Jika seluruh kalimat sudah selesai melintas ke sisi kiri layar, ulang dari sisi kanan
      if (popupScrollX < -textPixelWidth) {
        popupScrollX = SCREEN_WIDTH;
      }
    }
  }

  // --- PEMBACAAN TOMBOL 1 (B1 - GPIO 4: Next / Geser / Scroll) ---
  if (digitalRead(BTN1_PIN) == LOW) {
    if (millis() - lastBtn1Time > DEBOUNCE_DELAY) {
      lastBtn1Time = millis();

      if (currentState == STATE_STANDBY) {
        if (notifCount > 0) {
          currentState = STATE_NOTIF_LIST;
          selectedNotif = 0;
        } else {
          currentState = STATE_MENU;
          currentMenuIdx = 0;
        }
      }
      else if (currentState == STATE_POPUP) {
        if (notifCount > 0) {
          currentState = STATE_NOTIF_DETAIL;
          selectedNotif = 0;
        }
      }
      else if (currentState == STATE_NOTIF_LIST) {
        if (notifCount > 0) {
          selectedNotif = (selectedNotif + 1) % notifCount;
        }
      }
      else if (currentState == STATE_NOTIF_DETAIL) {
        if (notifCount > 1) {
          selectedNotif = (selectedNotif + 1) % notifCount;
        }
      }
      else if (currentState == STATE_MENU) {
        currentMenuIdx = (currentMenuIdx + 1) % MENU_TOTAL;
      }
    }
  }

  // --- PEMBACAAN TOMBOL 2 (B2 - GPIO 3: OK / Kembali / Pilih / Tutup) ---
  if (digitalRead(BTN2_PIN) == LOW) {
    if (millis() - lastBtn2Time > DEBOUNCE_DELAY) {
      lastBtn2Time = millis();

      if (currentState == STATE_STANDBY) {
        currentState = STATE_MENU;
        currentMenuIdx = 0;
      }
      else if (currentState == STATE_POPUP) {
        currentState = STATE_STANDBY;
      }
      else if (currentState == STATE_NOTIF_LIST) {
        if (notifCount > 0) {
          currentState = STATE_NOTIF_DETAIL;
        } else {
          currentState = STATE_STANDBY;
        }
      }
      else if (currentState == STATE_NOTIF_DETAIL) {
        currentState = STATE_NOTIF_LIST;
      }
      else if (currentState == STATE_MENU) {
        // 1. Sambung Android
        if (currentMenuIdx == 0) {
          startBLE(OS_ANDROID);
          showStatusToast("Bluetooth AKTIF", "Mode: Android", 1000);
          currentState = STATE_STANDBY;
        }
        // 2. Sambung iOS (iPhone)
        else if (currentMenuIdx == 1) {
          startBLE(OS_IOS);
          showStatusToast("Bluetooth AKTIF", "Mode: iOS", 1000);
          currentState = STATE_STANDBY;
        }
        // 3. Putus / Matikan Bluetooth
        else if (currentMenuIdx == 2) {
          stopBLE();
          showStatusToast("Bluetooth MATI", "Koneksi Diputus", 1000);
          currentState = STATE_STANDBY;
        }
        // 4. Riwayat Pesan
        else if (currentMenuIdx == 3) {
          currentState = STATE_NOTIF_LIST;
          selectedNotif = 0;
        }
        // 5. Hapus Semua Pesan
        else if (currentMenuIdx == 4) {
          notifCount = 0;
          selectedNotif = 0;
          showStatusToast("Riwayat Pesan", "Telah Dihapus", 800);
          currentState = STATE_STANDBY;
        }
        // 6. Status Perangkat
        else if (currentMenuIdx == 5) {
          currentState = STATE_DEVICE_STATUS;
        }
      }
      else if (currentState == STATE_DEVICE_STATUS) {
        currentState = STATE_STANDBY;
      }
    }
  }

  // Render Layar
  drawUI();
  delay(10);
}
