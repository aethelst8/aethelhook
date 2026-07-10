# Contributing

This is a solo-maintained personal project, but contributions are welcome.

## Before opening a PR

- For anything beyond a small fix, open an issue first describing what you want to
  change and why - saves both of us from a PR that doesn't fit the project's direction.
- Found a security issue? Don't open a public issue or PR for it - see
  [SECURITY.md](SECURITY.md) instead.

## Setting up a dev environment

See the "Development" section of [README.md](README.md) for build commands, and
[CLAUDE.md](CLAUDE.md) for the fuller technical reference - architecture, how each
IDE's hooks are wired, and a running list of non-obvious issues already hit and fixed
(worth checking before you rediscover one of them).

## Pull requests

- Keep PRs focused - one change per PR is easier to review than several bundled
  together.
- If you touch a hook script, keep the dev copy (`.claude/hooks/`, `.codex/hooks/`,
  `.gemini/hooks/`), the `dist/hooks/` mirror, in sync - the project has been bitten
  before by these three drifting apart.
- Explain the *why* in the PR description, not just the *what* - especially for
  anything touching auth, the pairing flow, or file permissions.
