# Security Policy

AethelHook exists to gate dangerous actions an AI agent can take on your PC, so a
vulnerability in it isn't just a bug - it can mean tool calls get silently approved
that shouldn't be, or a device token/credential gets exposed. Reports are taken
seriously and prioritized over new features.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, email **aethelst8@gmail.com** with:

- A description of the issue and its impact (what an attacker could actually do)
- Steps to reproduce, or a proof of concept if you have one
- Which component is affected (PC API, Android app, a specific hook script, the
  installer) and, if known, the affected file/line

This is a solo-maintained project, not a company with an SLA - I'll do my best to
acknowledge reports quickly and fix confirmed issues promptly, but response times may
vary. You'll get credit in the fix's commit/release notes unless you'd prefer not to
be named.

## Supported versions

Only the latest version on the `main` branch is supported. There's no LTS/backport
policy at this stage.

## Scope

In scope: the PC API (`AethelHook.API`), the Tray app, the Android app, the PowerShell
hook scripts for each supported IDE, and the installer.

Out of scope: vulnerabilities in Claude Code, Codex, or Antigravity themselves -
report those to their respective vendors.
