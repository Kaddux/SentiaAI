#!/usr/bin/env python3
"""UDP -> Sentia AI ingestion bridge.

Listens for the ESP32's WiFi-UDP broadcast (port 4210, 2 Hz ASCII CSV), maps the
fields into the Sentia AI sensor schema, batches them, and POSTs to the Spring Boot
backend's /api/sensors/ingest endpoint (with a JWT), giving last-mile integration
between the wearable's current UDP path and our HTTP ingestion API.

CSV field order — accepts both wire formats (see parse_packet):
    13: temp_c,temp_valid,hr_bpm,finger_detected,batt_v,batt_pct,button,ax,ay,az,gx,gy,gz
    15: temp_c,temp_valid,hr_bpm,<aux>,finger_detected,batt_v,batt_pct,button,ax,ay,az,gx,gy,gz,<status>

Env config:
    SENTIA_BACKEND      http://localhost:8080   (default)
    SENTIA_UDP_PORT     4210                    (default)
    SENTIA_BATCH_SIZE   60                      (samples per POST)
    SENTIA_BATCH_SECS   30                      (max seconds to buffer before POST)
    SENTIA_JWT          a valid bearer token (preferred; else use SENTIA_EMAIL/PASSWORD)
    SENTIA_EMAIL        login email (used only if SENTIA_JWT unset)
    SENTIA_PASSWORD     login password (used only if SENTIA_JWT unset)
    SENTIA_SELF_TEST    1 = send one sample and exit (verifies wiring)
"""

from __future__ import annotations

import json
import os
import socket
import time
import urllib.request
from datetime import datetime, timezone

BACKEND = os.getenv("SENTIA_BACKEND", "http://localhost:8080").rstrip("/")
UDP_PORT = int(os.getenv("SENTIA_UDP_PORT", "4210"))
BATCH_SIZE = int(os.getenv("SENTIA_BATCH_SIZE", "60"))
BATCH_SECS = float(os.getenv("SENTIA_BATCH_SECS", "30"))
TELEMETRY = os.getenv("SENTIA_TELEMETRY", "1") == "1"


def login() -> str:
    email = os.getenv("SENTIA_EMAIL")
    password = os.getenv("SENTIA_PASSWORD")
    if not email or not password:
        raise SystemExit("Set SENTIA_JWT, or SENTIA_EMAIL + SENTIA_PASSWORD.")
    body = json.dumps({"email": email, "password": password}).encode()
    req = urllib.request.Request(f"{BACKEND}/api/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())["token"]


def _float(v: str):
    try:
        return float(v)
    except (ValueError, TypeError):
        return None


def _int(v: str):
    try:
        return int(float(v))  # tolerate "72.0" style
    except (ValueError, TypeError):
        return None


def parse_packet(line: str) -> dict:
    """Map a CSV packet to a backend reading dict.

    Accepts both wire formats:
        13 fields (legacy):
            temp_c,temp_valid,hr_bpm,finger,batt_v,batt_pct,button,ax,ay,az,gx,gy,gz
        15 fields (current ESP32 firmware):
            temp_c,temp_valid,hr_bpm,<aux>,finger,batt_v,batt_pct,button,ax,ay,az,gx,gy,gz,<status>
        16 fields (step-aware firmware):
            ...same...,<status>,steps_today
    The auxiliary and status fields are ignored.
    """
    parts = [p.strip() for p in line.strip().split(",")]
    steps_total = None
    if len(parts) == 13:
        temp_c, temp_valid, hr_bpm, finger, batt_v, batt_pct, button, ax, ay, az, gx, gy, gz = parts
    elif len(parts) >= 15:
        temp_c, temp_valid, hr_bpm = parts[0], parts[1], parts[2]
        finger = parts[4]
        batt_v, batt_pct, button = parts[5], parts[6], parts[7]
        ax, ay, az, gx, gy, gz = parts[8], parts[9], parts[10], parts[11], parts[12], parts[13]
        if len(parts) >= 16:
            steps_total = _int(parts[15])
    else:
        return {}

    # Finger off -> the sensor reports 0 bpm; drop that and other implausible
    # values so they can't pollute resting-HR / averages.
    hr = _int(hr_bpm)
    if (_int(finger) or 0) == 0 or hr is None or hr < 30 or hr > 220:
        hr = None

    return {
        "recordedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "temperature": _float(temp_c),
        "heartRate": hr,
        "accelX": _float(ax), "accelY": _float(ay), "accelZ": _float(az),
        "gyroX": _float(gx), "gyroY": _float(gy), "gyroZ": _float(gz),
        "batteryV": _float(batt_v),
        "batteryPct": _int(batt_pct),
        "buttonPressed": _int(button),
        "tempValid": _int(temp_valid),
        "fingerDetected": _int(finger),
        "stepsTotal": steps_total,
    }


def format_telemetry(r: dict) -> str:
    """Human-readable one-line view (like the old udp_dashboard)."""
    t = datetime.now().strftime("%H:%M:%S")
    temp = f"{r['temperature']:.2f}°C" if r.get("temperature") is not None else "--"
    hr = f"{r['heartRate']} bpm" if r.get("heartRate") is not None else "--"
    batt = f"{r['batteryV']:.2f}V ({r['batteryPct']}%)" if r.get("batteryV") is not None else "--"
    btn = "PRESSED" if r.get("buttonPressed") else "ok"
    accel = (r.get("accelX"), r.get("accelY"), r.get("accelZ"))
    gyro = (r.get("gyroX"), r.get("gyroY"), r.get("gyroZ"))
    return (f"[{t}] temp {temp} | HR {hr} | batt {batt} | btn {btn} | "
            f"accel({_fmt(accel)}) | gyro({_fmt(gyro)}) | finger={r.get('fingerDetected')}")


def _fmt(v: tuple) -> str:
    out = []
    for x in v:
        out.append("--" if x is None else f"{x:.2f}")
    return ", ".join(out)


def post_batch(jwt: str, readings: list[dict]) -> int:
    body = json.dumps({"readings": readings}).encode()
    req = urllib.request.Request(f"{BACKEND}/api/sensors/ingest", data=body, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {jwt}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())["inserted"]


def self_test(jwt: str) -> None:
    sample = {
        "recordedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "temperature": 36.5, "heartRate": 72,
        "accelX": 0.06, "accelY": -0.02, "accelZ": 0.97,
        "gyroX": 1.2, "gyroY": -0.8, "gyroZ": 0.4,
        "batteryV": 3.98, "batteryPct": 82,
        "buttonPressed": 0, "tempValid": 1, "fingerDetected": 1,
    }
    n = post_batch(jwt, [sample])
    print(f"[self-test] POST /api/sensors/ingest -> inserted {n}")


def main() -> None:
    jwt = os.getenv("SENTIA_JWT") or login()
    if os.getenv("SENTIA_SELF_TEST") == "1":
        self_test(jwt)
        return

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", UDP_PORT))  # port 4210
    print(f"[bridge] listening on UDP :{UDP_PORT} -> {BACKEND}/api/sensors/ingest "
          f"(batch={BATCH_SIZE}, max {int(BATCH_SECS)}s)")
    if TELEMETRY:
        print("[bridge] live telemetry ON (SENTIA_TELEMETRY=0 to disable, e.g. for quiet logging)\n")

    buffer: list[dict] = []
    last_post = time.monotonic()
    start_time = time.monotonic()
    total = 0

    while True:
        data, _ = sock.recvfrom(2048)
        line = data.decode("utf-8", errors="ignore")
        reading = parse_packet(line)
        if not reading:
            print(f"[bridge] skip malformed: {line!r}")
            continue
        buffer.append(reading)
        total += 1

        if TELEMETRY:
            print(format_telemetry(reading))

        if len(buffer) >= BATCH_SIZE or (time.monotonic() - last_post) >= BATCH_SECS:
            try:
                n = post_batch(jwt, buffer)
                rate = total / max(1.0, time.monotonic() - start_time)
                print(f"[bridge] POSTed {len(buffer)} readings ({n} inserted) | "
                      f"{total} total @ {rate:.1f} pkt/s | "
                      f"{datetime.now(timezone.utc).isoformat()}")
            except Exception as exc:                      # noqa: BLE001
                print(f"[bridge] ERROR posting {len(buffer)} readings: {exc}")
            buffer = []
            last_post = time.monotonic()


if __name__ == "__main__":
    main()
