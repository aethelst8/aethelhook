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

# Open firewall ports so the Android app can reach this PC on the LAN
Remove-NetFirewallRule -Name "AethelHook-TCP-5264"  -ErrorAction SilentlyContinue
Remove-NetFirewallRule -Name "AethelHook-UDP-47263" -ErrorAction SilentlyContinue

New-NetFirewallRule -Name "AethelHook-TCP-5264" `
    -DisplayName "AethelHook (TCP 5264)" `
    -Direction Inbound -Protocol TCP -LocalPort 5264 `
    -Action Allow -Profile Any | Out-Null

New-NetFirewallRule -Name "AethelHook-UDP-47263" `
    -DisplayName "AethelHook Beacon (UDP 47263)" `
    -Direction Inbound -Protocol UDP -LocalPort 47263 `
    -Action Allow -Profile Any | Out-Null
