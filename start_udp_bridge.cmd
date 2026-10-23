@echo off
REM Launcher + supervisor for the Sentia AI UDP -> backend bridge.
REM Restarts the bridge if it exits (crash, device/network hiccup, etc.).
setlocal
cd /d "%~dp0"
if not exist "%~dp0logs" mkdir "%~dp0logs"

:loop
powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0udp_ingest_bridge.ps1" >> "%~dp0logs\udp_bridge.log" 2>&1
REM ping is non-interactive (timeout.exe fails without a console).
ping -n 6 127.0.0.1 >nul
goto loop
