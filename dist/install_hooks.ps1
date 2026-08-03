# Called by the installer to wire AethelHook hooks into Claude Code settings.json,
# Codex's hooks.json, OpenCode's opencode.json, Gemini CLI's settings.json,
# GitHub Copilot CLI's own hooks folder, and Devin CLI's config.json.
# Receives the actual user's profile path as $args[0] (passed by Inno Setup {userprofile}).
# Mirrors AethelHook.API\Program.cs's RestoreClaudeCodeHooks()/RestoreCodexHooks()/
# RestoreOpenCodeHooks()/RestoreGeminiHooks()/RestoreCopilotHooks()/RestoreDevinHooks() -
# keep all in sync. This script runs once
# at install time, using the real installing user's profile (reliable even under UAC
# elevation); the Program.cs functions re-apply on every service start, but run as the
# SYSTEM service account, which can't reliably resolve the real user's profile on a
# truly fresh machine with no existing .claude/.codex/.config\opencode/.gemini folder -
# this bootstrap step is what makes first install work before those folders exist.
# For Gemini CLI specifically, this script does NOT also pre-trust the folder in
# trustedFolders.json - RunHeadlessGeminiPromptAsync in Program.cs does that itself,
# per-project, at the point a headless run actually needs it, since there's no single
# "the" project directory to trust at install time.
#
# Antigravity is NOT configured here (2026-07-13): its approval gate has an
# unresolved deny-bypass issue, so AethelHook no longer ships/enables it for new
# installs. The implementation (Program.cs's RestoreAntigravityHooks(), the
# .gemini\hooks\*.ps1 scripts, ANTIGRAVITY_HOOKS.md) is left in the repo untouched
# in case it gets fixed later, it just isn't wired into a fresh install anymore.

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

# --- OpenCode: C:\Users\<user>\.config\opencode\opencode.json (global scope) ---
# Architecturally different from the other three - OpenCode's hook mechanism is a JS
# plugin loaded into its own process (registered via this config's "plugin" array), not
# a PowerShell script invoked per event. Confirmed live (2026-07-13): OpenCode follows
# XDG-style config conventions even on Windows (.config\opencode, not .opencode).
$openCodeConfigDir  = "$UserProfile\.config\opencode"
$openCodeConfigPath = "$openCodeConfigDir\opencode.json"
$openCodePluginPath = "$hooksDir\opencode\aethelhook-plugin.js" -replace '\\', '/'

New-Item -ItemType Directory -Force -Path $openCodeConfigDir | Out-Null
$openCodeConfig = [PSCustomObject]@{
    '$schema' = "https://opencode.ai/config.json"
    plugin    = @($openCodePluginPath)
}
$openCodeConfig | ConvertTo-Json -Depth 10 | Out-File $openCodeConfigPath -Encoding utf8NoBOM -Force

# --- Gemini CLI: C:\Users\<user>\.gemini\settings.json (global scope) ---
# Merge-preserving, not overwrite-whole-file - confirmed live this settings.json can
# already hold real content (e.g. a "permissions" block from prior Antigravity IDE use,
# which shares the same .gemini directory but writes its own separate hooks.json under
# .gemini\config\, so there's no file collision). Matcher "*" on BeforeTool/AfterTool is
# deliberate (see RestoreGeminiHooks()'s own comment) - broad by default so any tool a
# future Gemini CLI version adds is still gated, with the couple of pure-bookkeeping
# tools exempted inside on_before_tool.ps1 itself. SessionEnd is deliberately NOT wired
# here either - confirmed live it fires alongside AfterAgent for every headless `-p`
# run, which would double up the "Gemini finished" notification.
$geminiDir          = "$UserProfile\.gemini"
$geminiSettingsPath = "$geminiDir\settings.json"
$geminiBeforeToolCmd = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\geminicli\on_before_tool.ps1"
$geminiAfterToolCmd  = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\geminicli\on_after_tool.ps1"
$geminiAfterAgentCmd = "powershell.exe -ExecutionPolicy Bypass -File $hooksDir\geminicli\on_after_agent.ps1"

New-Item -ItemType Directory -Force -Path $geminiDir | Out-Null

$geminiSettings = if (Test-Path $geminiSettingsPath) {
    try { Get-Content $geminiSettingsPath -Raw -Encoding utf8 | ConvertFrom-Json }
    catch { [PSCustomObject]@{} }
} else { [PSCustomObject]@{} }

$geminiHooksBlock = [PSCustomObject]@{
    BeforeTool = @([PSCustomObject]@{
        matcher = "*"
        hooks   = @([PSCustomObject]@{ type = "command"; command = $geminiBeforeToolCmd })
    })
    AfterTool = @([PSCustomObject]@{
        matcher = "*"
        hooks   = @([PSCustomObject]@{ type = "command"; command = $geminiAfterToolCmd })
    })
    AfterAgent = @([PSCustomObject]@{
        hooks = @([PSCustomObject]@{ type = "command"; command = $geminiAfterAgentCmd })
    })
}

if (-not $geminiSettings.PSObject.Properties['hooks']) {
    $geminiSettings | Add-Member -NotePropertyName 'hooks' -NotePropertyValue $geminiHooksBlock
} else {
    $geminiSettings.hooks = $geminiHooksBlock
}

$geminiSettings | ConvertTo-Json -Depth 10 | Out-File $geminiSettingsPath -Encoding utf8NoBOM -Force

# --- GitHub Copilot CLI: C:\Users\<user>\.copilot\hooks\aethelhook-hooks.json ---
# Approval-gate-only (2026-07-22, dropped headless Session Access - see
# Program.cs's /hook/send-prompt rejection for "copilot") - permissionRequest +
# agentStop only, same scope as Antigravity's own approval-gate-only precedent.
# Simplest of all five agents' hook config otherwise - personal hooks are standalone
# *.json files dropped into ~/.copilot/hooks/, each independent, so no
# read-merge-preserve step is needed here at all, just write our own
# distinctly-named file. timeoutSec 100 on permissionRequest leaves headroom above
# on_permission_request.ps1's own 90s phone-wait budget - Copilot's hook timeout
# fails OPEN (allows the action) rather than denying, the opposite of every other
# agent here, so this script must always finish first.
$copilotHooksDir  = "$UserProfile\.copilot\hooks"
$copilotHooksPath = "$copilotHooksDir\aethelhook-hooks.json"
$copilotPermissionCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File $hooksDir\copilot\on_permission_request.ps1"
$copilotAgentStopCmd  = "powershell -NoProfile -ExecutionPolicy Bypass -File $hooksDir\copilot\on_agent_stop.ps1"

New-Item -ItemType Directory -Force -Path $copilotHooksDir | Out-Null
$copilotHooks = [PSCustomObject]@{
    version         = 1
    disableAllHooks = $false
    hooks           = [PSCustomObject]@{
        permissionRequest = @([PSCustomObject]@{ type = "command"; powershell = $copilotPermissionCmd; timeoutSec = 100 })
        agentStop         = @([PSCustomObject]@{ type = "command"; powershell = $copilotAgentStopCmd; timeoutSec = 10 })
    }
}
$copilotHooks | ConvertTo-Json -Depth 10 | Out-File $copilotHooksPath -Encoding utf8NoBOM -Force

# --- Devin CLI: C:\Users\<user>\AppData\Roaming\devin\config.json ---
# Standalone terminal CLI only (interactive `devin` or headless `devin -p`) - NOT the
# Devin IDE/Desktop app, which runs Devin in ACP mode as an editor subprocess and never
# fires hooks at all (permission decisions are handed to the connected client instead,
# confirmed live 2026-07-22 - see Program.cs's RestoreDevinHooks() for the full
# investigation). Merge-preserving, not overwrite-whole-file - this config.json also
# holds real "permissions"/model prefs/an "org_id" Devin CLI itself writes after login.
# PreToolUse alone is enough for the gate - confirmed live it fires unconditionally
# regardless of --permission-mode, including "dangerous" (auto-approve-everything), so
# no separate PermissionRequest hook is needed. "timeout": 100 leaves headroom above
# on_pre_tool_use.ps1's own 90s phone-wait budget - Devin's hook timeout fails OPEN
# (does not block) if exceeded, same danger as Copilot's hook.
#
# Stop is a plain done-notification (added after discovering Devin's own Stop event
# was incidentally cross-firing Claude Code's own Stop hook via Devin's default
# read_config_from.claude import behavior - see the Claude Code on_agent_done.ps1 fix
# for that). on_stop.ps1 never emits a "decision" field - Stop is one of the events
# that CAN block ("prevent premature stopping"), and a stray block here would risk a
# doom-loop, the same class of bug already hit and fixed for OpenCode (gotcha #28).
$devinConfigDir  = "$UserProfile\AppData\Roaming\devin"
$devinConfigPath = "$devinConfigDir\config.json"
$devinPreToolCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File $hooksDir\devincli\on_pre_tool_use.ps1"
$devinStopCmd    = "powershell -NoProfile -ExecutionPolicy Bypass -File $hooksDir\devincli\on_stop.ps1"

New-Item -ItemType Directory -Force -Path $devinConfigDir | Out-Null

$devinConfig = if (Test-Path $devinConfigPath) {
    try { Get-Content $devinConfigPath -Raw -Encoding utf8 | ConvertFrom-Json }
    catch { [PSCustomObject]@{} }
} else { [PSCustomObject]@{} }

$devinPreToolUse = @([PSCustomObject]@{
    matcher = ""
    hooks   = @([PSCustomObject]@{ type = "command"; command = $devinPreToolCmd; timeout = 100 })
})
$devinStop = @([PSCustomObject]@{
    matcher = ""
    hooks   = @([PSCustomObject]@{ type = "command"; command = $devinStopCmd; timeout = 15 })
})

if (-not $devinConfig.PSObject.Properties['hooks']) {
    $devinConfig | Add-Member -NotePropertyName 'hooks' -NotePropertyValue ([PSCustomObject]@{ PreToolUse = $devinPreToolUse; Stop = $devinStop })
} else {
    if (-not $devinConfig.hooks.PSObject.Properties['PreToolUse']) {
        $devinConfig.hooks | Add-Member -NotePropertyName 'PreToolUse' -NotePropertyValue $devinPreToolUse
    } else {
        $devinConfig.hooks.PreToolUse = $devinPreToolUse
    }
    if (-not $devinConfig.hooks.PSObject.Properties['Stop']) {
        $devinConfig.hooks | Add-Member -NotePropertyName 'Stop' -NotePropertyValue $devinStop
    } else {
        $devinConfig.hooks.Stop = $devinStop
    }
}

$devinConfig | ConvertTo-Json -Depth 10 | Out-File $devinConfigPath -Encoding utf8NoBOM -Force

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
