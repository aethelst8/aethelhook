# Called by the uninstaller to remove AethelHook hooks from Claude Code settings.json.
# Receives the actual user's profile path as $args[0] (passed by Inno Setup {userprofile}).

param([string]$UserProfile)
if (-not $UserProfile) { $UserProfile = $env:USERPROFILE }

$ErrorActionPreference = "SilentlyContinue"

$settingsPath = "$UserProfile\.claude\settings.json"
$aethelAllow  = @("PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)", "Bash(*)")

if (-not (Test-Path $settingsPath)) { exit 0 }

try {
    $s = Get-Content $settingsPath -Raw -Encoding utf8 | ConvertFrom-Json

    # Remove hooks section entirely
    if ($s.PSObject.Properties['hooks']) {
        $s.PSObject.Properties.Remove('hooks')
    }

    # Remove only AethelHook permissions, preserve any others
    if ($s.PSObject.Properties['permissions'] -and
        $s.permissions.PSObject.Properties['allow']) {
        $remaining = @($s.permissions.allow | Where-Object { $aethelAllow -notcontains $_ })
        if ($remaining.Count -gt 0) {
            $s.permissions | Add-Member -NotePropertyName 'allow' -NotePropertyValue $remaining -Force
        } else {
            $s.PSObject.Properties.Remove('permissions')
        }
    }

    $s | ConvertTo-Json -Depth 10 | Out-File $settingsPath -Encoding utf8NoBOM -Force
} catch {}
