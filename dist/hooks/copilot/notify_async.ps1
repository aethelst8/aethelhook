# AethelHook agentStop Hook - detached notifier (GitHub Copilot CLI edition)
# Launched via Start-Process by on_agent_stop.ps1 so a slow/unreachable API can never
# make the calling hook itself run long. Mirrors the other three agents'
# notify_async.ps1 exactly.
param([string]$PayloadPath)

$debugLog = "C:\ProgramData\AethelHook\hook_debug_copilot.log"
function Log($msg) {
    "$(Get-Date -Format 'HH:mm:ss') [Done-async] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue
}

try {
    $json = Get-Content -Path $PayloadPath -Raw -Encoding UTF8
    Remove-Item -Path $PayloadPath -ErrorAction SilentlyContinue

    $tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
    $apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
    $authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }

    $jsonBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    Invoke-WebRequest `
        -Uri "http://localhost:5266/hook/notify" `
        -Method POST `
        -ContentType "application/json; charset=utf-8" `
        -Headers $authHeaders `
        -Body $jsonBytes `
        -TimeoutSec 10 `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
    Log "Notification sent OK (async)"
} catch {
    Log "API not reachable (async) - skipping: $_"
}
