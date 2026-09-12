/*
 * ===================================================================================
 * Proyek  : ESP32-C3 SuperMini - Smart Notification BLE OLED
 * Layar   : OLED SSD1306 I2C 0.96" (128x64)
 * MCU     : ESP32-C3 SuperMini
 * ===================================================================================
 * 
 * PINOUT SKEMATIK ESP32-C3:
 * - OLED SDA -> GPIO 8
 * - OLED SCL -> GPIO 9
 * - Tombol B1 (Next / Geser)  -> GPIO 4 & GND
 * - Tombol B2 (OK / Kembali)  -> GPIO 3 & GND
 * ===================================================================================
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

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

// --- KONFIGURASI BLE (NORDIC UART SERVICE) ---
#define BLE_DEVICE_NAME        "ESP32-SmartNotif"
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer* pServer = NULL;
BLECharacteristic* pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

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

const int MENU_TOTAL = 3;
const char* menuItems[MENU_TOTAL] = {
  "1. Riwayat Pesan",
  "2. Status Koneksi",
  "3. Hapus Semua Pesan"
};
int currentMenuIdx = 0;

// Debounce Tombol
unsigned long lastBtn1Time = 0;
unsigned long lastBtn2Time = 0;
const unsigned long DEBOUNCE_DELAY = 220;

// Timer Auto-dismiss Popup (10 detik)
unsigned long popupTimer = 0;
const unsigned long POPUP_TIMEOUT = 10000;

// Forward Declarations
void drawUI();
void drawStandby();
void drawPopup();
void drawNotifList();
void drawNotifDetail();
void drawMenu();
void drawDeviceStatus();
void parseMessage(String raw);
void sendBLE(String msg);

// --- CALLBACK BLE SERVER ---
class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println(F("[BLE] HP Terhubung!"));
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println(F("[BLE] HP Terputus!"));
  }
};

// --- CALLBACK BLE RECEIVE (Karakteristik RX) ---
class ReceiveCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    size_t len = pCharacteristic->getLength();
    uint8_t* data = pCharacteristic->getData();
    if (len > 0 && data != nullptr) {
      String rxValue = "";
      for (size_t i = 0; i < len; i++) {
        rxValue += (char)data[i];
      }
      rxValue.trim();
      Serial.print(F("[BLE RX] "));
      Serial.println(rxValue);

      if (rxValue.length() > 0) {
        parseMessage(rxValue);
        sendBLE("ACK_OK");
      }
    }
  }
};

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
  popupTimer = millis();
}

void sendBLE(String msg) {
  if (deviceConnected && pTxCharacteristic != NULL) {
    pTxCharacteristic->setValue(msg.c_str());
    pTxCharacteristic->notify();
  }
}

// --- SETUP ---
void setup() {
  Serial.begin(115200);
  delay(100);

  // Inisialisasi Tombol dengan INPUT_PULLUP
  pinMode(BTN1_PIN, INPUT_PULLUP);
  pinMode(BTN2_PIN, INPUT_PULLUP);

  // Inisialisasi I2C OLED (SDA: GPIO 8, SCL: GPIO 9)
  Wire.begin(OLED_SDA_PIN, OLED_SCL_PIN);

  // Inisialisasi Layar OLED (cek alamat 0x3C dan 0x3D)
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3D)) {
      Serial.println(F("[ERROR] OLED tidak ditemukan!"));
    }
  }

  // Tampilan Splash Screen Minimalis
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(20, 18);
  display.print(F("ESP32-C3 NOTIF"));
  display.drawLine(20, 30, 108, 30, SSD1306_WHITE);
  display.setCursor(32, 38);
  display.print(F("Memulai BLE..."));
  display.display();
  delay(1000);

  // Inisialisasi BLE dengan dukungan MTU besar (517 bytes)
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(517);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Karakteristik TX (ESP32 -> Android)
  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Karakteristik RX (Android -> ESP32)
  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                                           CHARACTERISTIC_UUID_RX,
                                           BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
                                         );
  pRxCharacteristic->setCallbacks(new ReceiveCallbacks());

  pService->start();

  // Advertising BLE
  BLEAdvertising *pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  pAdv->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println(F("[BLE] Siap disambungkan ke HP!"));
}

// --- LOOP UTAMA ---
void loop() {
  // Re-connect BLE Advertising jika terputus
  if (!deviceConnected && oldDeviceConnected) {
    delay(200);
    pServer->startAdvertising();
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  // Auto-dismiss popup notifikasi setelah batas waktu
  if (currentState == STATE_POPUP && (millis() - popupTimer > POPUP_TIMEOUT)) {
    currentState = (lastState == STATE_POPUP) ? STATE_STANDBY : lastState;
  }

  // --- PEMBACAAN TOMBOL 1 (B1 - GPIO 4: Next / Geser) ---
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

  // --- PEMBACAAN TOMBOL 2 (B2 - GPIO 3: OK / Kembali / Tutup) ---
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
        if (currentMenuIdx == 0) {
          currentState = STATE_NOTIF_LIST;
          selectedNotif = 0;
        }
        else if (currentMenuIdx == 1) {
          currentState = STATE_DEVICE_STATUS;
        }
        else if (currentMenuIdx == 2) {
          // Hapus semua pesan
          notifCount = 0;
          selectedNotif = 0;
          display.clearDisplay();
          display.setTextSize(1);
          display.setTextColor(SSD1306_WHITE);
          display.setCursor(18, 26);
          display.print(F("Riwayat Terhapus"));
          display.display();
          delay(800);
          currentState = STATE_STANDBY;
        }
      }
      else if (currentState == STATE_DEVICE_STATUS) {
        currentState = STATE_STANDBY;
      }
    }
  }

  // Gambar Tampilan Layar
  drawUI();
  delay(15);
}

// ===================================================================================
// DESAIN TAMPILAN OLED (CLEAN, MINIMALIST & TIDAK BERANTAKAN)
// ===================================================================================

// Header Status Bar Bersih
void drawTopBar(const char* title, bool showBadge) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(title);

  display.setCursor(84, 0);
  if (deviceConnected) {
    display.print(F("[ON]"));
  } else {
    display.print(F("[OFF]"));
  }

  if (showBadge && notifCount > 0) {
    display.setCursor(114, 0);
    display.print("(");
    display.print(notifCount);
    display.print(")");
  }
  display.drawLine(0, 9, 127, 9, SSD1306_WHITE);
}

// Footer Navigasi Sederhana
void drawBottomBar(const char* btn1Text, const char* btn2Text) {
  display.drawLine(0, 53, 127, 53, SSD1306_WHITE);
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 56);
  display.print(btn1Text);
  if (btn2Text != NULL && strlen(btn2Text) > 0) {
    int xPos = 128 - (strlen(btn2Text) * 6);
    display.setCursor(xPos > 64 ? xPos : 68, 56);
    display.print(btn2Text);
  }
}

void drawUI() {
  display.clearDisplay();

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

// 1. LAYAR STANDBY / UTAMA
void drawStandby() {
  drawTopBar("ESP32 NOTIF", true);

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  if (notifCount == 0) {
    display.setCursor(16, 22);
    if (deviceConnected) {
      display.print(F("HP Terhubung"));
      display.setCursor(6, 36);
      display.print(F("Siap terima notif"));
    } else {
      display.print(F("Mode Standby"));
      display.setCursor(4, 36);
      display.print(F("Menunggu koneksi..."));
    }
    drawBottomBar("B1:Menu", "B2:Menu");
  } else {
    // Tampilkan ringkasan pesan terbaru secara rapi
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

// 2. POPUP NOTIFIKASI BARU (Tampilan Bersih & Terbaca)
void drawPopup() {
  if (notifCount == 0) {
    currentState = STATE_STANDBY;
    return;
  }

  Notification n = notifHistory[0];

  // Header Popup
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(F("["));
  display.print(n.app);
  display.print(F("] "));
  display.setCursor(88, 0);
  display.print(F("*BARU*"));
  display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

  // Pengirim
  display.setCursor(0, 13);
  String sender = "Dari: " + n.sender;
  if (sender.length() > 21) sender = sender.substring(0, 18) + "...";
  display.print(sender);

  // Isi Pesan (Lega tanpa garis pemotong)
  display.setCursor(0, 25);
  String msg = n.message;
  if (msg.length() > 42) msg = msg.substring(0, 39) + "...";
  display.print(msg);

  drawBottomBar("B1:Detail", "B2:Tutup");
}

// 3. DAFTAR RIWAYAT PESAN (Minimalis dengan Kursor)
void drawNotifList() {
  drawTopBar("RIWAYAT", false);

  if (notifCount == 0) {
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(18, 26);
    display.print(F("Belum ada pesan"));
    drawBottomBar("B1:Kembali", "B2:Menu");
    return;
  }

  for (int i = 0; i < notifCount && i < 3; i++) {
    int y = 13 + (i * 13);
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, y);
    if (i == selectedNotif) {
      display.print(F("> "));
    } else {
      display.print(F("  "));
    }

    String line = "[" + notifHistory[i].app + "] " + notifHistory[i].sender;
    if (line.length() > 18) line = line.substring(0, 16) + "..";
    display.print(line);
  }

  drawBottomBar("B1:Pilih", "B2:Buka");
}

// 4. DETAIL PESAN
void drawNotifDetail() {
  if (notifCount == 0) {
    currentState = STATE_STANDBY;
    return;
  }

  Notification n = notifHistory[selectedNotif];

  // Header
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(F("["));
  display.print(n.app);
  display.print(F("] "));
  display.print(selectedNotif + 1);
  display.print(F("/"));
  display.print(notifCount);
  display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

  // Pengirim
  display.setCursor(0, 13);
  String sender = "Dari: " + n.sender;
  if (sender.length() > 21) sender = sender.substring(0, 18) + "...";
  display.print(sender);

  // Pesan Utuh
  display.setCursor(0, 24);
  String msg = n.message;
  if (msg.length() > 60) msg = msg.substring(0, 57) + "...";
  display.print(msg);

  drawBottomBar("B1:Next", "B2:Kembali");
}

// 5. MENU PENGATURAN
void drawMenu() {
  drawTopBar("MENU", true);

  for (int i = 0; i < MENU_TOTAL; i++) {
    int y = 14 + (i * 12);
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, y);
    if (i == currentMenuIdx) {
      display.print(F("> "));
    } else {
      display.print(F("  "));
    }
    display.print(menuItems[i]);
  }

  drawBottomBar("B1:Geser", "B2:Pilih");
}

// 6. STATUS PERANGKAT
void drawDeviceStatus() {
  drawTopBar("STATUS", false);

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  display.setCursor(0, 14);
  display.print(F("Perangkat: ESP32-C3"));

  display.setCursor(0, 26);
  display.print(F("BLE   : "));
  display.print(deviceConnected ? "ONLINE (HP)" : "STANDBY");

  display.setCursor(0, 38);
  display.print(F("Pesan : "));
  display.print(notifCount);
  display.print(F(" tersimpan"));

  drawBottomBar("B1:Kembali", "B2:Kembali");
}
