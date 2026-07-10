# AethelHook Windows Service Uninstaller
# Run as Administrator.

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"

$ServiceName     = "AethelHook"
$InstallDir      = "C:\Program Files\AethelHook"
$StartupShortcut = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\AethelHook Tray.lnk"

# AethelHook-managed entries in Claude Code settings
$ClaudeSettings  = "$env:USERPROFILE\.claude\settings.json"
$AethelAllowList = @("PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)")

Write-Host ""
Write-Host "=== AethelHook Service Uninstaller ===" -ForegroundColor Cyan
Write-Host ""

# ── 1. Stop and remove the Windows service ───────────────────────────────────
$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Host "Service '$ServiceName' is not installed." -ForegroundColor Yellow
} else {
    if ($svc.Status -eq "Running") {
        Write-Host "Stopping service..." -ForegroundColor Yellow
        Stop-Service -Name $ServiceName -Force
        Start-Sleep 2
    }
    Write-Host "Removing service..." -ForegroundColor Yellow
    sc.exe delete $ServiceName | Out-Null
    Write-Host "Service removed." -ForegroundColor Green
}

# ── 2. Stop the tray app and remove its Startup-folder shortcut ──────────────
Get-Process -Name "AethelHook.Tray" -ErrorAction SilentlyContinue | Stop-Process -Force
if (Test-Path $StartupShortcut) {
    Remove-Item $StartupShortcut -Force
    Write-Host "Removed tray app Startup shortcut." -ForegroundColor Green
}

# ── 3. Delete the install directory (includes the tray app's Tray\ subfolder) ─
if (Test-Path $InstallDir) {
    Write-Host "Deleting install directory $InstallDir..." -ForegroundColor Yellow
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "Deleted." -ForegroundColor Green
}

# ── 4. Remove hooks and permissions from Claude Code settings.json ───────────
if (Test-Path $ClaudeSettings) {
    Write-Host "Restoring Claude Code settings..." -ForegroundColor Yellow
    try {
        $raw      = Get-Content $ClaudeSettings -Raw -Encoding utf8
        $settings = $raw | ConvertFrom-Json

        # Remove the hooks block entirely
        if ($settings.PSObject.Properties['hooks']) {
            $settings.PSObject.Properties.Remove('hooks')
            Write-Host "  Removed hooks block." -ForegroundColor Gray
        }

        # Remove only AethelHook-managed entries from permissions.allow
        if ($settings.PSObject.Properties['permissions'] -and
            $settings.permissions.PSObject.Properties['allow']) {
            $before = @($settings.permissions.allow)
            $after  = $before | Where-Object { $AethelAllowList -notcontains $_ }
            $settings.permissions.allow = $after
            $removed = $before.Count - $after.Count
            if ($removed -gt 0) {
                Write-Host "  Removed $removed AethelHook permission(s) from allow list." -ForegroundColor Gray
            }
            # Drop empty permissions block so settings stay clean
            if ($after.Count -eq 0) {
                $settings.PSObject.Properties.Remove('permissions')
            }
        }

        $settings | ConvertTo-Json -Depth 10 | Out-File $ClaudeSettings -Encoding utf8 -Force
        Write-Host "Claude Code settings restored. Native dialogs will reappear." -ForegroundColor Green
    } catch {
        Write-Host "  Warning: could not update $ClaudeSettings — $_" -ForegroundColor Yellow
        Write-Host "  Remove the 'hooks' block manually to restore native dialogs." -ForegroundColor Yellow
    }
} else {
    Write-Host "Claude Code settings.json not found — nothing to restore." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "AethelHook uninstalled." -ForegroundColor Green
Write-Host "Logs remain at C:\ProgramData\AethelHook\api.log (delete manually if desired)."
Write-Host ""
