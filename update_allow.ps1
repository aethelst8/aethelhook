$configPath = "C:\Users\Moloi\.gemini\config\projects\877e3233-7f8c-4d86-9c2f-590ded0b4ae5.json"
$config = Get-Content $configPath -Raw | ConvertFrom-Json

# Add wildcard command allow and common commands
$newRules = @(
    "command(*)",
    "command(echo)",
    "command(powershell.exe)",
    "command(Set-Content)",
    "command(Write-Output)"
)

foreach ($rule in $newRules) {
    if ($config.permissionGrants.permissionGrants.allow -notcontains $rule) {
        $config.permissionGrants.permissionGrants.allow += $rule
    }
}

$config | ConvertTo-Json -Depth 10 | Set-Content $configPath -Encoding UTF8
Write-Output "Done. Allow list updated."
