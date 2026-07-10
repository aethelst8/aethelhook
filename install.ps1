#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"

$ServiceName   = "AethelHook"
$InstallDir    = "C:\Program Files\AethelHook"
$ProjectDir    = "$PSScriptRoot\AethelHook.API"
$ExePath       = "$InstallDir\AethelHook.API.exe"
$TrayProjectDir = "$PSScriptRoot\AethelHook.Tray"
$TrayInstallDir = "$InstallDir\Tray"
$TrayExePath    = "$TrayInstallDir\AethelHook.Tray.exe"
$StartupDir     = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup"
$StartupShortcut = "$StartupDir\AethelHook Tray.lnk"

Write-Host ""
Write-Host "=== AethelHook Service Installer ===" -ForegroundColor Cyan
Write-Host ""

# 1. Stop and remove old service FIRST so the install dir isn't locked during publish
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Stopping existing service..." -ForegroundColor Yellow
    if ($existing.Status -eq "Running") { Stop-Service -Name $ServiceName -Force }
    Start-Sleep 3
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep 2
    Write-Host "Old service removed." -ForegroundColor Green
}

# 2. Build self-contained release executable (install dir is now unlocked)
Write-Host "Building self-contained release exe..." -ForegroundColor Yellow
dotnet publish "$ProjectDir\AethelHook.API.csproj" -c Release -r win-x64 --self-contained true -o $InstallDir
if ($LASTEXITCODE -ne 0) { Write-Error "Build failed. Aborting."; exit 1 }

# 4. Register the Windows Service
Write-Host "Registering Windows Service..." -ForegroundColor Yellow
New-Service -Name $ServiceName `
    -BinaryPathName "`"$ExePath`"" `
    -DisplayName "AethelHook" `
    -StartupType Automatic `
    -Description "AI Agent Permission Gateway"

# 5. Start it
Write-Host "Starting service..." -ForegroundColor Yellow
Start-Service -Name $ServiceName

# 6. Publish the tray app (kill it first - it's a desktop-session process, not the
#    service, so it isn't stopped by anything above and would lock the install dir)
Write-Host "Stopping tray app (if running)..." -ForegroundColor Yellow
Get-Process -Name "AethelHook.Tray" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep 1

Write-Host "Building tray app..." -ForegroundColor Yellow
dotnet publish "$TrayProjectDir\AethelHook.Tray.csproj" -c Release -r win-x64 --self-contained true -o $TrayInstallDir
if ($LASTEXITCODE -ne 0) { Write-Error "Tray app build failed. Aborting."; exit 1 }

# 7. (Re)create the Startup-folder shortcut so the tray app launches at next login
Write-Host "Registering tray app to launch at login..." -ForegroundColor Yellow
if (Test-Path $StartupShortcut) { Remove-Item $StartupShortcut -Force }
$wshell = New-Object -ComObject WScript.Shell
$shortcut = $wshell.CreateShortcut($StartupShortcut)
$shortcut.TargetPath = $TrayExePath
$shortcut.WorkingDirectory = $TrayInstallDir
$shortcut.Description = "AethelHook tray app"
$shortcut.Save()

# 8. Launch it now too, so this session shows the tray icon immediately
Start-Process -FilePath $TrayExePath

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor Green
Write-Host "Service '$ServiceName' is installed and running." -ForegroundColor Green
Write-Host "Tray app is installed and will launch automatically at login." -ForegroundColor Green
Write-Host ""
Write-Host "Logs:    C:\ProgramData\AethelHook\api.log"
Write-Host "Status:  Get-Service AethelHook"
Write-Host "Stop:    Stop-Service AethelHook"
Write-Host "Remove:  .\uninstall.ps1"
Write-Host ""
