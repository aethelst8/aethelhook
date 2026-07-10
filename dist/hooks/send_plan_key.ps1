# AethelHook - sends the OS-level keystroke that resolves the "Accept this plan?"
# dialog, launched detached (fire-and-forget) by on_exit_plan.ps1 once a phone decision
# has arrived. This exists because Claude Code ignores a PreToolUse hook's
# permissionDecision for ExitPlanMode specifically (confirmed via live testing) and the
# dialog's buttons aren't exposed to Windows UI Automation (VS Code webview content) -
# keyboard injection into the focused window is the only remaining automation path.
#
# SAFETY: this sends a real keystroke (and, for feedback, real typed text) to whatever
# window has focus after we force-foreground VS Code. If the dialog isn't actually
# showing yet, the keystroke/text lands wherever focus actually is (e.g. an open file).
# We only proceed if a window titled with the actual triggering project's folder name
# (passed in via -WorkspaceName by on_exit_plan.ps1, derived from the hook's cwd) is
# found - never send blind, and never match on this project's own name for someone
# else's project.

param(
    [Parameter(Mandatory = $true)][string]$Key,       # "1", "2", or "3"
    [string]$FeedbackFile = "",
    [string]$WorkspaceName = "AethelHook"
)

# Feedback text arrives via a temp file, not a command-line argument - Start-Process
# -ArgumentList in Windows PowerShell 5.1 does not reliably quote array elements
# containing spaces, which would silently corrupt multi-word feedback.
$Feedback = ""
if ($FeedbackFile -and (Test-Path $FeedbackFile)) {
    $Feedback = Get-Content -Path $FeedbackFile -Raw -Encoding UTF8
    Remove-Item -Path $FeedbackFile -Force -ErrorAction SilentlyContinue
}

# Escape -like wildcard/pattern characters so a literal folder name is never
# misinterpreted as a wildcard pattern.
$script:WorkspaceName = $WorkspaceName -replace '([\[\]\*\?`])', '`$1'

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [SendPlanKey] $msg"
    try { $line | Out-File -FilePath $debugLog -Append -Encoding utf8 } catch {}
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class Win32 {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
}
"@

$script:foundWindow = [IntPtr]::Zero

function Find-WorkspaceWindow {
    $script:foundWindow = [IntPtr]::Zero
    $callback = {
        param($hWnd, $lParam)
        if ([Win32]::IsWindowVisible($hWnd)) {
            $sb = New-Object System.Text.StringBuilder 256
            [Win32]::GetWindowText($hWnd, $sb, 256) | Out-Null
            $title = $sb.ToString()
            if ($title -like "*$script:WorkspaceName*") {
                $script:foundWindow = $hWnd
                return $false
            }
        }
        return $true
    }
    [Win32]::EnumWindows($callback, [IntPtr]::Zero) | Out-Null
    return $script:foundWindow
}

# Escape SendKeys' special characters so literal feedback text types correctly.
function ConvertTo-SendKeysEscaped([string]$text) {
    $escaped = $text -replace '([+^%~(){}\[\]])', '{$1}'
    return $escaped
}

try {
    Log "--- send_plan_key.ps1 (Key=$Key) ---"

    # Give Claude Code time to actually render the dialog after the hook process exits.
    Start-Sleep -Milliseconds 800

    $hwnd = [IntPtr]::Zero
    for ($i = 0; $i -lt 5; $i++) {
        $hwnd = Find-WorkspaceWindow
        if ($hwnd -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 300
    }

    if ($hwnd -eq [IntPtr]::Zero) {
        Log "No '$WorkspaceName' window found - aborting, will not send keys blind"
        exit 0
    }

    [Win32]::SetForegroundWindow($hwnd) | Out-Null
    Start-Sleep -Milliseconds 150

    [System.Windows.Forms.SendKeys]::SendWait($Key)
    Log "Sent key '$Key'"

    if ($Key -eq "3" -and $Feedback) {
        Start-Sleep -Milliseconds 500
        # Shift focus (keyboard-only, no coordinates) before typing, in case pressing
        # "3" left keyboard focus on the persistent chat input rather than the newly
        # revealed feedback box - a stray "3" leaking into the chat box was observed
        # without this.
        [System.Windows.Forms.SendKeys]::SendWait("{TAB}")
        Start-Sleep -Milliseconds 200
        $safe = ConvertTo-SendKeysEscaped $Feedback
        [System.Windows.Forms.SendKeys]::SendWait($safe)
        Start-Sleep -Milliseconds 150
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
        Log "Sent feedback text and Enter"
    }
} catch {
    Log "Error: $_"
}
