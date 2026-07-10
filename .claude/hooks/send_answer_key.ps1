# AethelHook - sends the OS-level keystrokes that resolve a single-question,
# single-select AskUserQuestion dialog, launched detached (fire-and-forget) by
# on_ask_question.ps1 once a phone answer has arrived. Same reason this exists as
# send_plan_key.ps1: Claude Code's PreToolUse hookSpecificOutput/updatedInput no longer
# suppresses the native dialog (a Claude Code update between 2.1.198 and 2.1.199 seems
# to have changed this) - the interactive UI still renders and a real PC interaction
# would otherwise silently override the phone's answer.
#
# Navigation model (confirmed via live testing 2026-07-03): the first option is
# highlighted by default with no key press; Down arrow moves the highlight one option
# at a time; Space selects the highlighted option; Enter submits.
#
# SCOPE: only handles a single question with a single-select answer that matches one of
# the predefined options (not "Other" free text, not multi-select, not multiple
# questions) - on_ask_question.ps1 only launches this script for that case.
#
# SAFETY: same as send_plan_key.ps1 - this sends real keystrokes to whatever window has
# focus after force-foregrounding VS Code. We only proceed if a window titled with the
# actual triggering project's folder name (passed in via -WorkspaceName by
# on_ask_question.ps1, derived from the hook's cwd) is found - never send blind, and
# never match on this project's own name for someone else's project.

param(
    [Parameter(Mandatory = $true)][int]$OptionIndex,
    [string]$WorkspaceName = "AethelHook"
)

# Escape -like wildcard/pattern characters so a literal folder name is never
# misinterpreted as a wildcard pattern.
$script:WorkspaceName = $WorkspaceName -replace '([\[\]\*\?`])', '`$1'

$debugLog = "C:\ProgramData\AethelHook\hook_debug.log"
function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss') [SendAnswerKey] $msg"
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

try {
    Log "--- send_answer_key.ps1 (OptionIndex=$OptionIndex) ---"

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

    for ($i = 0; $i -lt $OptionIndex; $i++) {
        [System.Windows.Forms.SendKeys]::SendWait("{DOWN}")
        Start-Sleep -Milliseconds 120
    }
    Log "Moved highlight down $OptionIndex time(s)"

    [System.Windows.Forms.SendKeys]::SendWait(" ")
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
    Log "Sent Space + Enter"
} catch {
    Log "Error: $_"
}
