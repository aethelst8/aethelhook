$ErrorActionPreference = "Stop"

param([string]$AppDir)
if (-not $AppDir) { $AppDir = "C:\Program Files\AethelHook" }

$ServiceName = "AethelHook"
$ExePath     = "$AppDir\AethelHook.API.exe"

# Remove existing service if present
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    if ($existing.Status -eq "Running") { Stop-Service -Name $ServiceName -Force }
    Start-Sleep -Seconds 2
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 1
}

New-Service -Name $ServiceName `
    -BinaryPathName "`"$ExePath`"" `
    -DisplayName "AethelHook" `
    -StartupType Automatic `
    -Description "AI Agent Permission Gateway"

Start-Service -Name $ServiceName

# Open the phone-facing API port so the Android app can reach this PC. Profile
# "Any" (not Private-only) is deliberate: Tailscale's virtual adapter is commonly
# classified as Public/Unidentified by Windows, and phones connecting over
# Tailscale need this rule to match that profile too - the tradeoff is the port
# is also reachable from a hostile Public Wi-Fi network, relying entirely on
# TLS + per-device token auth (no network-level barrier) for protection there.
# No inbound rule for the UDP 47263 beacon - it's outbound-only (PC broadcasts,
# never listens), so no inbound allow rule is needed for it. The Remove- call
# below still cleans up that rule name in case an older install created it.
Remove-NetFirewallRule -Name "AethelHook-TCP-5264"  -ErrorAction SilentlyContinue
Remove-NetFirewallRule -Name "AethelHook-UDP-47263" -ErrorAction SilentlyContinue

New-NetFirewallRule -Name "AethelHook-TCP-5264" `
    -DisplayName "AethelHook (TCP 5264)" `
    -Direction Inbound -Protocol TCP -LocalPort 5264 `
    -Action Allow -Profile Any | Out-Null
