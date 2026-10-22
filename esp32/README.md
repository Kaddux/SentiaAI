# Sentia AI — ESP32-C3 Wearable Firmware (Phase 8)

Two data paths (both feed the Spring Boot backend):

| Path | Firmware | Notes |
|---|---|---|
| **HTTP batch POST** (this folder) | `esp32_sentia_ingest/esp32_sentia_ingest.ino` | Buffers readings and `POST`s to `P /api/sensors/ingest` with a JWT. Direct device→backend. |
| **WiFi UDP broadcast** (already tested) | your existing UDP firmware | Broadcasts CSV at 2 Hz to a PC; the PC-side bridge parses + batch-POSTs to the backend. Kept as the low-risk fallback. |
| **UDP + on-device steps** (new) | `esp32_sentia_udp_steps/esp32_sentia_udp_steps.ino` | Same 2 Hz UDP CSV, but the MPU6050 is sampled at 50 Hz with a peak step detector. Adds a 16th field `steps_today`. |

### Step-capable UDP firmware

`esp32_sentia_udp_steps/esp32_sentia_udp_steps.ino` is a self-contained reference
for the step path. Set `WIFI_SSID` / `WIFI_PASS` (and `GMT_OFFSET` if not IST),
flash it, and keep the PC-side bridge running.

- MPU6050 is sampled every 20 ms; gravity is high-passed and steps are counted
  on acceleration peaks with a 280 ms refractory period.
- `steps_today` is persisted in NVS and reset at local midnight.
- The 16th CSV field is forwarded as `stepsTotal`; the backend uses it as the
  authoritative daily total and falls back to the ML/live estimate only when it
  is absent.
- Tune `STEP_PEAK_G` if walking undercounts (lower) or arm gestures overcount
  (raise). Keep `STEP_REFRACTORY_MS` around 280 ms.
- If your existing UDP sketch has LED/buzzer/panic feedback, keep that sketch
  and copy `updateSteps()`, the NVS helpers, and the extra CSV field.

## Board / toolchain (from `context_2.md`)
- Board: **ESP32C3 Dev Module**
- ESP32 Arduino core **2.0.10**
- **USB CDC On Boot:** enabled · **Serial baud:** 115200

## Pinout
| Function | GPIO |
|---|---|
| I2C SDA (MPU6050 + MAX30102) | 8 |
| I2C SCL | 9 |
| DS18B20 data | 3 (external 4.7k pullup) |
| WS2812B LED | 10 (raw battery) |
| Buzzer (PWM) | 6 |
| Panic button | 7 (INPUT_PULLUP, active LOW) |
| Battery ADC (100k/100k) | 0 |

## Required libraries (Arduino Library Manager)
`OneWire`, `DallasTemperature`, **SparkFun MAX3010x** (`MAX30105.h` + `heartRate.h`), `Adafruit NeoPixel`, `WiFi` + `HTTPClient` (built-in). `Adafruit_MPU6050`/`Adafruit_Sensor` are **not required** (raw I2C).

## Configure before flashing — `esp32_sentia_ingest.ino`
```cpp
const char *WIFI_SSID   = "YOUR_WIFI_SSID";
const char *WIFI_PASS   = "YOUR_WIFI_PASS";
const char *SERVER_HOST = "192.168.1.100";   // backend IP/host from the ESP32
const uint16_t SERVER_PORT = 8080;
const char *DEVICE_JWT  = "<paste-device-bearer-token>";
const long  GMT_OFFSET  = 19800;             // IST +5:30; 0 for UTC
```
- `SERVER_HOST` must be reachable from the ESP32 (same LAN). Use the backend host IP, not `localhost`.
- Get a token with `POST /api/auth/login` (or `/register`), then paste it as the bearer.

## What it does
- Samples MAX30102 **HR** (beat-detection, continuous), MPU6050 **accel+gyro** (raw I2C), DS18B20 **temperature** (async state machine), **battery** (ADC), and **panic button** (debounced).
- Non-blocking loop (no `delay()` in the hot path).
- Buffers readings and POSTs a batch (default 30 samples / 30 s) as:
  ```json
  {"readings":[{"recordedAt":"2026-09-03T20:00:00Z","heartRate":72,"accelX":0.06,...,"fingerDetected":1}]}
  ```
  Field names (camelCase) match the backend `ReadingDto`; NTP-provides `recordedAt`.

## Verify the backend contract
The backend accepts this exact payload (verified): `POST /api/sensors/ingest` with the
`{"readings":[...]}` body, camelCase keys, JWT bearer → `{"inserted":N}` and rows land
in `sensor_readings` (incl. `gyro_*`, `battery_*`, `button_pressed`, `temp_valid`,
`finger_detected`).

## Notes / caveats
- **Not compiled here** — firmware needs the Arduino IDE (core 2.0.10) to flash and validate on real hardware. Code mirrors the sensor logic already proven in `context_2.md`.
- Board heating: the UDP + reduced-TX-power path was chosen to reduce 3.3V-LDO strain. The HTTP path uses brief Wi-Fi bursts; keep TX power modest or add a small cooling spreader if needed.
- Use `String(float, n)` for JSON floats (ESP32 printf `%f` is unreliable) — already handled.
- Prefer the **UDP broadcast + `udp_ingest_bridge.py`** path if you want to keep the tested, low-power approach; it already delivers data to the backend.
