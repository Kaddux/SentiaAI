# Sentia AI — UDP -> backend ingestion bridge for Windows (no Python required).
#
# Listens for the ESP32's UDP broadcast (default :4210), maps the 13- or 15-field
# CSV into the Sentia AI sensor schema, batches, and POSTs to
# POST /api/sensors/ingest with a JWT. Host-side equivalent of
# udp_ingest_bridge.py.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\udp_ingest_bridge.ps1
#   powershell -ExecutionPolicy Bypass -File .\udp_ingest_bridge.ps1 -Email me@x.com -Password secret
#
param(
    [string]$Backend   = "http://localhost:8080",
    [int]$UdpPort      = 4210,
    [int]$BatchSize    = 30,
    [int]$BatchSecs    = 4,
    [string]$Email     = "dev@sentia.ai",
    [string]$Password  = "password123",
    [string]$Jwt       = "",
    [bool]$Telemetry   = $true
)

$ErrorActionPreference = "Stop"
$inv = [Globalization.CultureInfo]::InvariantCulture

# Single instance per user session.
$mutex = New-Object System.Threading.Mutex($false, "Local\SentiaUdpBridge")
if (-not $mutex.WaitOne(0)) {
    Write-Host "[bridge] already running - exiting"
    exit 0
}

function ConvertTo-Number([string]$s, [string]$kind) {
    if ([string]::IsNullOrWhiteSpace($s)) { return $null }
    try {
        if ($kind -eq "int") { return [int][double]::Parse($s, $inv) }
        return [double]::Parse($s, $inv)
    } catch { return $null }
}

function Get-Token {
    if ($Jwt) { return $Jwt }
    $body = @{ email = $Email; password = $Password } | ConvertTo-Json -Compress
    while ($true) {
        try {
            $res = Invoke-RestMethod -Method Post -Uri "$Backend/api/auth/login" -ContentType "application/json" -Body $body
            return $res.token
        } catch {
            Write-Host "[bridge] backend not ready ($($_.Exception.Message)); retrying in 5s"
            Start-Sleep -Seconds 5
        }
    }
}

function ConvertFrom-Packet([string]$line) {
    $p = $line.Trim().Split(",")
    $stepsTotal = $null
    if ($p.Length -eq 13) {
        $temp = $p[0]; $tValid = $p[1]; $hrRaw = $p[2]; $finger = $p[3]; $battV = $p[4]
        $battPct = $p[5]; $button = $p[6]; $ax = $p[7]; $ay = $p[8]; $az = $p[9]; $gx = $p[10]; $gy = $p[11]; $gz = $p[12]
    } elseif ($p.Length -ge 15) {
        $temp = $p[0]; $tValid = $p[1]; $hrRaw = $p[2]; $finger = $p[4]; $battV = $p[5]
        $battPct = $p[6]; $button = $p[7]; $ax = $p[8]; $ay = $p[9]; $az = $p[10]; $gx = $p[11]; $gy = $p[12]; $gz = $p[13]
        if ($p.Length -ge 16) { $stepsTotal = ConvertTo-Number $p[15] "int" }
    } else {
        return $null
    }

    $fingerN = ConvertTo-Number $finger "int"
    $hr = ConvertTo-Number $hrRaw "int"
    if ($fingerN -eq 0 -or $null -eq $hr -or $hr -lt 30 -or $hr -gt 220) { $hr = $null }  # finger off / implausible pulse

    return @{
        recordedAt     = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.ffffffZ")
        temperature    = ConvertTo-Number $temp "dbl"
        tempValid      = ConvertTo-Number $tValid "int"
        heartRate      = $hr
        fingerDetected = $fingerN
        batteryV       = ConvertTo-Number $battV "dbl"
        batteryPct     = ConvertTo-Number $battPct "int"
        buttonPressed  = ConvertTo-Number $button "int"
        accelX         = ConvertTo-Number $ax "dbl"
        accelY         = ConvertTo-Number $ay "dbl"
        accelZ         = ConvertTo-Number $az "dbl"
        gyroX          = ConvertTo-Number $gx "dbl"
        gyroY          = ConvertTo-Number $gy "dbl"
        gyroZ          = ConvertTo-Number $gz "dbl"
        stepsTotal     = $stepsTotal
    }
}

function Send-Batch([System.Collections.ArrayList]$batch, [string]$token) {
    if ($batch.Count -eq 0) { return 0 }
    $body = @{ readings = @($batch) } | ConvertTo-Json -Depth 6 -Compress
    $res = Invoke-RestMethod -Method Post -Uri "$Backend/api/sensors/ingest" -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer $token" } -Body $body
    return [int]$res.inserted
}

$token = Get-Token
$udp = New-Object System.Net.Sockets.UdpClient
# Exclusive bind = single instance machine-wide (a second bridge fails to bind
# and exits; the launcher restarts it only when this one is gone).
$udp.ExclusiveAddressUse = $true
$udp.Client.Bind([System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, $UdpPort))
$udp.Client.ReceiveTimeout = 1000

Write-Host "[bridge] listening on UDP :$UdpPort -> $Backend/api/sensors/ingest (batch=$BatchSize, max ${BatchSecs}s)"

$batch = New-Object System.Collections.ArrayList
$lastPost = Get-Date
$total = 0
$inserted = 0

try {
    while ($true) {
        try {
            $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $data = $udp.Receive([ref]$ep)
            $reading = ConvertFrom-Packet ([Text.Encoding]::ASCII.GetString($data))
        } catch { $reading = $null }

        if ($null -ne $reading) {
            [void]$batch.Add($reading)
            $total++
            if ($Telemetry) {
                $hr = if ($null -eq $reading.heartRate) { "--" } else { "$($reading.heartRate) bpm" }
                Write-Host ("[{0}] temp {1}C | HR {2} | batt {3}V ({4}%) | finger={5}" -f `
                    (Get-Date).ToString("HH:mm:ss"), $reading.temperature, $hr, $reading.batteryV, $reading.batteryPct, $reading.fingerDetected)
            }
        }

        if ($batch.Count -ge $BatchSize -or ((Get-Date) - $lastPost).TotalSeconds -ge $BatchSecs) {
            if ($batch.Count -gt 0) {
                try {
                    $n = Send-Batch $batch $token
                    $inserted += $n
                    Write-Host "[bridge] POSTed $($batch.Count) readings ($n inserted) | total $total"
                    $batch.Clear()
                } catch {
                    Write-Host "[bridge] ERROR posting: $($_.Exception.Message)"
                    $code = $null
                    try { $code = [int]$_.Exception.Response.StatusCode } catch { }
                    if ($code -eq 401) {
                        Write-Host "[bridge] JWT expired - refreshing"
                        $token = Get-Token
                    }
                    # keep the buffer; retry on the next tick
                }
                $lastPost = Get-Date
            }
        }
    }
} finally {
    $udp.Close()
    Write-Host "[bridge] stopped (captured=$total inserted=$inserted)"
}
