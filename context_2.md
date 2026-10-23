# IoT Wearable Device — Project Context

## Overview
A wrist-worn IoT health/safety wearable built on ESP32-C3, combining motion sensing,
body temperature, heart rate (computed BPM), a panic button, an addressable LED ring
for status/alerts, and a passive buzzer for audible alerts. Currently in **testing/
bring-up phase** — all hardware wired, all sensors confirmed working, wireless data
link to PC confirmed working via WiFi UDP broadcast (current final approach after
a full BLE evaluation — see "BLE vs WiFi" section below for the full reasoning).

---

## Parts List

| # | Part | Role |
|---|---|---|
| 1 | ESP32-C3 Supermini | Main MCU, WiFi/BLE radio |
| 2 | MPU6050 | Accelerometer + Gyroscope (I2C, raw register access) |
| 3 | DS18B20 | Body temperature (OneWire, async/non-blocking) |
| 4 | MAX30102 | Heart rate — computed BPM via beat-detection algorithm (I2C) |
| 5 | Boost converter (5V) | Not used in final power plan |
| 6 | 3.7V LiPo + TP4056 charger | Battery + charging (charge-current issue resolved separately, not a current concern) |
| 7 | Passive buzzer module | Audible alerts, driven via PWM tone (LEDC), non-blocking |
| 8 | WS2812B addressable LED ring (8 LEDs) | Visual status/alerts, non-blocking spinning chase animation on button press |
| 9 | Push button | Panic button — triggers buzzer + LED animation, debounced |

---

## Power Architecture

- **3.3V rail (ESP32 onboard LDO):** MPU6050, MAX30102, DS18B20, buzzer
  - Buffered with 220–470µF electrolytic + 100nF ceramic cap at the 3V3 pin to reduce
    radio TX transient brownout risk.
- **Battery raw (3.7–4.2V):** WS2812B ring, powered directly (avoids logic-level
  mismatch between 3.3V GPIO data line and a 5V-powered ring).
- **5V boost converter:** not used.
- **ESP32 board heating (investigated):** was running hot under WiFi web-server load
  in earlier testing. Suspected cause: onboard 3.3V LDO current strain from
  simultaneous WiFi TX bursts (~300-400mA) + MAX30102 + MPU6050 + buzzer all sharing
  that rail. Current UDP-based approach with reduced TX power (see below) should
  meaningfully reduce this, since transmission is lighter and less frequent than the
  old web-server approach, and peak TX current draw itself is lower. Not yet formally
  re-measured under the new UDP code, but expected to be improved.

---

## Pinout (ESP32-C3 Supermini)

| Function | GPIO | Notes |
|---|---|---|
| I2C SDA (MPU6050 + MAX30102, shared bus) | GPIO8 | Boards have onboard pullups (~10k each, parallel ≈ 5kΩ) — no external resistor needed |
| I2C SCL (shared bus) | GPIO9 | Same as above |
| DS18B20 data | GPIO3 | External 4.7kΩ pullup to 3.3V — wiring confirmed correct; reading issue was a firmware/timing bug, not hardware (see DS18B20 section) |
| WS2812B data | GPIO10 | Powered from raw battery, brightness capped in firmware (20/255) |
| Buzzer (passive, PWM tone) | GPIO6 | LEDC PWM, non-blocking |
| Panic button | GPIO7 | INPUT_PULLUP, active LOW, debounced in firmware |
| Battery voltage sense (ADC) | GPIO0 | 100kΩ/100kΩ divider, no cap — now implemented and transmitted in the data packet |

**Avoid:** GPIO2, GPIO8, GPIO9 are boot-strapping pins — GPIO8/9 are safely used for
I2C because pullups keep them HIGH at boot (matches required "normal boot" strap
state).

**Reserved:** GPIO20/21 (UART0 RX/TX) — kept free for USB serial debugging.

---

## I2C Device Map
- MPU6050 → `0x68`
- MAX30102 → `0x57`
- Confirmed via I2C bus scan on every boot — both devices respond reliably.

---

## The BLE vs WiFi Journey (full reasoning, in order)

This project went through a full evaluation of both wireless approaches before
settling on the current architecture. Documenting the full reasoning since it
explains *why* the code looks the way it does.

1. **Started with WiFi (basic web server).** Worked, but: WiFi connect was very slow
   (many retries visible in logs, WiFi status codes 6/1 cycling before eventually
   reaching 3/connected), the ESP32 board ran noticeably hot under load, and the web
   dashboard felt sluggish/unresponsive.
2. **Diagnosed the sluggishness:** `ds18b20.requestTemperatures()` was blocking for
   ~750ms (12-bit conversion) every loop, during which `server.handleClient()`
   couldn't run — this was starving the web server, not a WiFi problem per se.
3. **Diagnosed the heat:** most likely the onboard 3.3V LDO under combined
   WiFi-TX-burst + sensor + buzzer current draw — a known risk flagged early in the
   power budget analysis (peak draw estimated ~480-500mA, right at typical Supermini
   LDO limits).
4. **Pivoted to BLE** (NimBLE-Arduino, chosen for being lighter/more reliable on the
   C3's limited RAM vs the stock Bluedroid BLE stack) to eliminate the WiFi radio
   entirely and solve both the heat and responsiveness issues at once. Also
   objectively a better fit for a battery-wearable's actual final use case
   (low power, connectionless-friendly, doesn't need a router).
5. **BLE firmware worked correctly** — confirmed via I2C-independent testing and,
   more importantly, confirmed via **Windows' own native BLE testing tool**, which
   could see the device, all 3 services, and all custom characteristics under the
   custom service UUID `12345678-1234-1234-1234-1234567890ab`.
6. **BLE connection from PC (via `bleak` in Python) consistently failed** with
   `BleakDeviceNotFoundError`, even after: unpairing from Windows Bluetooth settings
   (pairing isn't needed for GATT and can itself block the single-connection-slot
   many BLE peripherals allow), connecting by discovered device object instead of
   raw MAC string (a known WinRT backend quirk), and a multi-attempt retry loop.
7. **Root-caused to the PC's Bluetooth dongle/driver itself**, not the code: even
   Microsoft's own official BLE testing tool could enumerate the device's services
   perfectly but still reported "Device Connected: False". This is a known class of
   issue with certain cheap/generic BLE dongles having incomplete GATT connection
   handling in their Windows drivers.
8. **Confirmed BLE firmware is fully correct** by connecting via **phone (nRF
   Connect for Mobile)** — this connected successfully, proving the ESP32 side,
   NimBLE stack, and service/characteristic setup were never the problem.
9. **Decision: move the PC-side wireless link to WiFi UDP broadcast** rather than
   chase a dongle-specific driver bug further, since:
   - The PC's *built-in* WiFi adapter is far more reliable than the flaky external
     BLE dongle.
   - UDP is connectionless (no pairing, no handshake, no single-connection-slot
     issue) — fundamentally sidesteps the entire class of problem hit with BLE.
   - WiFi TX power can be deliberately reduced (`WiFi.setTxPower()`), which directly
     addresses the earlier heat concern — the original heat problem was never
     "WiFi is inherently too hot," it was "WiFi running a heavy web server at full
     default TX power."
   - A lightweight UDP broadcast has meaningfully less overhead than either a TCP
     web server *or* BLE's GATT/notification stack.
10. **Confirmed working** — UDP broadcast approach is live and tested successfully.

**Where this leaves BLE:** the NimBLE firmware logic is fully valid and kept as a
reference/option — if a proper BLE dongle (e.g. CSR8510-based) is obtained later,
or for the eventual real wearable pairing with a phone app (a phone's native BLE
stack doesn't have this dongle-driver problem), BLE remains the architecturally
correct choice for the *final* battery-powered product. WiFi-UDP is the current
practical choice specifically for **PC-based testing and data analysis**.

---

## Current Working Architecture (WiFi UDP)

- **ESP32 side:** connects to WiFi in station mode, computes the local subnet's
  broadcast address at runtime (no need to hardcode or know the PC's IP), and fires
  a UDP packet to that broadcast address every 500ms (2Hz). Also mirrors the same
  packet to USB Serial for direct debugging.
- **TX power reduced** to `WIFI_POWER_8_5dBm` (down from the default ~19.5dBm) —
  reduces current draw during transmission bursts and heat, at the cost of range;
  fine for same-room/same-network PC communication. Adjustable if range becomes an
  issue (see Known Open Issues).
- **PC side:** a Python script binds a UDP socket to port 4210 and listens for
  broadcast packets from any sender — no IP configuration needed on the PC. Every
  received packet is parsed, printed live to the terminal in human-readable form,
  and simultaneously logged as a row in a timestamped CSV file for later analysis.
- **Fully non-blocking firmware loop** — no `delay()` calls anywhere in `loop()`.
  Every subsystem (DS18B20 conversion, buzzer tone duration, LED animation steps,
  button debounce, sensor publish timing) is driven independently by comparing
  `millis()` against its own stored "last event time," so nothing stalls anything
  else.

---

## UDP Packet Format

**Transport:** UDP broadcast, port `4210`, one packet every 500ms (2Hz).
**Payload:** plain ASCII CSV string (not binary-packed — chosen for this stage
because it's directly human-readable in Wireshark/terminal without a parser, at the
small cost of a few extra bytes per packet — irrelevant at 2Hz on a local network).

**Field order:**
```
temp_c,temp_valid,hr_bpm,finger_detected,batt_v,batt_pct,button,ax,ay,az,gx,gy,gz
```

| Field | Type | Meaning |
|---|---|---|
| `temp_c` | float | Body temperature in °C from DS18B20 |
| `temp_valid` | 0/1 | Whether the last DS18B20 read was valid (see DS18B20 section) |
| `hr_bpm` | int | Computed heart rate, beats per minute (averaged over last 4 beats) |
| `finger_detected` | 0/1 | Whether MAX30102 IR reading suggests a finger is present |
| `batt_v` | float | Battery voltage, computed from ADC divider reading |
| `batt_pct` | int (0-100) | Estimated battery percentage, linear map of 3.0V-4.2V range |
| `button` | 0/1 | Live panic button state (1 = currently pressed) |
| `ax,ay,az` | float | Accelerometer, g-units |
| `gx,gy,gz` | float | Gyroscope, degrees/second |

**Example packet (raw string as sent over UDP):**
```
36.80,1,72,1,3.98,82,0,0.06,-0.02,0.97,1.23,-0.85,0.44
```

**Decoded:** body temp 36.80°C (valid reading), heart rate 72 BPM (finger detected),
battery 3.98V (82%), button not pressed, accelerometer roughly at rest (Z≈1g = flat
orientation), small gyro drift values.

---

## Software Architecture / Code Logic (in depth)

### MPU6050 — raw I2C register access (not the Adafruit library)
Early testing found that `Adafruit_MPU6050::begin()` failed even though the I2C
scanner clearly showed the device responding at `0x68`. Root cause: the Adafruit
library checks a WHO_AM_I chip-ID register, and many MPU6050 clone boards return a
slightly different ID than genuine chips, causing the library to report
"not found" despite the device being fully functional. Fix: bypass the library
entirely, talk to the chip directly:
- **Init:** write `0x00` to register `0x6B` (PWR_MGMT_1) to clear the sleep bit and
  wake the chip. A successful I2C ACK (endTransmission returns 0) is treated as
  "device present and responding."
- **Read:** point to register `0x3B` (ACCEL_XOUT_H), then read 14 consecutive bytes
  in one burst: 6 bytes accel (X/Y/Z, high+low byte each), 2 bytes internal chip
  temperature (skipped/unused), 6 bytes gyro (X/Y/Z).
- **Scaling:** accel raw / 16384.0 → g-units (default ±2g range), gyro raw / 131.0 →
  degrees/second (default ±250°/s range).
This approach is also lighter on flash/RAM than pulling in the full Adafruit_MPU6050
+ Adafruit_Sensor dependency chain.

### DS18B20 — the -127°C bug and its fix
**Symptom history:** device reliably detected (`getDeviceCount() > 0`), but every
temperature read returned exactly `-127.00`, which is DallasTemperature's
disconnected/error sentinel value.
**Root cause identified:** not wiring (confirmed correct) — the default library
behavior calls `requestTemperatures()` in **blocking mode**, which waits for the
sensor's conversion internally. In early non-blocking loop refactors, code was
structured such that temperature was sometimes read before the physical conversion
had actually completed, and/or the blocking call itself (~750ms at default 12-bit
resolution) was interfering with timing elsewhere in the loop.
**Fix implemented (current code):** explicit async state machine:
1. `ds18b20.setWaitForConversion(false)` — tells the library not to block internally.
2. A `DSState` enum (`DS_IDLE` / `DS_WAITING`) tracks where we are in the cycle.
3. Every `DS_CYCLE_INTERVAL` (3000ms — temperature changes slowly, no need to poll
   faster), fire `requestTemperatures()` (returns immediately now) and record the
   timestamp, moving to `DS_WAITING`.
4. Once `DS_CONVERSION_MS` (750ms, matching 12-bit resolution) has actually elapsed,
   read the result — by device **address** (`ds18b20.getAddress()`, captured once at
   boot) rather than by bus index, which is more reliable if any bus glitches occur.
5. **Validity check:** if the read returns `DEVICE_DISCONNECTED_C` or an
   implausible value (`<= -100`), the code does *not* overwrite the last known-good
   `bodyTemp` — it just marks `tempValid = false` for that cycle and keeps the
   previous value, avoiding spiky garbage in the data stream.
This state-machine approach is what makes DS18B20 handling genuinely non-blocking
while still respecting its real conversion-time requirement.

### MAX30102 — heart rate via beat-detection (not raw IR/RED anymore)
Earlier versions transmitted raw IR/RED counts only. Current version computes actual
BPM using SparkFun's `checkForBeat()` peak-detection algorithm (from `heartRate.h`,
bundled with the MAX3010x library):
- **Finger detection:** simple threshold — `irValue > 50000` is treated as "finger
  present" (IR reading is much higher with a finger blocking/reflecting the LED than
  with nothing in front of the sensor).
- **Beat timing:** `checkForBeat(irValue)` returns true exactly when it detects a
  new pulse peak in the waveform. Time since the *previous* detected beat
  (`millis() - lastBeat`) converts directly to instantaneous BPM: `60.0 / (delta
  seconds)`.
- **Sanity filter:** only accepts instantaneous BPM between 20-255 (rules out noise
  spikes being interpreted as physically impossible heart rates).
- **Smoothing:** keeps the last 4 valid BPM readings in a small ring buffer
  (`rates[RATE_SIZE]`) and reports the rolling average (`beatAvg`) — smooths out
  natural beat-to-beat jitter without needing a large buffer or heavy math.
- **Critical timing note:** beat detection needs to *sample* far faster than the
  ~1Hz heartbeat itself to see the waveform shape at all — so `sampleHeartRate()`
  runs on **every single loop iteration** (no rate-limiting), even though the
  *publish/broadcast* rate for the computed BPM value is still only 2Hz along with
  everything else. This was a specific design correction made after initially
  considering rate-limiting all sensor reads uniformly to 2Hz — doing that to heart
  rate specifically would have broken beat detection entirely (not just made it less
  accurate), since at 2Hz you'd get roughly one sample per heartbeat cycle, nowhere
  near enough to resolve a peak.

### Battery voltage — ADC + divider (now implemented and transmitted)
- Reads `GPIO0` (`analogRead`), which sits behind a 100kΩ/100kΩ voltage divider on
  the actual battery line.
- Converts raw ADC (0-4095, 12-bit) back to actual battery voltage:
  `(raw / 4095.0) * 3.3 * 2.0` — the `*2.0` undoes the divider's halving.
- Estimates percentage with a simple linear map between empirical LiPo empty/full
  voltages: `((voltage - 3.0) / (4.2 - 3.0)) * 100`, clamped to 0-100 with
  `constrain()`.
- No smoothing/averaging currently implemented on the raw ADC read (noted as a
  possible future improvement — ESP32 ADCs are known to be somewhat noisy).

### Passive buzzer — PWM tone generation, non-blocking
A passive buzzer needs an oscillating signal to produce sound — `digitalWrite`
HIGH/LOW does nothing audible on it (that only works on *active* buzzers, which have
their own internal oscillator). Implemented using the **old channel-based LEDC API**
(`ledcSetup` + `ledcAttachPin` + `ledcWrite` + `ledcDetachPin`) because the project
is on **ESP32 Arduino core 2.0.10**, which doesn't support the newer pin-based
`ledcAttach()`/`ledcDetach()` calls (core 3.x only) — using the wrong API version was
an earlier compile-time bug, now fixed.
**Non-blocking design:** `startBuzzer(freq, durationMs)` sets up the PWM tone and
records a start timestamp + duration, then returns immediately. A separate
`updateBuzzer()`, called every loop, checks whether the duration has elapsed and
turns the tone off at the right time — no `delay()` involved.

### WS2812B LED ring — non-blocking spinning chase animation
On button press, instead of the earlier simple "flash all red then off" (which used
blocking `delay()` calls), the current version runs a proper **non-blocking spinning
chase**:
- `startLedAnim()` resets a step counter and timestamp.
- `updateLedAnim()`, called every loop, checks if `STEP_INTERVAL` (50ms) has passed
  since the last step; if so, it clears the ring, lights the current position pixel
  full red, lights the *previous* position pixel dim red (creates a trailing "comet"
  effect), advances the step counter, and updates the display.
- Runs for `TOTAL_STEPS = NUM_LEDS * 3` steps — i.e. three full spins around the
  8-LED ring — then automatically clears and stops.
- Brightness capped at 20/255 globally via `ring.setBrightness(20)` for power/eye
  comfort.
- A startup test cycle (red→green→blue→off) also runs once at boot, independent of
  this logic, purely to confirm the ring itself is physically alive before any other
  code runs — useful as a hardware sanity check.

### Panic button — debounced, triggers both buzzer and LED together
- `INPUT_PULLUP` mode, so the resting state is HIGH and a press pulls it LOW.
- Debounce logic: only accepts a new "press" if the pin has actually transitioned
  from HIGH to LOW (edge detection, not just "currently LOW") **and** at least
  `BUTTON_DEBOUNCE` (400ms) has passed since the last trigger — prevents a single
  physical press from firing multiple times due to mechanical contact bounce.
- On a valid press: calls both `startBuzzer()` (tone duration matched to the full
  LED animation length, so they finish together) and `startLedAnim()` — both
  non-blocking, so the rest of the loop (sensor reads, UDP publish) keeps running
  normally throughout the panic animation, unlike the earlier blocking version.

### Main loop structure (current, fully non-blocking)
```
loop():
  sampleHeartRate()        <- every iteration, no rate limit (see MAX30102 section)

  if 500ms elapsed since last publish cycle:
    mpuRead()               <- fresh IMU values
    updateBattery()          <- fresh battery reading
    publishData()             <- builds CSV string, sends via UDP broadcast + Serial

  updateDS18B20()            <- advances its own async state machine if due
  checkButton()               <- edge-detects + debounces
  updateBuzzer()                <- turns off tone if duration elapsed
  updateLedAnim()                 <- advances animation if a step interval elapsed
```
Every one of these functions either does a small amount of work and returns
immediately, or checks a `millis()`-based condition and no-ops if it's not time yet
— there is no path through `loop()` that blocks for any meaningful duration,
regardless of which sensors are due to update on a given pass.

---

## Current Status (as of latest test)

| Component | Status |
|---|---|
| I2C bus (scan) | Working — 0x57 and 0x68 both detected on every boot |
| MPU6050 | Working (raw I2C register access) |
| MAX30102 | Working — real computed BPM via beat-detection, not raw counts |
| DS18B20 | **Fixed** — async state machine + validity check resolved the -127°C bug |
| WS2812B ring | Working — startup test + non-blocking spinning chase on button press |
| Passive buzzer | Working — PWM tone, non-blocking duration handling |
| Panic button | Working — debounced, triggers buzzer + LED animation together |
| Battery voltage | Working — implemented and now included in every transmitted packet |
| BLE (NimBLE) | Fully functional firmware, confirmed via phone (nRF Connect) — **not currently used for PC link** due to a PC-side BLE dongle/driver bug (see BLE vs WiFi section) |
| WiFi UDP broadcast | **Working — current primary wireless data path to PC** |
| PC-side Python (UDP listener) | Working — live terminal view + CSV logging for analysis |
| TX power | Reduced to 8.5dBm — expected to help ESP32 heat, not yet formally re-measured |
| TP4056 charging | Resolved separately, not a current concern |

---

## Known Open Issues / Next Steps
1. Re-measure ESP32 board temperature under the new UDP+reduced-TX-power code to
   confirm the heat issue is actually resolved (was diagnosed but not re-verified
   after this change).
2. Consider smoothing/averaging the battery ADC reading (currently raw, single-sample).
3. If UDP broadcast range becomes an issue as the device moves further from the
   router/PC, raise `WIFI_POWER_8_5dBm` back up incrementally (trade-off against
   heat/current draw).
4. Consider switching UDP payload from ASCII CSV to a packed binary struct if
   packet size/parsing efficiency ever becomes a concern (not currently necessary at
   2Hz on a local network, but noted as a natural next optimization).
5. BLE path is fully functional and kept as the architecturally correct option for
   the eventual real wearable-to-phone pairing (a phone's BLE stack doesn't have the
   dongle-driver problem seen on this PC) — revisit if/when a proper BLE dongle
   (e.g. CSR8510-based) is available for PC-side use, or when phone-app development
   begins.
6. No smoothing on IMU data currently — raw per-2Hz-cycle values, fine for current
   testing purposes but worth considering for motion-based features later (e.g. fall
   detection would likely want a short rolling window rather than instantaneous
   readings).

---

## Board / Toolchain Settings
- **Board:** ESP32C3 Dev Module
- **Core version:** ESP32 Arduino core 2.0.10 (affects LEDC and Wire.requestFrom API
  — see buzzer/MPU6050 sections above for the specific API differences this required)
- **USB CDC On Boot:** Must be Enabled for Serial Monitor output to appear at all
- **Serial Monitor baud:** 115200
- **Libraries used (current UDP version):** `OneWire`, `DallasTemperature`,
  `SparkFun MAX3010x Pulse and Proximity Sensor Library` (provides both `MAX30105.h`
  and `heartRate.h`), `Adafruit NeoPixel`, `WiFi` + `WiFiUdp` (built-in)
- **Libraries used (BLE version, kept for reference/future use):** same as above but
  swap `WiFi`/`WiFiUdp` for `NimBLE-Arduino` (by h2zero) — do not install both the
  stock ESP32 BLE library and NimBLE simultaneously, they conflict.
- **Note:** `Adafruit_MPU6050` and `Adafruit_Sensor` are NOT required — replaced
  entirely by raw I2C register code (see MPU6050 section).

---

## PC-Side Tooling

### `udp_dashboard.py` (current, primary tool)
- Binds a UDP socket to port 4210, listens for broadcasts from any sender.
- No IP address configuration needed — works automatically as long as the PC is on
  the same WiFi network as the ESP32.
- Parses each 13-field CSV packet, prints a human-readable live line to the
  terminal, and simultaneously writes every reading as a row (with an added local
  timestamp) into a timestamped CSV log file — created fresh each run — for later
  offline analysis (e.g. in Excel, pandas, plotting).

### `serial_dashboard.py` (fallback/USB-tethered option, superseded for the wireless
goal but kept as a known-reliable debugging path)
- Reads the same CSV format directly over USB serial via `pyserial`.
- Useful specifically when isolating whether a problem is wireless-link-related vs a
  firmware/sensor-level problem, since it has zero wireless dependency.
CONTEXTEOF