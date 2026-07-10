# Called by the installer to wire AethelHook hooks into Claude Code settings.json,
# Codex's hooks.json, and Antigravity's global hooks.json.
# Receives the actual user's profile path as $args[0] (passed by Inno Setup {userprofile}).
# Mirrors AethelHook.API\Program.cs's RestoreClaudeCodeHooks()/RestoreCodexHooks()/
# RestoreAntigravityHooks() — keep all in sync. This script runs once at install time,
# using the real installing user's profile (reliable even under UAC elevation); the
# Program.cs functions re-apply on every service start, but run as the SYSTEM service
# account, which can't reliably resolve the real user's profile on a truly fresh
# machine with no existing .claude/.codex/.gemini folder — this bootstrap step is what
# makes first install work before those folders exist.

param([string]$UserProfile)
if (-not $UserProfile) { $UserProfile = $env:USERPROFILE }

$ErrorActionPreference = "SilentlyContinue"

$claudeDir    = "$UserProfile\.claude"
$settingsPath = "$claudeDir\settings.json"
$hooksDir     = "C:\ProgramData\AethelHook\hooks"

function Cmd($script) { "powershell.exe -ExecutionPolicy Bypass -Command `"& '$hooksDir\$script'`"" }

$hookCmd         = Cmd "on_approval_request.ps1"
$doneCmd         = Cmd "on_agent_done.ps1"
$askQCmd         = Cmd "on_ask_question.ps1"
$exitPlanCmd     = Cmd "on_exit_plan.ps1"
$sessionStartCmd = Cmd "on_session_start.ps1"
$toolDoneCmd     = Cmd "on_tool_done.ps1"

New-Item -ItemType Directory -Force -Path $claudeDir | Out-Null

# Load existing settings or start fresh
$settings = if (Test-Path $settingsPath) {
    try { Get-Content $settingsPath -Raw -Encoding utf8 | ConvertFrom-Json }
    catch { [PSCustomObject]@{} }
} else { [PSCustomObject]@{} }

# Build PreToolUse entries for each gated tool
$matchers = @(
    "Write", "Edit", "Read", "NotebookEdit", "CronCreate", "CronDelete",
    "WebFetch", "WebSearch", "Bash", "PowerShell"
)
$preToolUse  = $matchers | ForEach-Object {
    [PSCustomObject]@{
        matcher = $_
        hooks   = @([PSCustomObject]@{ type = "command"; command = $hookCmd })
    }
}
$preToolUse += [PSCustomObject]@{
    matcher = "AskUserQuestion"
    hooks   = @([PSCustomObject]@{ type = "command"; command = $askQCmd })
}
$preToolUse += [PSCustomObject]@{
    matcher = "ExitPlanMode"
    hooks   = @([PSCustomObject]@{ type = "command"; command = $exitPlanCmd })
}

$sessionStartHooks = @([PSCustomObject]@{
    hooks = @([PSCustomObject]@{ type = "command"; command = $sessionStartCmd })
})
$postToolUseHooks = @([PSCustomObject]@{
    hooks = @([PSCustomObject]@{ type = "command"; command = $toolDoneCmd })
})
$stopHooks = @([PSCustomObject]@{
    hooks = @([PSCustomObject]@{ type = "command"; command = $doneCmd })
})

# Merge into settings (overwrite any existing AethelHook hooks)
if (-not $settings.PSObject.Properties['hooks']) {
    $settings | Add-Member -NotePropertyName 'hooks' -NotePropertyValue ([PSCustomObject]@{})
}
$settings.hooks | Add-Member -NotePropertyName 'SessionStart' -NotePropertyValue $sessionStartHooks -Force
$settings.hooks | Add-Member -NotePropertyName 'PreToolUse'   -NotePropertyValue $preToolUse         -Force
$settings.hooks | Add-Member -NotePropertyName 'PostToolUse'  -NotePropertyValue $postToolUseHooks   -Force
$settings.hooks | Add-Member -NotePropertyName 'Stop'         -NotePropertyValue $stopHooks          -Force

# Merge permissions.allow (add ours, keep any existing user permissions)
$aethelAllow = @("PowerShell(*)", "Write(*)", "Edit(*)", "Read(*)", "Bash(*)")
if (-not $settings.PSObject.Properties['permissions']) {
    $settings | Add-Member -NotePropertyName 'permissions' -NotePropertyValue ([PSCustomObject]@{})
}
$existing = if ($settings.permissions.PSObject.Properties['allow']) { $settings.permissions.allow } else { @() }
$merged   = ($existing + $aethelAllow) | Select-Object -Unique
$settings.permissions | Add-Member -NotePropertyName 'allow' -NotePropertyValue $merged -Force

$settings | ConvertTo-Json -Depth 10 | Out-File $settingsPath -Encoding utf8NoBOM -Force

# --- Codex: C:\Users\<user>\.codex\hooks.json ---
$codexDir       = "$UserProfile\.codex"
$codexHooksPath = "$codexDir\hooks.json"
$codexApprovalCmd = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\codex\on_approval_request.ps1"
$codexDoneCmd     = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\codex\on_agent_done.ps1"

New-Item -ItemType Directory -Force -Path $codexDir | Out-Null
$codexHooks = [PSCustomObject]@{
    hooks = [PSCustomObject]@{
        PreToolUse = @(
            [PSCustomObject]@{ matcher = "Bash"; hooks = @([PSCustomObject]@{ type = "command"; command = $codexApprovalCmd; timeout = 90 }) }
            [PSCustomObject]@{ matcher = "apply_patch"; hooks = @([PSCustomObject]@{ type = "command"; command = $codexApprovalCmd; timeout = 90 }) }
        )
        Stop = @([PSCustomObject]@{ hooks = @([PSCustomObject]@{ type = "command"; command = $codexDoneCmd; timeout = 30 }) })
    }
}
$codexHooks | ConvertTo-Json -Depth 10 | Out-File $codexHooksPath -Encoding utf8NoBOM -Force

# --- Antigravity: C:\Users\<user>\.gemini\config\hooks.json (global scope) ---
$geminiConfigDir = "$UserProfile\.gemini\config"
$geminiHooksPath = "$geminiConfigDir\hooks.json"
$geminiApprovalCmd = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\gemini\on_approval_request.ps1"
$geminiDoneCmd     = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\gemini\on_task_complete.ps1"

New-Item -ItemType Directory -Force -Path $geminiConfigDir | Out-Null
function GeminiMatcherHook($matcher) {
    [PSCustomObject]@{ matcher = $matcher; hooks = @([PSCustomObject]@{ type = "command"; command = $geminiApprovalCmd; timeout = 90 }) }
}
$geminiDoneHook = @([PSCustomObject]@{ hooks = @([PSCustomObject]@{ type = "command"; command = $geminiDoneCmd; timeout = 5 }) })
$geminiHooks = [PSCustomObject]@{
    hooks = [PSCustomObject]@{
        PreToolUse = @(
            GeminiMatcherHook "run_command"
            GeminiMatcherHook "write_file"
            GeminiMatcherHook "replace_file_content"
            GeminiMatcherHook "multi_replace_file_content"
            GeminiMatcherHook "write_to_file"
        )
        SessionEnd = $geminiDoneHook
        AfterAgent = $geminiDoneHook
        Stop       = $geminiDoneHook
    }
}
$geminiHooks | ConvertTo-Json -Depth 10 | Out-File $geminiHooksPath -Encoding utf8NoBOM -Force

# --- VS Code keybinding: claude-vscode.focus -> Ctrl+Alt+Shift+F9 (Phase 2: Session
# Access). send_prompt_to_session.ps1 sends this exact combo to reliably route focus
# to the Claude Code chat input via VS Code's own keybinding dispatch, instead of a
# blind {TAB} guess. Merge-don't-clobber: never touch the file if it fails to parse
# (keybindings.json commonly has // comments, which ConvertFrom-Json rejects) - a
# missing binding just means send_prompt_to_session.ps1 has no effect, which is safe;
# corrupting the user's existing keybindings is not.
try {
    $vscodeUserDir     = "$UserProfile\AppData\Roaming\Code\User"
    $keybindingsPath   = "$vscodeUserDir\keybindings.json"
    $focusBinding      = [PSCustomObject]@{ key = "ctrl+alt+shift+f9"; command = "claude-vscode.focus" }

    New-Item -ItemType Directory -Force -Path $vscodeUserDir | Out-Null

    $bindings = @()
    $shouldWrite = $true
    if (Test-Path $keybindingsPath) {
        $raw = Get-Content $keybindingsPath -Raw -Encoding utf8
        if ($raw -and $raw.Trim()) {
            $stripped = ($raw -split "`n" | Where-Object { $_.Trim() -notlike "//*" }) -join "`n"
            try {
                $parsed = $stripped | ConvertFrom-Json
                $bindings = @($parsed)
                if ($bindings | Where-Object { $_.command -eq "claude-vscode.focus" }) {
                    $shouldWrite = $false  # already bound - leave as-is
                }
            } catch {
                $shouldWrite = $false  # can't safely parse - don't risk clobbering
            }
        }
    }

    if ($shouldWrite) {
        $bindings += $focusBinding
        # ConvertTo-Json unwraps a single-element array into a bare object in
        # Windows PowerShell 5.1 - keybindings.json requires a JSON array even with
        # one entry, so force it back into array form if that happened.
        $json = $bindings | ConvertTo-Json -Depth 5
        if ($json -notmatch '^\s*\[') { $json = "[$json]" }
        $json | Out-File $keybindingsPath -Encoding utf8NoBOM -Force
    }
} catch { }
