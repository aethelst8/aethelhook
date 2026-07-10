# AethelHook Extension Schema Watchdog
# Fires on SessionStart. Detects Claude Code VS Code extension version changes,
# new hook events introduced by the schema, and Claude tools that AethelHook
# isn't currently gating (matcher drift). Fire-and-forget: never blocks the session.

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [SchemaWatch] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
}

try {
    $baselineDir = "C:\ProgramData\AethelHook\schema_baseline"
    if (-not (Test-Path $baselineDir)) { New-Item -ItemType Directory -Path $baselineDir -Force | Out-Null }

    $versionFile    = Join-Path $baselineDir "version.txt"
    $eventsFile     = Join-Path $baselineDir "hook_events.txt"
    $knownToolsFile = Join-Path $baselineDir "known_tools.txt"
    $ungatedFile    = Join-Path $baselineDir "ungated_snapshot.txt"

    # Manually-maintained list of Claude tool names seen to date.
    # Update this when a session's tool list surfaces something new.
    if (-not (Test-Path $knownToolsFile)) {
        @(
            "Agent","Artifact","AskUserQuestion","Bash","CronCreate","CronDelete","CronList",
            "DesignSync","Edit","EnterPlanMode","EnterWorktree","ExitPlanMode","ExitWorktree",
            "Glob","Grep","Monitor","NotebookEdit","PowerShell","PushNotification","Read",
            "RemoteTrigger","ReportFindings","ScheduleWakeup","SendMessage","Skill","TaskOutput",
            "TaskStop","TodoWrite","Workflow","WebFetch","WebSearch","Write"
        ) | Set-Content -Path $knownToolsFile -Encoding utf8
        Log "Seeded known tool list"
    }

    # Locate the installed extension (newest if more than one is present).
    $extDir = Get-ChildItem "$env:USERPROFILE\.vscode\extensions" -Directory -Filter "anthropic.claude-code-*" -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending | Select-Object -First 1

    if (-not $extDir) {
        Log "Claude Code extension not found under .vscode/extensions - skipping"
        exit 0
    }

    $version     = ($extDir.Name -replace '^anthropic\.claude-code-', '') -replace '-win32-x64$', ''
    $prevVersion = if (Test-Path $versionFile) { (Get-Content $versionFile -Raw).Trim() } else { $null }

    $schemaPath    = Join-Path $extDir.FullName "claude-code-settings.schema.json"
    $currentEvents = @()
    if (Test-Path $schemaPath) {
        try {
            $schema = Get-Content $schemaPath -Raw | ConvertFrom-Json
            $currentEvents = @($schema.properties.hooks.propertyNames.anyOf[0].enum)
        } catch { Log "Failed to parse schema: $_" }
    }

    $prevEvents = if (Test-Path $eventsFile) { @(Get-Content $eventsFile) } else { @() }
    $newEvents  = @($currentEvents | Where-Object { $prevEvents -notcontains $_ })

    # Which tools does AethelHook actually gate today?
    $settingsPath = "$env:USERPROFILE\.claude\settings.json"
    $gatedTools = @()
    if (Test-Path $settingsPath) {
        try {
            $s = Get-Content $settingsPath -Raw -Encoding utf8 | ConvertFrom-Json
            $gatedTools = @($s.hooks.PreToolUse | Where-Object { $_.matcher } | ForEach-Object { $_.matcher })
        } catch { Log "Failed to parse settings.json: $_" }
    }

    $knownTools = @(Get-Content $knownToolsFile)
    $ungated    = @($knownTools | Where-Object { $gatedTools -notcontains $_ })
    $prevUngated = if (Test-Path $ungatedFile) { @(Get-Content $ungatedFile) } else { @() }
    $newlyUngated = @($ungated | Where-Object { $prevUngated -notcontains $_ })

    $versionChanged = $prevVersion -and ($prevVersion -ne $version)

    if ($versionChanged -or $newEvents.Count -gt 0 -or $newlyUngated.Count -gt 0) {
        $parts = @()
        if ($versionChanged)          { $parts += "Extension updated $prevVersion -> $version." }
        if ($newEvents.Count -gt 0)   { $parts += "New hook events: $($newEvents -join ', ')." }
        if ($newlyUngated.Count -gt 0) { $parts += "Ungated tools: $($newlyUngated -join ', ')." }
        $detail = $parts -join " "
        Log $detail

        $tokenPath   = "C:\ProgramData\AethelHook\api_token.txt"
        $apiToken    = if (Test-Path $tokenPath) { (Get-Content $tokenPath -Raw -Encoding ascii).Trim() } else { "" }
        $authHeaders = if ($apiToken) { @{"X-AethelHook-Token" = $apiToken} } else { @{} }
        $notifyBody  = @{ message = "AethelHook: Claude Code schema changed"; detail = $detail } | ConvertTo-Json -Compress
        try {
            Invoke-WebRequest -Uri "http://localhost:5266/hook/notify" -Method POST `
                -ContentType "application/json; charset=utf-8" -Headers $authHeaders `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($notifyBody)) -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop | Out-Null
            Log "Notification sent"
        } catch { Log "Notify failed (API offline?): $_" }
    } else {
        Log "No drift detected (version $version)"
    }

    # Update baselines for next run
    $version | Set-Content -Path $versionFile -Encoding utf8
    if ($currentEvents.Count -gt 0) { $currentEvents | Set-Content -Path $eventsFile -Encoding utf8 }
    $ungated | Set-Content -Path $ungatedFile -Encoding utf8
} catch {
    Log "Unhandled error: $_"
}

exit 0
