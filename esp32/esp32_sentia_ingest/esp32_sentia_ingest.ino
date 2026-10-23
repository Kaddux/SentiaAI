/*
 * Sentia AI — ESP32-C3 wearable firmware (Phase 8)
 * Samples HR (MAX30102), accelerometer+gyro (MPU6050, raw I2C), temperature
 * (DS18B20, async), battery + panic button, buffers readings, and batch-POSTs
 * them to the Spring Boot backend  POST /api/sensors/ingest  (JWT).
 *
 * Board: ESP32C3 Dev Module  ·  ESP32 Arduino core 2.0.10  ·  USB CDC On Boot ON
 * Libraries: OneWire, DallasTemperature, SparkFun MAX3010x (MAX30105.h + heartRate.h),
 *            WiFi + HTTPClient (built-in). Adafruit_MPU6050 NOT required (raw I2C).
 *
 * This is the HTTP-POST data path. The alternative is WiFi-UDP broadcast (already
 * tested) feeding the PC-side  udp_ingest_bridge.py  — see esp32/README.md.
 *
 * Fill in credentials below, then flash.
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClient.h>
#include <Wire.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <time.h>
#include <stdio.h>

#include "MAX30105.h"
#include "heartRate.h"

// ----------------------------------------------------------- configuration
const char *WIFI_SSID     = "YOUR_WIFI_SSID";
const char *WIFI_PASS     = "YOUR_WIFI_PASS";
const char *SERVER_HOST   = "192.168.1.100";   // backend host/IP reachable from ESP32
const uint16_t SERVER_PORT = 8080;
const char *API_PATH      = "/api/sensors/ingest";
// Device JWT (a user/device token from POST /api/auth/login or /register).
const char *DEVICE_JWT    = "<paste-device-bearer-token>";
const long  GMT_OFFSET    = 19800;             // e.g. IST +5:30. Set to 0 for UTC.

const int SAMPLE_MS    = 500;   // publish cadence (ms) -> ~2 Hz
const int BATCH_SIZE   = 30;    // samples per HTTP POST (~15 s)
const int BATCH_SECS   = 30;    // force a POST at least this often

// ----------------------------------------------------------- pinout (context_2.md)
const int PIN_SDA = 8, PIN_SCL = 9;   // I2C (MPU6050 + MAX30102), onboard pullups
const int PIN_DS18B20 = 3;            // OneWire, external 4.7k pullup
const int PIN_BUTTON = 7;             // panic button, INPUT_PULLUP, active LOW
const int PIN_BATT   = 0;             // ADC, 100k/100k divider

// MAX30102 beat detection
MAX30105 particleSensor;
const byte RATE_SIZE = 4;
float rates[RATE_SIZE];
byte rateSpot = 0;
long lastBeat = 0;
int beatAvg = 0;

// DS18B20 async
OneWire oneWire(PIN_DS18B20);
DallasTemperature ds(&oneWire);
DeviceAddress dsAddr;
enum DSState { DS_IDLE, DS_WAITING };
DSState dsState = DS_IDLE;
unsigned long dsStarted = 0;
const unsigned long DS_CYCLE_MS = 3000;   // temperature changes slowly
const unsigned long DS_CONVERSION_MS = 750;
float bodyTemp = 0.0;
bool tempValid = false;
bool dsFound = false;

// Button debounce
bool lastButton = HIGH;
unsigned long lastButtonChange = 0;
const unsigned long BUTTON_DEBOUNCE = 400;

// Battery
int batteryPct = 0;
float batteryV = 0.0;

// MPU6050 raw
float accelX, accelY, accelZ, gyroX, gyroY, gyroZ;

// Ingest buffer
struct Reading { float temp, ax, ay, az, gx, gy, gz, battV; int hr, battPct, btn, tValid, finger; };
Reading buffer[BATCH_SIZE];
int bufferCount = 0;
unsigned long lastPost = 0;
unsigned long lastSample = 0;
unsigned long lastDsCycle = 0;
bool timeSynced = false;

// --------------------------------------------------------------- setup
void setup() {
  Serial.begin(115200);
  Serial.println("\nSentia AI wearable boot");

  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(400000);

  // MPU6050: wake it up, raw register access (clone-friendly).
  Wire.beginTransmission(0x68);
  Wire.write(0x6B);          // PWR_MGMT_1
  Wire.write(0x00);          // clear sleep bit
  Wire.endTransmission();

  // MAX30102 init
  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("MAX30102 not found");
  }
  particleSensor.setup();
  particleSensor.setPulseAmplitudeRed(0x0A);
  particleSensor.setPulseAmplitudeIR(0x0A);

  // DS18B20 (address-based, async)
  ds.begin();
  ds.setWaitForConversion(false);
  dsFound = ds.getAddress(dsAddr, 0);

  pinMode(PIN_BUTTON, INPUT_PULLUP);
  delay(200);

  // Wifi + NTP
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting");
  while (WiFi.status() != WL_CONNECTED) { delay(400); Serial.print("."); }
  Serial.println("\nWiFi connected, IP " + WiFi.localIP().toString());
  configTime(GMT_OFFSET, 0, "pool.ntp.org", "time.nist.gov");
}

// --------------------------------------------------------------- loop
void loop() {
  sampleHeartRate();          // every iteration (beat detection needs high rate)

  if (millis() - lastDsCycle >= DS_CYCLE_MS) advanceDS18B20();   // async temperature

  if (millis() - lastSample >= SAMPLE_MS) {
    lastSample = millis();
    readMPU();
    readBattery();
    bool btn = readButton();
    bufferAdd(btn);
    if (bufferCount >= BATCH_SIZE || (bufferCount > 0 && (millis() - lastPost) / 1000 >= BATCH_SECS)) {
      postBatch();
    }
  }
}

// ------------------------------------------------------- sensors (context_2.md)
void readMPU() {
  Wire.beginTransmission(0x68);
  Wire.write(0x3B);
  Wire.endTransmission(false);
  Wire.requestFrom(0x68, 14);
  if (Wire.available() >= 14) {
    int16_t ax = (Wire.read() << 8) | Wire.read();
    int16_t ay = (Wire.read() << 8) | Wire.read();
    int16_t az = (Wire.read() << 8) | Wire.read();
    Wire.read(); Wire.read();               // chip temp (skip)
    int16_t gx = (Wire.read() << 8) | Wire.read();
    int16_t gy = (Wire.read() << 8) | Wire.read();
    int16_t gz = (Wire.read() << 8) | Wire.read();
    accelX = ax / 16384.0; accelY = ay / 16384.0; accelZ = az / 16384.0;
    gyroX = gx / 131.0;    gyroY = gy / 131.0;    gyroZ = gz / 131.0;
  }
}

void sampleHeartRate() {
  long ir = particleSensor.getIR();
  if (ir > 50000) {                          // finger present
    if (checkForBeat(ir)) {
      long now = millis();
      long delta = now - lastBeat;
      lastBeat = now;
      float bpm = 60.0 / (delta / 1000.0);
      if (bpm > 20 && bpm < 255) {
        rates[rateSpot++] = bpm;
        rateSpot %= RATE_SIZE;
        float sum = 0;
        for (byte i = 0; i < RATE_SIZE; i++) sum += rates[i];
        beatAvg = (int)(sum / RATE_SIZE);
      }
    }
  }
}

void readBattery() {
  int raw = analogRead(PIN_BATT);
  batteryV = (raw / 4095.0) * 3.3 * 2.0;           // undo 100k/100k divider
  batteryPct = (int)constrain(((batteryV - 3.0) / (4.2 - 3.0)) * 100, 0, 100);
}

bool readButton() {
  bool raw = digitalRead(PIN_BUTTON);
  if ((millis() - lastButtonChange) > BUTTON_DEBOUNCE) {
    if (raw != lastButton) { lastButton = raw; lastButtonChange = millis(); }
  }
  return lastButton == LOW;   // debounced current state (active LOW)
}

void advanceDS18B20() {
  if (!dsFound) return;
  if (dsState == DS_IDLE) {
    ds.requestTemperatures();
    dsStarted = millis();
    dsState = DS_WAITING;
  } else if (dsState == DS_WAITING && (millis() - dsStarted) >= DS_CONVERSION_MS) {
    float t = ds.getTempC(dsAddr);
    if (t != DEVICE_DISCONNECTED_C && t > -100) { bodyTemp = t; tempValid = true; }
    else { tempValid = false; }
    dsState = DS_IDLE;
    lastDsCycle = millis();          // gate the next temperature cycle
  }
}

void bufferAdd(bool btn) {
  if (bufferCount >= BATCH_SIZE) return;
  buffer[bufferCount++] = {
    tempValid ? bodyTemp : 0.0f,   // temperature (0 if invalid; tempValid flag sent)
    accelX, accelY, accelZ, gyroX, gyroY, gyroZ, batteryV,
    beatAvg, batteryPct, btn ? 1 : 0, tempValid ? 1 : 0,
    (particleSensor.getIR() > 50000) ? 1 : 0
  };
}

// ------------------------------------------------------- ingest (HTTP POST)
void postBatch() {
  if (bufferCount == 0) return;

  // Ensure an ISO8601 timestamp. Fall back to boot-relative if NTP not synced.
  if (!timeSynced && time(nullptr) > 100000) timeSynced = true;
  char ts[32];
  time_t now = time(nullptr);
  strftime(ts, sizeof(ts), "%Y-%m-%dT%H:%M:%SZ", gmtime(&now));

  // Build {"readings":[...]}  (use String(float, n) — ESP32 printf %f is unreliable)
  String body = "{\"readings\":[";
  for (int i = 0; i < bufferCount; i++) {
    Reading &r = buffer[i];
    if (i) body += ",";
    body += "{\"recordedAt\":\"" + String(ts) + "\"";
    body += ",\"heartRate\":" + String(r.hr);
    body += ",\"accelX\":" + String(r.ax, 4) + ",\"accelY\":" + String(r.ay, 4) + ",\"accelZ\":" + String(r.az, 4);
    body += ",\"gyroX\":" + String(r.gx, 4) + ",\"gyroY\":" + String(r.gy, 4) + ",\"gyroZ\":" + String(r.gz, 4);
    body += ",\"temperature\":" + String(r.temp, 1);
    body += ",\"batteryV\":" + String(r.battV, 2) + ",\"batteryPct\":" + String(r.battPct);
    body += ",\"buttonPressed\":" + String(r.btn) + ",\"tempValid\":" + String(r.tValid) + ",\"fingerDetected\":" + String(r.finger) + "}";
  }
  body += "]}";

  HTTPClient http;
  http.begin(SERVER_HOST, SERVER_PORT, API_PATH);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Authorization", String("Bearer ") + DEVICE_JWT);
  int code = http.POST(body);
  Serial.printf("[ingest] POST %d -> HTTP %d\n", bufferCount, code);
  http.end();

  bufferCount = 0;
  lastPost = millis();
}
