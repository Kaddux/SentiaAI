/*
 * Sentia AI - ESP32-C3 UDP firmware with an on-device step counter.
 *
 * Simple reference path:
 *   - MPU6050 is read at 50 Hz (the old sketches read it only in the 500 ms
 *     publish block, which is too slow to resolve walking steps).
 *   - A small high-pass + peak detector counts steps.
 *   - The daily step total is persisted in NVS and reset at local midnight.
 *   - The existing 15-field UDP CSV is extended with a 16th field: steps_today.
 *
 * Packet:
 *   temp,tempValid,hr,aux,finger,battV,battPct,button,ax,ay,az,gx,gy,gz,status,steps
 *
 * The PC-side bridge forwards stepsTotal to /api/sensors/ingest.
 *
 * Board: ESP32C3 Dev Module, Arduino core 2.0.10, USB CDC On Boot ON.
 * Libraries: OneWire, DallasTemperature, SparkFun MAX3010x.
 *
 * If your existing UDP sketch has LED/buzzer/panic feedback, keep that code and
 * copy only updateSteps(), the NVS step helpers, and the final CSV field.
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Preferences.h>
#include <time.h>
#include <math.h>

#include "MAX30105.h"
#include "heartRate.h"

// ----------------------------------------------------------- configuration
const char *WIFI_SSID = "YOUR_WIFI_SSID";
const char *WIFI_PASS = "YOUR_WIFI_PASS";
const uint16_t UDP_PORT = 4210;
const long GMT_OFFSET = 19800;       // IST +5:30; seconds east of UTC

const int SAMPLE_MS = 500;           // publish cadence (2 Hz)
const int IMU_MS = 20;               // 50 Hz accelerometer sampling
const int IMU_HZ = 50;

// Step detector tuning. Lower STEP_PEAK_G if normal walking undercounts;
// raise it if arm gestures count as steps. Refractory caps cadence at ~3.5/s.
const float STEP_PEAK_G = 0.14f;
const unsigned long STEP_REFRACTORY_MS = 280;
const float STEP_GRAVITY_ALPHA = 0.08f;

// ----------------------------------------------------------- pinout
const int PIN_SDA = 8, PIN_SCL = 9;
const int PIN_DS18B20 = 3;
const int PIN_BUTTON = 7;
const int PIN_BATT = 0;

// MAX30102
MAX30105 particleSensor;
const byte RATE_SIZE = 4;
float rates[RATE_SIZE];
byte rateSpot = 0;
long lastBeat = 0;
int beatAvg = 0;

// DS18B20 (async)
OneWire oneWire(PIN_DS18B20);
DallasTemperature ds(&oneWire);
DeviceAddress dsAddr;
enum DSState { DS_IDLE, DS_WAITING };
DSState dsState = DS_IDLE;
unsigned long dsStarted = 0;
const unsigned long DS_CYCLE_MS = 3000;
const unsigned long DS_CONVERSION_MS = 750;
float bodyTemp = 0.0;
bool tempValid = false;
bool dsFound = false;

// Button / battery
bool lastButton = HIGH;
unsigned long lastButtonChange = 0;
const unsigned long BUTTON_DEBOUNCE = 400;
int batteryPct = 0;
float batteryV = 0.0;

// MPU6050 latest values (published at 2 Hz, updated at 50 Hz)
float accelX = 0, accelY = 0, accelZ = 0;
float gyroX = 0, gyroY = 0, gyroZ = 0;
float gravityEst = 1.0f;
float prevLin = 0.0f;
unsigned long lastStepMs = 0;

// Step counter + NVS
Preferences prefs;
uint32_t stepsToday = 0;
char stepsDay[11] = "";

// Scheduling
unsigned long lastImu = 0;
unsigned long lastSample = 0;
unsigned long lastDsCycle = 0;
unsigned long lastWifiAttempt = 0;
WiFiUDP udp;

// --------------------------------------------------------------- setup
void setup() {
  Serial.begin(115200);
  Serial.println("\nSentia AI UDP + steps boot");

  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(400000);
  initMpu();

  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println("MAX30102 not found");
  }
  particleSensor.setup();
  particleSensor.setPulseAmplitudeRed(0x0A);
  particleSensor.setPulseAmplitudeIR(0x0A);

  ds.begin();
  ds.setWaitForConversion(false);
  dsFound = ds.getAddress(dsAddr, 0);

  pinMode(PIN_BUTTON, INPUT_PULLUP);
  initSteps();

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting");
  while (WiFi.status() != WL_CONNECTED) {
    delay(400);
    Serial.print(".");
  }
  Serial.println("\nWiFi connected, IP " + WiFi.localIP().toString());
  configTime(GMT_OFFSET, 0, "pool.ntp.org", "time.nist.gov");

  udp.begin(UDP_PORT + 1);          // device's local port; bridge listens on 4210
  lastImu = millis();
  lastSample = millis();
  lastDsCycle = millis();
  Serial.println("Publishing UDP straight to the subnet broadcast");
}

void mpuWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(0x68);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}

void initMpu() {
  mpuWrite(0x6B, 0x00);   // wake
  mpuWrite(0x19, 0x09);   // 1 kHz / (1 + 9) = 100 Hz sample rate
  mpuWrite(0x1A, 0x03);   // DLPF ~44 Hz
  mpuWrite(0x1C, 0x00);   // accel +/-2 g
}

// --------------------------------------------------------------- loop
void loop() {
  sampleHeartRate();

  unsigned long now = millis();
  if (now - lastImu >= IMU_MS) {
    lastImu = now;
    readMPU();
    updateSteps();
  }

  if (now - lastDsCycle >= DS_CYCLE_MS) advanceDS18B20();

  if (now - lastSample >= SAMPLE_MS) {
    lastSample = now;
    readBattery();
    bool btn = readButton();
    checkDay();
    publishData(btn);
  }

  if (WiFi.status() != WL_CONNECTED && now - lastWifiAttempt > 5000) {
    lastWifiAttempt = now;
    WiFi.reconnect();
  }
}

// ------------------------------------------------------- sensors
void readMPU() {
  Wire.beginTransmission(0x68);
  Wire.write(0x3B);
  Wire.endTransmission(false);
  Wire.requestFrom(0x68, 14);
  if (Wire.available() >= 14) {
    int16_t ax = (Wire.read() << 8) | Wire.read();
    int16_t ay = (Wire.read() << 8) | Wire.read();
    int16_t az = (Wire.read() << 8) | Wire.read();
    Wire.read(); Wire.read();               // chip temperature
    int16_t gx = (Wire.read() << 8) | Wire.read();
    int16_t gy = (Wire.read() << 8) | Wire.read();
    int16_t gz = (Wire.read() << 8) | Wire.read();
    accelX = ax / 16384.0; accelY = ay / 16384.0; accelZ = az / 16384.0;
    gyroX = gx / 131.0;    gyroY = gy / 131.0;    gyroZ = gz / 131.0;
  }
}

void updateSteps() {
  float mag = sqrtf(accelX * accelX + accelY * accelY + accelZ * accelZ);
  gravityEst += (mag - gravityEst) * STEP_GRAVITY_ALPHA;   // ~0.25 s time constant
  float lin = mag - gravityEst;

  // Count the falling edge of a positive acceleration peak. The refractory
  // period (280 ms) prevents double-counting one footfall and caps cadence.
  if (prevLin > STEP_PEAK_G && lin <= prevLin &&
      (millis() - lastStepMs) > STEP_REFRACTORY_MS) {
    stepsToday++;
    lastStepMs = millis();
    if (stepsToday % 20 == 0) saveSteps();
  }
  prevLin = lin;
}

void sampleHeartRate() {
  long ir = particleSensor.getIR();
  if (ir > 50000) {
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
  batteryV = (raw / 4095.0) * 3.3 * 2.0;
  batteryPct = (int)constrain(((batteryV - 3.0) / (4.2 - 3.0)) * 100, 0, 100);
}

bool readButton() {
  bool raw = digitalRead(PIN_BUTTON);
  if ((millis() - lastButtonChange) > BUTTON_DEBOUNCE) {
    if (raw != lastButton) {
      lastButton = raw;
      lastButtonChange = millis();
    }
  }
  return lastButton == LOW;
}

void advanceDS18B20() {
  if (!dsFound) return;
  if (dsState == DS_IDLE) {
    ds.requestTemperatures();
    dsStarted = millis();
    dsState = DS_WAITING;
  } else if (dsState == DS_WAITING && (millis() - dsStarted) >= DS_CONVERSION_MS) {
    float t = ds.getTempC(dsAddr);
    if (t != DEVICE_DISCONNECTED_C && t > -100) {
      bodyTemp = t;
      tempValid = true;
    } else {
      tempValid = false;
    }
    dsState = DS_IDLE;
    lastDsCycle = millis();
  }
}

// ---------------------------------------------------- daily step storage
bool localDayKey(char out[11]) {
  time_t now = time(nullptr);
  if (now < 100000) return false;    // NTP not ready yet
  struct tm tm;
  localtime_r(&now, &tm);
  strftime(out, 11, "%Y-%m-%d", &tm);
  return true;
}

void initSteps() {
  prefs.begin("sentia-steps", false);
  String savedDay = prefs.getString("day", "");
  savedDay.toCharArray(stepsDay, sizeof(stepsDay));
  stepsToday = prefs.getUInt("steps", 0);
  Serial.printf("[steps] restored day=%s steps=%lu\n", stepsDay, (unsigned long)stepsToday);
}

void saveSteps() {
  prefs.putString("day", stepsDay);
  prefs.putUInt("steps", stepsToday);
}

void checkDay() {
  char key[11];
  if (!localDayKey(key)) return;
  if (stepsDay[0] == '\0') {
    strncpy(stepsDay, key, sizeof(stepsDay) - 1);
    stepsDay[sizeof(stepsDay) - 1] = '\0';
    return;
  }
  if (strcmp(key, stepsDay) != 0) {
    strncpy(stepsDay, key, sizeof(stepsDay) - 1);
    stepsDay[sizeof(stepsDay) - 1] = '\0';
    stepsToday = 0;
    saveSteps();
    Serial.printf("[steps] new day %s\n", stepsDay);
  }
}

// ------------------------------------------------------------- UDP publish
IPAddress broadcastAddress() {
  IPAddress ip = WiFi.localIP();
  IPAddress mask = WiFi.subnetMask();
  IPAddress bc;
  for (int i = 0; i < 4; i++) bc[i] = (uint8_t)(ip[i] | (uint8_t)~mask[i]);
  return bc;
}

void publishData(bool btn) {
  bool finger = particleSensor.getIR() > 50000;
  int hr = finger ? beatAvg : 0;
  float temp = tempValid ? bodyTemp : 0.0f;

  // Before NTP is ready we do not know the local day. Do not publish a
  // restored yesterday total under today's timestamp; send 0 until time is
  // valid, then checkDay() will either keep today's saved count or reset it.
  char dayKey[11];
  uint32_t stepsOut = localDayKey(dayKey) ? stepsToday : 0;

  String line = String(temp, 2);
  line += ","; line += tempValid ? "1" : "0";
  line += ","; line += String(hr);
  line += ",0";                       // aux (unused)
  line += ","; line += finger ? "1" : "0";
  line += ","; line += String(batteryV, 2);
  line += ","; line += String(batteryPct);
  line += ","; line += btn ? "1" : "0";
  line += ","; line += String(accelX, 3);
  line += ","; line += String(accelY, 3);
  line += ","; line += String(accelZ, 3);
  line += ","; line += String(gyroX, 2);
  line += ","; line += String(gyroY, 2);
  line += ","; line += String(gyroZ, 2);
  line += ",0";                       // status (unused)
  line += ","; line += String(stepsOut);

  IPAddress bc = broadcastAddress();
  udp.beginPacket(bc, UDP_PORT);
  udp.write((const uint8_t *)line.c_str(), line.length());
  udp.endPacket();

  Serial.println(line);
}
