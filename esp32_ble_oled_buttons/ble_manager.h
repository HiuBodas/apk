#ifndef BLE_MANAGER_H
#define BLE_MANAGER_H

/*
 * ===================================================================================
 * MODUL BLE MANAGER (LOGIKA BLE TERISOLASI & TERKUNCI)
 * ===================================================================================
 * Modul ini menangani seluruh fungsi komunikasi Bluetooth Low Energy (BLE)
 * menggunakan profil Nordic UART Service (NUS).
 * Logika di file ini dikunci agar tidak berubah saat mengedit UI / tombol.
 * ===================================================================================
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- KONFIGURASI BLE (NORDIC UART SERVICE) ---
#define BLE_DEVICE_NAME        "ESP32-SmartNotif"
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// Enum Target OS
enum TargetOS {
  OS_NONE,
  OS_ANDROID,
  OS_IOS
};

// Forward declaration fungsi parser pesan di file .ino
void parseMessage(String raw);

// Pointer Objek BLE
inline BLEServer* pServer = NULL;
inline BLECharacteristic* pTxCharacteristic = NULL;
inline BLECharacteristic* pRxCharacteristic = NULL;
inline BLEAdvertising* pAdv = NULL;

// Status Koneksi BLE
inline bool bleInitialized = false;
inline bool bleActive = false;           // Default: Bluetooth MATI saat awal booting
inline bool deviceConnected = false;
inline TargetOS currentTargetOS = OS_NONE;

// Forward Declarations Fungsi BLE
void sendBLE(String msg);
void initBLEStack();
void startBLE(TargetOS target);
void stopBLE();

// --- CALLBACK BLE SERVER ---
class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println(F("[BLE] HP Terhubung!"));
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println(F("[BLE] HP Terputus!"));
    // Otomatis pasang iklan ulang jika Bluetooth masih aktif
    if (bleActive) {
      pServer->startAdvertising();
      Serial.println(F("[BLE] Siap menerima koneksi baru..."));
    }
  }
};

// --- CALLBACK BLE RECEIVE (Karakteristik RX: HP -> ESP32) ---
class ReceiveCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) override {
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

// Inisialisasi Stack BLE (Bebas enkripsi/bonding agar koneksi instan dan bebas error GATT 133)
inline void initBLEStack() {
  if (bleInitialized) return;

  BLEDevice::init(BLE_DEVICE_NAME);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Karakteristik TX (ESP32 -> HP)
  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Karakteristik RX (HP -> ESP32)
  pRxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_RX,
                        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
                      );
  pRxCharacteristic->setCallbacks(new ReceiveCallbacks());

  pService->start();

  // Pengaturan Advertising Aman
  pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  pAdv->setMinPreferred(0x12);

  bleInitialized = true;
  Serial.println(F("[BLE] Stack BLE siap digunakan."));
}

// Menyalakan Bluetooth On-Demand
inline void startBLE(TargetOS target) {
  initBLEStack();

  currentTargetOS = target;
  bleActive = true;

  if (pAdv != NULL) {
    pAdv->start();
  } else {
    BLEDevice::startAdvertising();
  }

  Serial.print(F("[BLE] Bluetooth AKTIF - Mode: "));
  Serial.println(target == OS_ANDROID ? F("Android") : F("iOS (iPhone)"));
}

// Mematikan Bluetooth & Memutus Koneksi HP
inline void stopBLE() {
  if (bleActive) {
    bleActive = false;

    // 1. Hentikan pemancaran sinyal (advertising)
    if (pAdv != NULL) {
      pAdv->stop();
    }

    // 2. Putus koneksi HP jika sedang tersambung
    if (deviceConnected && pServer != NULL) {
      pServer->disconnect(pServer->getConnId());
    }

    deviceConnected = false;
    currentTargetOS = OS_NONE;
    Serial.println(F("[BLE] Bluetooth DINONAKTIFKAN (OFF)"));
  }
}

// Mengirim pesan balik ke HP via BLE
inline void sendBLE(String msg) {
  if (deviceConnected && pTxCharacteristic != NULL) {
    pTxCharacteristic->setValue(msg.c_str());
    pTxCharacteristic->notify();
  }
}

#endif // BLE_MANAGER_H
