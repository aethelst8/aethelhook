#define AppName "AethelHook"
#define AppVersion "1.2"
#define AppPublisher "AethelSt8"

[Setup]
AppId={{A3F2E1D0-7B4C-4E9A-8F3D-2C6B1A5E9F0D}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\AethelHook
DisableDirPage=yes
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=AethelHook-Setup
Compression=lzma2/ultra64
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
SetupMutex=AethelHookSetupMutex
SetupIconFile=dist\aethelhook.ico
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\aethelhook.ico
MinVersion=10.0.17763

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=Welcome to AethelHook
WelcomeLabel2=This will install AethelHook on your computer.%n%nAethelHook routes AI agent tool calls to your Android phone for approval - keeping you in full control of what your AI can do.%n%nClick Next to continue.
FinishedHeadingLabel=AethelHook is ready
FinishedLabel=The AethelHook service is installed and running.%n%nNext step: Install the APK on your Android phone, then connect to the same Wi-Fi as this PC. The app will auto-discover this PC within seconds.

[Files]
Source: "dist\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "dist\publish-tray\*"; DestDir: "{app}\Tray"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "dist\hooks\on_approval_request.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\on_agent_done.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\on_ask_question.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\on_exit_plan.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\on_session_start.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\send_plan_key.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\send_answer_key.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\on_tool_done.ps1"; DestDir: "{commonappdata}\AethelHook\hooks"; Flags: ignoreversion
Source: "dist\hooks\codex\on_approval_request.ps1"; DestDir: "{commonappdata}\AethelHook\hooks\codex"; Flags: ignoreversion
Source: "dist\hooks\codex\on_agent_done.ps1"; DestDir: "{commonappdata}\AethelHook\hooks\codex"; Flags: ignoreversion
Source: "dist\hooks\codex\notify_async.ps1"; DestDir: "{commonappdata}\AethelHook\hooks\codex"; Flags: ignoreversion
Source: "dist\hooks\opencode\aethelhook-plugin.js"; DestDir: "{commonappdata}\AethelHook\hooks\opencode"; Flags: ignoreversion
Source: "dist\install_hooks.ps1"; DestDir: "{tmp}"; Flags: ignoreversion deleteafterinstall
Source: "dist\uninstall_hooks.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\aethelhook.ico"; DestDir: "{app}"; Flags: ignoreversion

[Dirs]
; Deliberately NOT users-full here - this folder holds the TLS private key
; (aethelhook-cert.pfx) and every paired device's auth token (devices.json).
; Granting the whole folder to Users would let any other local Windows account
; read both straight off disk, no admin rights needed. Program.cs additionally
; hardens each sensitive file to Administrators+SYSTEM only as it's written;
; the only thing the interactive user actually needs read+execute on is the
; hooks scripts themselves, granted explicitly below.
Name: "{commonappdata}\AethelHook"
Name: "{commonappdata}\AethelHook\hooks"; Permissions: users-readexec

[Icons]
Name: "{userstartup}\AethelHook Tray"; Filename: "{app}\Tray\AethelHook.Tray.exe"; WorkingDir: "{app}\Tray"

[Run]
; Clean up any previous installation (safe to fail on fresh install)
Filename: "{sys}\sc.exe"; Parameters: "stop AethelHook"; Flags: runhidden waituntilterminated
Filename: "{sys}\sc.exe"; Parameters: "delete AethelHook"; Flags: runhidden waituntilterminated
; Register and start the service using sc.exe directly - no PowerShell needed
Filename: "{sys}\sc.exe"; Parameters: "create AethelHook binPath= ""{app}\AethelHook.API.exe"" start= auto DisplayName= ""AethelHook"""; StatusMsg: "Installing AethelHook service..."; Flags: runhidden waituntilterminated
Filename: "{sys}\sc.exe"; Parameters: "start AethelHook"; Flags: runhidden waituntilterminated
; Open the phone-facing API port. Profile "Any" (rather than Private-only) is
; deliberate: Tailscale's virtual adapter is commonly classified by Windows as
; Public/Unidentified, and phones connecting over Tailscale need this rule to
; match that profile too. The tradeoff is the port is also reachable from a
; hostile Public Wi-Fi network the PC happens to be on, relying entirely on
; TLS + per-device token auth (no network-level barrier) for protection there.
; Note: no inbound rule for the UDP 47263 beacon - it's outbound-only (PC
; broadcasts, never listens), so no inbound allow rule is needed for it.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NonInteractive -NoProfile -ExecutionPolicy Bypass -Command ""Remove-NetFirewallRule -Name 'AethelHook-TCP-5264' -ErrorAction SilentlyContinue; New-NetFirewallRule -Name 'AethelHook-TCP-5264' -DisplayName 'AethelHook (TCP 5264)' -Direction Inbound -Protocol TCP -LocalPort 5264 -Action Allow -Profile Any | Out-Null"""; StatusMsg: "Configuring firewall..."; Flags: runhidden
; Wire Claude Code hooks
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NonInteractive -NoProfile -ExecutionPolicy Bypass -File ""{tmp}\install_hooks.ps1"" ""{%USERPROFILE}"""; StatusMsg: "Configuring Claude Code hooks..."; Flags: runhidden
; Launch the tray app now (as the invoking user, not the elevated installer) so it's visible immediately
Filename: "{app}\Tray\AethelHook.Tray.exe"; Flags: nowait postinstall skipifsilent runasoriginaluser; Description: "Launch AethelHook Tray"

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM AethelHook.Tray.exe /F"; RunOnceId: "KillTrayApp"; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "stop AethelHook"; RunOnceId: "StopService"; Flags: runhidden waituntilterminated
Filename: "{sys}\sc.exe"; Parameters: "delete AethelHook"; RunOnceId: "DeleteService"; Flags: runhidden waituntilterminated
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NonInteractive -NoProfile -ExecutionPolicy Bypass -Command ""Remove-NetFirewallRule -Name 'AethelHook-TCP-5264' -ErrorAction SilentlyContinue; Remove-NetFirewallRule -Name 'AethelHook-UDP-47263' -ErrorAction SilentlyContinue"""; RunOnceId: "RemoveFirewallRules"; Flags: runhidden
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NonInteractive -NoProfile -ExecutionPolicy Bypass -File ""{app}\uninstall_hooks.ps1"" ""{%USERPROFILE}"""; RunOnceId: "RemoveHooks"; Flags: runhidden

[UninstallDelete]
Type: filesandordirs; Name: "{commonappdata}\AethelHook"

[Code]
procedure InitializeWizard;
begin
  WizardForm.NextButton.Caption := '&Install';
end;
