$ErrorActionPreference = "SilentlyContinue"

$ServiceName = "AethelHook"

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    if ($existing.Status -eq "Running") { Stop-Service -Name $ServiceName -Force }
    Start-Sleep -Seconds 2
    sc.exe delete $ServiceName | Out-Null
}

Remove-NetFirewallRule -Name "AethelHook-TCP-5264"  -ErrorAction SilentlyContinue
Remove-NetFirewallRule -Name "AethelHook-UDP-47263" -ErrorAction SilentlyContinue
