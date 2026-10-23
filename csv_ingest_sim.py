#!/usr/bin/env python3
"""Replay a wearable CSV into Sentia AI's ingestion API.

Reads the PC-logged CSV (header + timestamp + 13 sensor fields), maps each row to
the backend ReadingDto shape, and batch-POSTs to  POST /api/sensors/ingest  with a
JWT. The file's timestamp column is treated as UTC and used as recordedAt.

Usage:
    SENTIA_EMAIL=u@x.com SENTIA_PASSWORD=pass python csv_ingest_sim.py <csv>
    SENTIA_JWT=eyJ...                          python csv_ingest_sim.py <csv>

Env:
    SENTIA_BACKEND    http://localhost:8080 (default)
    SENTIA_BATCH      100 (default) readings per POST
    SENTIA_JWT        preferred; else SENTIA_EMAIL + SENTIA_PASSWORD (auto-login)
    SENTIA_SHIFT_TO_NOW  1 = shift timestamps so the last sample lands on today,
                         N minutes before now (so the data shows in "today" views).
                         SENTIA_END_MINUTES_AGO (default 45) controls the offset.
"""

from __future__ import annotations

import csv
import json
import os
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

BACKEND = os.getenv("SENTIA_BACKEND", "http://localhost:8080").rstrip("/")
BATCH = int(os.getenv("SENTIA_BATCH", "100"))
SHIFT_TO_NOW = os.getenv("SENTIA_SHIFT_TO_NOW", "0") == "1"
END_MINUTES_AGO = int(os.getenv("SENTIA_END_MINUTES_AGO", "45"))


def login() -> str:
    email = os.getenv("SENTIA_EMAIL")
    password = os.getenv("SENTIA_PASSWORD")
    if not email or not password:
        raise SystemExit("Set SENTIA_JWT, or SENTIA_EMAIL + SENTIA_PASSWORD.")
    req = urllib.request.Request(
        f"{BACKEND}/api/auth/login",
        data=json.dumps({"email": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())["token"]


def _f(v: str):
    try:
        return float(v)
    except (ValueError, TypeError):
        return None


def _i(v: str):
    try:
        return int(float(v))
    except (ValueError, TypeError):
        return None


def parse_ts(v: str) -> datetime | None:
    try:
        return datetime.fromisoformat(str(v).strip().replace(" ", "T"))
    except (ValueError, TypeError):
        return None


def row_to_reading(row: dict, shift: timedelta) -> dict:
    ts = parse_ts(row.get("timestamp", ""))
    if ts is None:
        return {}
    ts = ts + shift
    return {
        "recordedAt": ts.isoformat() + "Z",
        "temperature": _f(row.get("temp_c")),
        "heartRate": _i(row.get("hr_bpm")),
        "accelX": _f(row.get("ax")),
        "accelY": _f(row.get("ay")),
        "accelZ": _f(row.get("az")),
        "gyroX": _f(row.get("gx")),
        "gyroY": _f(row.get("gy")),
        "gyroZ": _f(row.get("gz")),
        "batteryV": _f(row.get("batt_v")),
        "batteryPct": _i(row.get("batt_pct")),
        "buttonPressed": _i(row.get("button")),
        "tempValid": _i(row.get("temp_valid")),
        "fingerDetected": _i(row.get("finger_detected")),
    }


def post_batch(jwt: str, readings: list[dict]) -> int:
    req = urllib.request.Request(
        f"{BACKEND}/api/sensors/ingest",
        data=json.dumps({"readings": readings}).encode(),
        method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {jwt}"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())["inserted"]


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: csv_ingest_sim.py <csv-file>")
    jwt = os.getenv("SENTIA_JWT") or login()

    raw_rows = []
    with open(sys.argv[1], newline="", encoding="utf-8") as fh:
        raw_rows = list(csv.DictReader(fh))   # header row consumed automatically

    # Optional: shift so the last sample lands ~END_MINUTES_AGO before now (today),
    # so the data is visible in the app's "today" views and forms closed windows.
    shift = timedelta(0)
    if SHIFT_TO_NOW and raw_rows:
        last = parse_ts(raw_rows[-1].get("timestamp", ""))
        if last is not None:
            target = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(minutes=END_MINUTES_AGO)
            shift = target - last
            print(f"[sim] shifting timestamps by {shift} (last sample -> {target.isoformat()}Z)")

    rows = []
    for row in raw_rows:
        readings = row_to_reading(row, shift)
        if readings:
            rows.append(readings)

    total = 0
    print(f"[sim] {len(rows)} readings -> {BACKEND}/api/sensors/ingest (batch={BATCH})")
    for i in range(0, len(rows), BATCH):
        batch = rows[i:i + BATCH]
        n = post_batch(jwt, batch)
        total += n
        print(f"[sim] POSTed {len(batch)} -> inserted {n} (running {total})")
    print(f"[sim] DONE: {total}/{len(rows)} inserted")


if __name__ == "__main__":
    main()
