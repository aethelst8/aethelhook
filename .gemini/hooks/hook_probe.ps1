# Minimal diagnostic probe - writes stdin to log then exits 0 (allow).
$raw = [System.IO.StreamReader]::new([Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8).ReadToEnd()
"$(Get-Date -Format 'HH:mm:ss') ANTIGRAVITY_HOOK_FIRED stdin=$raw" |
    Out-File "C:\ProgramData\AethelHook\hook_debug.log" -Append -Encoding ascii
exit 0
