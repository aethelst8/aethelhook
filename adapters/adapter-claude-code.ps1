# AethelHook Adapter - Claude Code
# Block format: {"decision":"block","reason":"..."} + exit 2
# Allow format: exit 0

. (Join-Path $PSScriptRoot "..\core\AethelHook-Core.ps1")
$r = Invoke-AethelHookCore

switch ($r.Decision) {
    { $_ -in "allow", "allow_once" } {
        exit 0
    }
    { $_ -in "always_allow_project", "always_allow_global" } {
        "$($r.CmdName)" | Out-File -FilePath $r.PhoneAllowPath -Append -ErrorAction SilentlyContinue
        exit 0
    }
    "deny_with_reason" {
        $reason = if ($r.Reason) { $r.Reason } else { "User declined via phone" }
        $escaped = $reason -replace '"', '\"'
        Write-Output "{`"decision`":`"block`",`"reason`":`"$escaped`"}"
        exit 2
    }
    "deny" {
        Write-Output '{"decision":"block","reason":"Denied via phone"}'
        exit 2
    }
    default {
        Write-Output '{"decision":"block","reason":"No phone response (timed out)"}'
        exit 2
    }
}
