# Codex Hooks, MCP, and Approval Modes

Using the current Codex docs: yes, Codex has lifecycle hooks.

## Direct Answers

| Question | Answer |
|---|---|
| Hook system | Yes. `PreToolUse` exists and can run before supported tool calls. It can intercept `Bash`, `apply_patch` file edits, and MCP tool calls. It is documented as a guardrail, not a complete enforcement boundary, because it does not cover every possible tool path, including `WebSearch` and some shell paths. Source: [Hooks](https://developers.openai.com/codex/hooks). |
| Stop/done hook | Yes. `Stop` fires when a turn is about to finish. Returning a block/continue signal can make Codex continue with another pass instead of ending the turn. |
| Hook config format | Hooks live in `hooks.json` or inline `[hooks]` tables in `config.toml`. Common locations are `~/.codex/hooks.json`, `~/.codex/config.toml`, `<repo>/.codex/hooks.json`, and `<repo>/.codex/config.toml`. Project hooks only load for trusted project config. Example: `{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"python3 .codex/hooks/pre_tool_use_policy.py"}]}]}}`. See config shape in [Hooks](https://developers.openai.com/codex/hooks). |
| Stdin format | Every command hook receives one JSON object on stdin. Common fields include `session_id`, `transcript_path`, `cwd`, `hook_event_name`, and `model`; turn-scoped hooks also include `turn_id`. `PreToolUse` additionally gets `tool_name`, `tool_use_id`, and `tool_input`. For `Bash`/`apply_patch`, the command is in `tool_input.command`; MCP tools receive their arguments as `tool_input`. |
| Block mechanism | For `PreToolUse`, return JSON on stdout with `hookSpecificOutput.hookEventName = "PreToolUse"`, `permissionDecision = "deny"`, and `permissionDecisionReason`. Legacy `{ "decision": "block", "reason": "..." }` is also accepted. Exit code `2` with the blocking reason on stderr also blocks. |
| MCP support | Yes. Codex supports MCP servers in CLI and IDE, with STDIO and streamable HTTP servers, config in `config.toml`, and per-server/per-tool approval settings. MCP servers expose tools to Codex; they do not globally wrap or intercept other Codex tool calls. Interception is done by hooks, and hooks can match MCP tool names like `mcp__server__tool`. Source: [MCP](https://developers.openai.com/codex/mcp). |
| Approval modes | At config/API level, the documented controls are `sandbox_mode`, `approval_policy`, `approvals_reviewer`, granular approval policies, and MCP tool approval modes. `Auto` is typically `workspace-write` + `on-request`: read/edit/run inside workspace, ask for outside workspace or network. Read-only uses `read-only`. Full access is `danger-full-access` + no approvals/`--yolo`, and is explicitly risky. Older `codex exec --full-auto` is deprecated compatibility. A script can participate through `PermissionRequest` hooks; automatic review can also route eligible approvals to an `auto_review` reviewer agent. Sources: [Agent approvals & security](https://developers.openai.com/codex/agent-approvals-security), [Advanced config](https://developers.openai.com/codex/config-advanced). |

One important nuance: `PermissionRequest` hooks are specifically for cases where Codex is about to ask for approval. They can allow or deny that approval request, but they do not run for actions that already proceed without approval.
