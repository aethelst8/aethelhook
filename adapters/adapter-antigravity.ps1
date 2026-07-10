# AethelHook Adapter - Antigravity IDE
# Block format: {"hookSpecificOutput":{"permissionDecision":"deny"}} + exit 2
# Also auto-dismisses Antigravity's native permission dialog via SendKeys.

. (Join-Path $PSScriptRoot "..\core\AethelHook-Core.ps1")
$r = Invoke-AethelHookCore

# Load WinForms for SendKeys auto-dismiss
try {
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class AethelWin {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@ -ErrorAction SilentlyContinue
} catch {}

function AutoDismiss($keys) {
    try {
        $ideProc = Get-Process | Where-Object {
            $_.MainWindowHandle -ne [IntPtr]::Zero -and
            $_.MainWindowTitle  -ne "" -and
            ($_.MainWindowTitle -match "Antigravity" -or $_.ProcessName -match "antigravity")
        } | Select-Object -First 1
        if ($ideProc) {
            [AethelWin]::ShowWindow($ideProc.MainWindowHandle, 9) | Out-Null
            Start-Sleep -Milliseconds 300
            [AethelWin]::SetForegroundWindow($ideProc.MainWindowHandle) | Out-Null
            Start-Sleep -Milliseconds 300
            [System.Windows.Forms.SendKeys]::SendWait($keys)
        }
    } catch {}
}

switch ($r.Decision) {
    { $_ -in "allow", "allow_once" } {
        AutoDismiss "1{ENTER}"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$($r.CmdName)" | Out-File -FilePath $r.PhoneAllowPath -Append -ErrorAction SilentlyContinue
        AutoDismiss "1{ENTER}"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"allow"}}'
        exit 0
    }
    "deny_with_reason" {
        $reason = if ($r.Reason) { $r.Reason } else { "User declined via phone" }
        $escaped = $reason -replace '"', '\"'
        AutoDismiss "{ESC}"
        Write-Output "{`"hookSpecificOutput`":{`"permissionDecision`":`"deny`",`"permissionDecisionReason`":`"$escaped`"}}"
        exit 2
    }
    "deny" {
        AutoDismiss "{ESC}"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"Denied via phone"}}'
        exit 2
    }
    default {
        AutoDismiss "{ESC}"
        Write-Output '{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"No phone response (timed out)"}}'
        exit 2
    }
}
