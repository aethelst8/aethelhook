# AethelHook AfterAgent Hook - Gemini CLI edition
# Fires once per turn after the model's final response. Deliberately NOT also wired to
# SessionEnd/Stop - confirmed live those fire ALONGSIDE AfterAgent for every headless
# `-p` run (the process exits right after its one turn), which produced a duplicate
# "Gemini finished" notification per prompt when this script was wired to all three -
# see RestoreGeminiHooks()'s own comment. AfterAgent alone reliably covers both the
# headless single-turn case and an interactive session's per-turn case.
#
# Real field name for the response text confirmed live 2026-07-22 (see hook_debug_
# geminicli.log): "prompt_response". Also carries the original "prompt" text and
# "stop_hook_active", neither used here. The other field names below are kept as a
# defensive fallback only, in case a future Gemini CLI version renames this.

$debugLog = "C:\ProgramData\AethelHook\hook_debug_geminicli.log"
function Log($msg) {
    try { "$(Get-Date -Format 'HH:mm:ss') [Done] $msg" | Out-File -FilePath $debugLog -Append -Encoding utf8 -ErrorAction SilentlyContinue } catch {}
}

Log "AfterAgent hook fired"

$summary = ""
$cwd     = $null
try {
    $reader   = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $readTask = $reader.ReadToEndAsync()
    if ($readTask.Wait(3000)) {
        $raw = $readTask.Result
        if ($raw) {
            Log "stdin: $raw"
            $data = $raw | ConvertFrom-Json
            if ($data.cwd) { $cwd = $data.cwd }

            # Live-verified 2026-07-22 via hook_debug_geminicli.log: the real field is
            # "prompt_response" - none of the 4 originally-guessed names
            # (response/last_message/message/text) ever matched, so every AfterAgent
            # notification silently had no summary until this fix.
            $rawSummary = $null
            if ($data.prompt_response)         { $rawSummary = $data.prompt_response }
            elseif ($data.response)            { $rawSummary = $data.response }
            elseif ($data.last_message)        { $rawSummary = $data.last_message }
            elseif ($data.message)             { $rawSummary = $data.message }
            elseif ($data.text)                { $rawSummary = $data.text }

            if ($rawSummary -and "$rawSummary".Trim()) {
                $summary = "$rawSummary".Trim()
                $summary = $summary -replace '\*\*(.+?)\*\*',          '$1'
                $summary = $summary -replace '\*([^*\r\n]+)\*',        '$1'
                $summary = $summary -replace '`([^`]+)`',               '$1'
                $summary = $summary -replace '(?m)^#{1,6}\s+',          ''
                $summary = $summary -replace '\[([^\]]+)\]\([^\)]+\)',  '$1'
                $summary = $summary.Trim()
                if ($summary.Length -gt 4000) { $summary = $summary.Substring(0, 4000) + "..." }
                Log "Got summary ($($summary.Length) chars)"
            }
        }
    }
} catch { Log "stdin read failed: $_" }

$notifyBody = @{ message = "Gemini finished" }
if ($summary) { $notifyBody.detail = $summary }
if ($cwd) { $notifyBody.cwd = $cwd }

# Dispatch to a fully detached process, same reasoning as Codex's notify_async.ps1 -
# this hook's own synchronous work must not include a network round-trip, since
# SessionEnd-class events explicitly are not waited on by the CLI (per Gemini CLI's own
# docs) and a slow/unreachable API could otherwise eat this process's remaining budget.
try {
    $json        = $notifyBody | ConvertTo-Json -Compress
    $payloadPath = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($payloadPath, $json, [System.Text.Encoding]::UTF8)

    $notifierScript = "C:\ProgramData\AethelHook\hooks\geminicli\notify_async.ps1"
    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", $notifierScript, $payloadPath) `
        -WindowStyle Hidden
    Log "Notification dispatched async (summary: $(if ($summary) { 'yes' } else { 'no' }))"
} catch {
    Log "Failed to dispatch async notification: $_"
}

exit 0
