/*
 * ===================================================================================
 * Proyek  : ESP32-C3 SuperMini - Koneksi Bluetooth Android & iPhone
 * Layar   : OLED SSD1306 I2C 0.96" (128x64)
 * MCU     : ESP32-C3 SuperMini
 * ===================================================================================
 * 
 * DETAIL PINOUT PERSIS SESUAI SKEMATIK:
 * -----------------------------------------------------------------------------------
 * [Layar OLED SSD1306 0.96"]
 *   - GND -> Pin 15 (GND ESP32-C3)
 *   - VCC -> Pin 14 (3V3 ESP32-C3)
 *   - SCL -> Pin 5  (GPIO 9 ESP32-C3)
 *   - SDA -> Pin 4  (GPIO 8 ESP32-C3)
 * 
 * [Tombol Push Button Sesuai Jalur Skematik]
 *   - B1 (Tombol 1) -> Pin 13 (GPIO 4) & GND (Pin 15) -> FUNGSI: GESER / NEXT
 *   - B2 (Tombol 2) -> Pin 12 (GPIO 3) & GND (Pin 15) -> FUNGSI: PILIH / OK / TUTUP
 * 
 * [Catatan Pin X / Tidak Terhubung di Skematik]:
 *   - GPIO 0, 1, 2, 5, 6, 7, 10, 20, 21 diberi tanda X (No Connect)
 * 
 * [Power & Baterai]
 *   - TP4056 OUT+ -> SW1 (Switch Pin 2) -> Pin 16 (5V ESP32-C3)
 *   - TP4056 OUT- / BAT- -> Pin 15 (GND ESP32-C3)
 * ===================================================================================
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

// --- PIN DEFINISI SKEMATIK ESP32-C3 SUPERMINI ---
#define OLED_SDA_PIN    8   // Pin 4 (GPIO 8)
#define OLED_SCL_PIN    9   // Pin 5 (GPIO 9)
#define BTN1_PIN        4   // Pin 13 (GPIO 4) -> Tombol B1
#define BTN2_PIN        3   // Pin 12 (GPIO 3) -> Tombol B2

// --- KONFIGURASI LAYAR OLED ---
#define SCREEN_WIDTH    128
#define SCREEN_HEIGHT   64
#define OLED_RESET      -1
#define SCREEN_ADDRESS  0x3C

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
  STATE_MENU,
  STATE_ANDROID,
  STATE_IPHONE,
  STATE_NOTIF_LIST,
  STATE_NOTIF_DETAIL,
  STATE_POPUP
};

ScreenState currentState = STATE_MENU;
ScreenState lastState = STATE_MENU;

const int MENU_TOTAL = 3;
const char* menuItems[MENU_TOTAL] = {
  "1. Hubungkan Android",
  "2. Hubungkan iPhone",
  "3. Riwayat Pesan"
};
int currentMenuIdx = 0;

// Debounce Tombol
unsigned long lastBtn1Time = 0;
unsigned long lastBtn2Time = 0;
const unsigned long DEBOUNCE_DELAY = 200;

// Timer Auto-dismiss Popup (10 detik)
unsigned long popupTimer = 0;
const unsigned long POPUP_TIMEOUT = 10000;

// Forward Declarations
void drawUI();
void drawMenu();
void drawAndroidScreen();
void drawiPhoneScreen();
void drawNotifList();
void drawNotifDetail();
void drawPopup();
void parseMessage(String msg);
void sendBLE(String msg);

// --- CALLBACK BLE ---
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

class ReceiveCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String rxValue = pCharacteristic->getValue().c_str();
    if (rxValue.length() > 0) {
      Serial.print(F("[BLE RX] "));
      Serial.println(rxValue);
      parseMessage(rxValue);
      sendBLE("ACK_OK");
    }
  }
};

// Parser Pesan Notifikasi: [APP] Pengirim: Pesan
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

  // Geser riwayat
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

  // Inisialisasi Tombol B1 (GPIO 4) & B2 (GPIO 3) dengan INPUT_PULLUP
  pinMode(BTN1_PIN, INPUT_PULLUP);
  pinMode(BTN2_PIN, INPUT_PULLUP);

  // Inisialisasi I2C OLED (SDA: GPIO 8, SCL: GPIO 9)
  Wire.begin(OLED_SDA_PIN, OLED_SCL_PIN);

  if (!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    Serial.println(F("[ERROR] OLED tidak ditemukan!"));
    for (;;);
  }

  // Tampilan Boot Bersih
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.drawRect(0, 0, 128, 64, SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(14, 18);
  display.print(F("ESP32-C3 SUPERMINI"));
  display.setCursor(18, 32);
  display.print(F("BLE NOTIF SYSTEM"));
  display.setCursor(38, 48);
  display.print(F("Siap..."));
  display.display();
  delay(1200);

  // Setup BLE
  BLEDevice::init(BLE_DEVICE_NAME);

  BLESecurity *pSecurity = new BLESecurity();
  pSecurity->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  pSecurity->setCapability(ESP_IO_CAP_NONE);
  pSecurity->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                                           CHARACTERISTIC_UUID_RX,
                                           BLECharacteristic::PROPERTY_WRITE
                                         );
  pRxCharacteristic->setCallbacks(new ReceiveCallbacks());

  pService->start();

  BLEAdvertising *pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  pAdv->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println(F("[BLE] Siap disambungkan ke Android & iPhone"));
}

// --- LOOP UTAMA ---
void loop() {
  // Re-connect BLE Advertising jika terputus
  if (!deviceConnected && oldDeviceConnected) {
    delay(300);
    pServer->startAdvertising();
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  // Auto-dismiss popup notifikasi setelah 10 detik
  if (currentState == STATE_POPUP && (millis() - popupTimer > POPUP_TIMEOUT)) {
    currentState = lastState;
  }

  // --- PEMBACAAN TOMBOL 1 (B1 - GPIO 4) ---
  // Fungsi: Geser Menu / Next Item
  if (digitalRead(BTN1_PIN) == LOW) {
    if (millis() - lastBtn1Time > DEBOUNCE_DELAY) {
      lastBtn1Time = millis();

      if (currentState == STATE_MENU) {
        currentMenuIdx = (currentMenuIdx + 1) % MENU_TOTAL;
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
      else if (currentState == STATE_POPUP) {
        if (notifCount > 1) {
          selectedNotif = (selectedNotif + 1) % notifCount;
          popupTimer = millis();
        }
      }
    }
  }

  // --- PEMBACAAN TOMBOL 2 (B2 - GPIO 3) ---
  // Fungsi: Pilih (OK) / Kembali / Tutup
  if (digitalRead(BTN2_PIN) == LOW) {
    if (millis() - lastBtn2Time > DEBOUNCE_DELAY) {
      lastBtn2Time = millis();

      if (currentState == STATE_MENU) {
        if (currentMenuIdx == 0) currentState = STATE_ANDROID;
        else if (currentMenuIdx == 1) currentState = STATE_IPHONE;
        else if (currentMenuIdx == 2) {
          currentState = STATE_NOTIF_LIST;
          selectedNotif = 0;
        }
      }
      else if (currentState == STATE_ANDROID || currentState == STATE_IPHONE) {
        currentState = STATE_MENU; // Kembali ke menu utama
      }
      else if (currentState == STATE_NOTIF_LIST) {
        if (notifCount > 0) currentState = STATE_NOTIF_DETAIL;
        else currentState = STATE_MENU;
      }
      else if (currentState == STATE_NOTIF_DETAIL) {
        currentState = STATE_NOTIF_LIST; // Kembali ke daftar
      }
      else if (currentState == STATE_POPUP) {
        currentState = lastState; // Tutup popup
      }
    }
  }

  // Render Tampilan
  drawUI();
  delay(20);
}

// --- TAMPILAN GRAFIS (CLEAN OLED UI) ---

void drawHeader(const char* title) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print(title);

  display.setCursor(80, 0);
  if (deviceConnected) {
    display.print(F("[BLE:ON]"));
  } else {
    display.print(F("[BLE:--]"));
  }
  display.drawLine(0, 9, 127, 9, SSD1306_WHITE);
}

void drawFooter(const char* text) {
  display.drawLine(0, 54, 127, 54, SSD1306_WHITE);
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 56);
  display.print(text);
}

void drawUI() {
  display.clearDisplay();

  switch (currentState) {
    case STATE_MENU:
      drawMenu();
      break;
    case STATE_ANDROID:
      drawAndroidScreen();
      break;
    case STATE_IPHONE:
      drawiPhoneScreen();
      break;
    case STATE_NOTIF_LIST:
      drawNotifList();
      break;
    case STATE_NOTIF_DETAIL:
      drawNotifDetail();
      break;
    case STATE_POPUP:
      drawPopup();
      break;
  }

  display.display();
}

// 1. MENU UTAMA
void drawMenu() {
  drawHeader("MENU UTAMA");

  for (int i = 0; i < MENU_TOTAL; i++) {
    int y = 14 + (i * 12);
    if (i == currentMenuIdx) {
      display.fillRect(0, y - 1, 128, 11, SSD1306_WHITE);
      display.setTextColor(SSD1306_BLACK);
    } else {
      display.setTextColor(SSD1306_WHITE);
    }
    display.setCursor(4, y + 1);
    display.print(menuItems[i]);
  }

  drawFooter("[B1]Geser  [B2]Pilih");
}

// 2. LAYAR SAMBUNGKAN ANDROID
void drawAndroidScreen() {
  drawHeader("KONEKSI ANDROID");

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  display.setCursor(0, 13);
  display.print(F("1. Buka Bluetooth HP"));

  display.setCursor(0, 23);
  display.print(F("2. Cari: "));
  display.print(BLE_DEVICE_NAME);

  display.setCursor(0, 33);
  display.print(F("3. Status: "));
  display.print(deviceConnected ? "TERHUBUNG" : "MENUNGGU HP");

  display.setCursor(0, 43);
  display.print(F("4. App: MacroDroid/BLE"));

  drawFooter("[B2] Kembali ke Menu");
}

// 3. LAYAR SAMBUNGKAN IPHONE
void drawiPhoneScreen() {
  drawHeader("KONEKSI IPHONE");

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  display.setCursor(0, 13);
  display.print(F("1. App: nRF Connect /"));
  display.setCursor(18, 23);
  display.print(F("LightBlue"));

  display.setCursor(0, 33);
  display.print(F("2. Cari: "));
  display.print(BLE_DEVICE_NAME);

  display.setCursor(0, 43);
  display.print(F("3. Status: "));
  display.print(deviceConnected ? "TERHUBUNG" : "MENUNGGU HP");

  drawFooter("[B2] Kembali ke Menu");
}

// 4. DAFTAR RIWAYAT NOTIFIKASI
void drawNotifList() {
  drawHeader("RIWAYAT PESAN");

  if (notifCount == 0) {
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(14, 24);
    display.print(F("Belum ada notif"));
    display.setCursor(4, 38);
    display.print(F("Kirim pesan via BLE"));
    drawFooter("[B2] Kembali Menu");
    return;
  }

  for (int i = 0; i < notifCount && i < 3; i++) {
    int y = 13 + (i * 13);
    if (i == selectedNotif) {
      display.fillRect(0, y - 1, 128, 12, SSD1306_WHITE);
      display.setTextColor(SSD1306_BLACK);
    } else {
      display.setTextColor(SSD1306_WHITE);
    }

    display.setTextSize(1);
    display.setCursor(2, y + 1);
    String line = "[" + notifHistory[i].app + "] " + notifHistory[i].sender;
    if (line.length() > 20) line = line.substring(0, 18) + "..";
    display.print(line);
  }

  drawFooter("[B1]Pilih [B2]Buka/Back");
}

// 5. DETAIL PESAN
void drawNotifDetail() {
  if (notifCount == 0) {
    currentState = STATE_NOTIF_LIST;
    return;
  }

  Notification n = notifHistory[selectedNotif];

  display.fillRect(0, 0, 48, 10, SSD1306_WHITE);
  display.setTextColor(SSD1306_BLACK);
  display.setTextSize(1);
  display.setCursor(2, 1);
  display.print(n.app);

  display.setTextColor(SSD1306_WHITE);
  display.setCursor(85, 1);
  display.print(String(selectedNotif + 1) + "/" + String(notifCount));
  display.drawLine(0, 11, 127, 11, SSD1306_WHITE);

  display.setCursor(0, 14);
  String sender = "Dari: " + n.sender;
  if (sender.length() > 21) sender = sender.substring(0, 18) + "...";
  display.print(sender);
  display.drawLine(0, 23, 127, 23, SSD1306_WHITE);

  display.setCursor(0, 26);
  String msg = n.message;
  if (msg.length() > 55) msg = msg.substring(0, 52) + "...";
  display.println(msg);

  drawFooter("[B1]Next  [B2]Kembali");
}

// 6. POPUP NOTIFIKASI MASUK
void drawPopup() {
  if (notifCount == 0) {
    currentState = lastState;
    return;
  }

  Notification n = notifHistory[0];

  display.fillRect(0, 0, 128, 64, SSD1306_BLACK);
  display.drawRect(0, 0, 128, 64, SSD1306_WHITE);

  display.fillRect(1, 1, 126, 12, SSD1306_WHITE);
  display.setTextColor(SSD1306_BLACK);
  display.setTextSize(1);
  display.setCursor(4, 3);
  display.print(F("PESAN BARU ["));
  display.print(n.app);
  display.print(F("]"));

  display.setTextColor(SSD1306_WHITE);
  display.setCursor(4, 17);
  String sender = n.sender;
  if (sender.length() > 19) sender = sender.substring(0, 16) + "...";
  display.print(sender);
  display.drawLine(4, 27, 123, 27, SSD1306_WHITE);

  display.setCursor(4, 31);
  String msg = n.message;
  if (msg.length() > 42) msg = msg.substring(0, 39) + "...";
  display.print(msg);

  display.drawLine(0, 52, 127, 52, SSD1306_WHITE);
  display.setCursor(4, 54);
  display.print(F("[B2] Tutup Pesan"));
}
