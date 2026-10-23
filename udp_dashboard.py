"""
Wearable UDP Dashboard - receives wireless sensor data broadcast from ESP32
No Bluetooth needed - uses your PC's normal WiFi, same network as the ESP32.
Install: nothing extra needed, socket + csv are built into Python.

Logs every reading to a timestamped CSV file for further analysis, and prints
a live view to the terminal.
"""

import socket
import csv
import time
from datetime import datetime

UDP_PORT = 4210
LOG_FILE = f"wearable_log_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"

HEADER = ["timestamp", "temp_c", "temp_valid", "hr_bpm", "finger_detected",
          "batt_v", "batt_pct", "button", "ax", "ay", "az", "gx", "gy", "gz"]

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", UDP_PORT))  # listen on all interfaces for broadcast packets

    print(f"Listening for UDP broadcasts on port {UDP_PORT}...")
    print(f"Logging to {LOG_FILE}")
    print("Waiting for first packet (make sure ESP32 is powered and on the same WiFi)...\n")

    with open(LOG_FILE, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)

        while True:
            try:
                data, addr = sock.recvfrom(1024)
                line = data.decode('utf-8', errors='ignore').strip()
                parts = line.split(',')

                if len(parts) != 13:
                    print(f"[unexpected packet from {addr[0]}]: {line}")
                    continue

                temp, tempValid, hr, finger, battV, battPct, btn, ax, ay, az, gx, gy, gz = parts
                timestamp = datetime.now().isoformat()

                writer.writerow([timestamp] + parts)
                f.flush()

                print(
                    f"T:{float(temp):6.2f}C({'OK' if int(tempValid) else 'ERR'})  "
                    f"HR:{hr:>3}({'finger' if int(finger) else 'no-finger'})  "
                    f"BAT:{float(battV):.2f}V/{battPct}%  "
                    f"BTN:{'PRESSED' if int(btn) else 'idle'}  "
                    f"Acc:[{float(ax):5.2f},{float(ay):5.2f},{float(az):5.2f}]  "
                    f"Gyro:[{float(gx):6.2f},{float(gy):6.2f},{float(gz):6.2f}]  "
                    f"from {addr[0]}"
                )
            except KeyboardInterrupt:
                print(f"\nStopped. Data saved to {LOG_FILE}")
                break
            except (ValueError, UnicodeDecodeError):
                continue

if __name__ == "__main__":
    main()
