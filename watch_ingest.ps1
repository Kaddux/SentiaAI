# Live view of Sentia AI ingestion.
#   - streams new lines from the UDP bridge log (per-packet telemetry + POSTs)
#   - every few seconds prints today's row count and the newest timestamp
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\watch_ingest.ps1
#   powershell -ExecutionPolicy Bypass -File .\watch_ingest.ps1 -Tail 40 -PollSecs 3
#
param(
    [int]$Tail = 25,
    [int]$PollSecs = 5,
    [string]$Container = "sentiaai-postgres-1",
    [string]$DbUser = "health_user",
    [string]$Database = "health_assistant"
)

$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $root "logs\udp_bridge.log"

if (-not (Test-Path $log)) {
    Write-Host "No log yet at $log" -ForegroundColor Yellow
    Write-Host "Start the bridge:  powershell -ExecutionPolicy Bypass -File .\udp_ingest_bridge.ps1"
    return
}

Write-Host "== Sentia AI live ingestion ==" -ForegroundColor Cyan
Write-Host "log: $log   (Ctrl+C to stop)" -ForegroundColor DarkGray
Write-Host ""

$tailer = Start-Job -ScriptBlock {
    param($p, $n)
    Get-Content -Path $p -Tail $n -Wait
} -ArgumentList $log, $Tail

try {
    while ($true) {
        Receive-Job $tailer
        $row = docker exec $Container psql -U $DbUser -d $Database -t -A -F ' | ' -c `
            "SELECT 'today_readings=' || count(*), 'latest=' || COALESCE(max(recorded_at)::text,'-') FROM sensor_readings WHERE recorded_at::date = CURRENT_DATE;" 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $row) {
            Write-Host "  [db] Docker/Postgres not reachable" -ForegroundColor DarkYellow
        } else {
            Write-Host ("  [db] " + $row.Trim()) -ForegroundColor DarkGray
        }
        Start-Sleep -Seconds $PollSecs
    }
} finally {
    Stop-Job $tailer -ErrorAction SilentlyContinue
    Remove-Job $tailer -Force -ErrorAction SilentlyContinue
}
