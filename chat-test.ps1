# Chat self-test for Sentia AI (NVIDIA NIM).
# Requires: docker compose up -d (all services healthy).
# Usage:  powershell -File chat-test.ps1 ["your question"]
param([string]$Question = "How was my sleep last night? Compare with my baseline.")

$tmp = Join-Path $env:TEMP "sentiaai_test"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$login = '{"email":"dev@sentia.ai","password":"password123"}'
Set-Content -LiteralPath "$tmp\login.json" -Value $login -Encoding utf8
$chat = @{ message = $Question; conversation_id = $null } | ConvertTo-Json -Compress
Set-Content -LiteralPath "$tmp\chat.json" -Value $chat -Encoding utf8

Write-Host "== 1) login =="
$reg = curl.exe -s -X POST "http://localhost:8080/api/auth/login" -H "Content-Type: application/json" --data-binary "@$tmp\login.json"
$token = ($reg | ConvertFrom-Json).token
if (-not $token) { Write-Host "LOGIN FAILED: $reg"; exit 1 }
Write-Host "   token ok"

Write-Host "== 2) chat (NVIDIA, may take 10-40s on first call) =="
curl.exe -s -w "`n[HTTP %{http_code} in %{time_total}s]`n" `
  -X POST "http://localhost:8000/api/assistant/chat" `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $token" `
  --data-binary "@$tmp\chat.json"

Write-Host "`n== 3) tips =="
Write-Host "   - If HTTP 502: check your NVIDIA_API_KEY is set in .env (a real nvapi-/nim- key)."
Write-Host "   - Ask about sleep/heart-rate/activity -> the agent fetches real data via tools."
Write-Host "   - Try: 'Create a reminder to stretch at 5pm' to exercise action tools."
