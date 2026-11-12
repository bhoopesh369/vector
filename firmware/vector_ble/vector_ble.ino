// Vector USB motion demo. Display/pins adapted from Waveshare's 2.06-inch
// Arduino examples (Copyright 2026 Waveshare, Apache-2.0).
// Modified: enable all six IMU axes, timestamp CSV, independent acquisition.
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <atomic>
#include <Wire.h>
#include <HWCDC.h>
#include <esp_timer.h>
#include <SensorQMI8658.hpp>
#include <Arduino_GFX_Library.h>

HWCDC USBSerial;
SensorQMI8658 imu;
Arduino_ESP32QSPI displayBus(12, 11, 4, 5, 6, 7);
Arduino_CO5300 display(&displayBus, 8, 0, 410, 502, 22, 0, 0, 0);

struct Sample {
  uint32_t seq;
  int64_t timestamp;
  float ax, ay, az, gx, gy, gz;
  bool clipped;
};
QueueHandle_t samples;
BLEServer *bleServer;
BLECharacteristic *motion;
std::atomic<bool> bleConnected{false};
class ConnectionCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { bleConnected = true; }
  void onDisconnect(BLEServer *) override { bleConnected = false; }
};
void initBluetooth() {
  BLEDevice::init("Vector Motion");
  BLEDevice::setMTU(185);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ConnectionCallbacks());
  bleServer->advertiseOnDisconnect(true);
  BLEService *service = bleServer->createService("7b8e0001-6f27-4b8e-a9a1-3cd405ee1920");
  motion = service->createCharacteristic("7b8e0002-6f27-4b8e-a9a1-3cd405ee1920", BLECharacteristic::PROPERTY_NOTIFY);
  motion->addDescriptor(new BLE2902());
  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(service->getUUID());
  advertising->setScanResponse(true);
  advertising->start();
}
void notifySample(const Sample &s) {
  if (!bleConnected || bleServer->getPeerMTU(bleServer->getConnId()) < 29) return;
  uint8_t packet[26] = {1, uint8_t(s.clipped)};
  // Explicit little-endian layout, independent of structure padding.
  for (int i=0; i<4; i++) packet[2+i] = (s.seq >> (8*i)) & 255;
  for (int i=0; i<8; i++) packet[6+i] = (uint64_t(s.timestamp) >> (8*i)) & 255;
  float axes[] = {s.ax*1000, s.ay*1000, s.az*1000, s.gx*16, s.gy*16, s.gz*16};
  for (int i=0; i<6; i++) {
    int16_t value = int16_t(lroundf(axes[i]));
    packet[14+2*i] = uint16_t(value) & 255;
    packet[15+2*i] = (uint16_t(value) >> 8) & 255;
  }
  motion->setValue(packet, sizeof(packet));
  motion->notify();
}
bool displayReady = false;
const char *HEADER = "seq,t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,clipped";

void fail(const char *message) {
  if (displayReady) {
    display.fillScreen(RGB565_BLACK);
    display.setCursor(24, 70);
    display.setTextColor(RGB565_RED);
    display.setTextSize(2);
    display.println(message);
  }
  while (true) {
    USBSerial.printf("# ERROR: %s\n", message);
    delay(1000);
  }
}

// This task alone owns I2C after setup. The display and USB run on the other core.
void acquire(void *) {
  uint32_t sequence = 0;
  while (true) {
    if (imu.getDataReady()) {
      Sample s{};
      s.timestamp = esp_timer_get_time(); // Host-read time, not a hardware IMU timestamp.
      if (imu.getAccelerometer(s.ax, s.ay, s.az) && imu.getGyroscope(s.gx, s.gy, s.gz)) {
        s.seq = sequence++;
        s.clipped = fabsf(s.ax) >= 15.8f || fabsf(s.ay) >= 15.8f || fabsf(s.az) >= 15.8f
                    || fabsf(s.gx) >= 1010 || fabsf(s.gy) >= 1010 || fabsf(s.gz) >= 1010;
        // A full queue drops a sample instead of delaying acquisition.
        // Sequence gaps expose that loss to the capture utility.
        xQueueSend(samples, &s, 0);
      }
    }
    vTaskDelay(1);
  }
}

void setup() {
  USBSerial.begin(115200);
  USBSerial.setTxTimeoutMs(0);
  displayReady = display.begin();
  if (displayReady) {
    display.setBrightness(100);
    display.fillScreen(RGB565_BLACK);
    display.setTextColor(RGB565_CYAN);
    display.setTextSize(3);
    display.setCursor(24, 36);
    display.println("VECTOR / MOTION");
    display.setTextColor(RGB565_WHITE);
    display.setTextSize(2);
    display.setCursor(24, 90);
    display.println("Bluetooth + USB");
    display.setCursor(24, 400);
    display.println("Tilt to see readings");
    display.setCursor(24, 430);
    display.println("Connect: Vector Motion");
  }
  // Official board examples use the hardware's existing power-rail defaults.
  // Do not change charging current or voltage for this USB-powered demo.
  Wire.begin(15, 14);
  Wire.setClock(400000);
  if (!imu.begin(Wire, QMI8658_L_SLAVE_ADDRESS, 15, 14)) fail("IMU not detected");
  if (imu.configAccelerometer(SensorQMI8658::ACC_RANGE_16G,
                              SensorQMI8658::ACC_ODR_125Hz, SensorQMI8658::LPF_MODE_3) != 0
      || imu.configGyroscope(SensorQMI8658::GYR_RANGE_1024DPS,
                             SensorQMI8658::GYR_ODR_112_1Hz, SensorQMI8658::LPF_MODE_3) != 0)
    fail("IMU config failed");
  // In six-axis mode the gyro clock sets both output rates (~112.1 Hz).
  if (!imu.enableGyroscope() || !imu.enableAccelerometer()) fail("IMU enable failed");
  initBluetooth();
  samples = xQueueCreate(128, sizeof(Sample));
  if (!samples) fail("Sample queue failed");
  if (xTaskCreatePinnedToCore(acquire, "imu", 4096, nullptr, 2, nullptr, 0) != pdPASS)
    fail("Sensor task failed");
}

void loop() {
  static uint32_t lastDisplay = 0;
  static bool wasConnected = false;
  const bool connected = USBSerial;
  if (connected && !wasConnected) {
    USBSerial.println("# vector_motion v1; QMI8658; acc_range_g=16; gyro_range_dps=1024; nominal_hz=112.1; timestamp=host_read_us; lpf=mode3");
    USBSerial.println(HEADER);
  }
  wasConnected = connected;
  Sample s{};
  bool received = false;
  while (xQueueReceive(samples, &s, 0) == pdTRUE) {
    received = true;
    notifySample(s);
    if (connected) {
      char line[180];
      const int length = snprintf(line, sizeof(line), "%lu,%lld,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f,%u\n",
          (unsigned long)s.seq, (long long)s.timestamp, s.ax, s.ay, s.az, s.gx, s.gy, s.gz, s.clipped ? 1 : 0);
      if (length > 0 && length < (int)sizeof(line) && USBSerial.availableForWrite() >= length)
        USBSerial.write((const uint8_t *)line, length);
    }
  }
  if (received && displayReady && millis() - lastDisplay >= 250) {
    lastDisplay = millis();
    display.fillRect(24, 140, 365, 252, RGB565_BLACK);
    display.setCursor(24, 140);
    display.setTextSize(2);
    display.setTextColor(RGB565_WHITE);
    display.printf("Acceleration (g)\n\n  X %7.3f\n  Y %7.3f\n  Z %7.3f\n\nRotation (deg/s)\n\n  X %7.1f\n  Y %7.1f\n  Z %7.1f", s.ax, s.ay, s.az, s.gx, s.gy, s.gz);
  }
  delay(1);
}
