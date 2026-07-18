// AethelHook - OpenCode edition
//
// Unlike Claude Code/Codex/Antigravity (a PowerShell script spawned per hook event via
// a JSON hooks.json config), OpenCode's hook mechanism is a JS/TS plugin loaded once
// into OpenCode's own long-running process (registered via opencode.json's "plugin"
// array, see RestoreOpenCodeHooks() in Program.cs). Confirmed via live testing
// (2026-07-13) against OpenCode 1.4.3:
//   - "tool.execute.before" fires reliably for real tool calls and reliably BLOCKS
//     the call when it throws - the agent receives a genuine tool-execution error,
//     not a silent bypass.
//   - "tool.execute.after" fires once the tool call completes - args live on
//     `input.args` here (not `output.args` like "before"). Nothing was posting a
//     per-tool "still working" update from this hook, unlike the other three IDEs'
//     PostToolUse-equivalent scripts (on_tool_done.ps1 etc.) which fire on every
//     tool call, headless or interactive, and push a live activity bubble into the
//     phone's Sessions chat - this is why that chat stayed empty for OpenCode
//     during real interactive use even though notifications and Session Access
//     replies both worked (confirmed live 2026-07-13, reported by the user testing
//     on a second PC).
//   - OpenCode's own documented "permission.ask" hook is defined in the plugin SDK
//     types but is NEVER actually triggered (confirmed both by our own testing and by
//     an open upstream bug, google/opencode - anomalyco/opencode#7006, filed Jan 2026,
//     still open as of this writing) - do not build against it.
//   - There is no dedicated "session.idle" hook key; it's an event TYPE delivered
//     through the generic "event" hook (event.type === "session.idle"), payload is
//     just { sessionID } - no cwd, no message text. The plugin's own `client` (an SDK
//     client bound to this OpenCode server, part of PluginInput) can fetch the
//     session's messages back out via GET /session/{id}/message though - confirmed
//     live the shape matches `opencode export <id>`: an array of
//     { info: {role, ...}, parts: [{type, text, ...}] }.
import fs from "fs";

const DEBUG_LOG = "C:\\ProgramData\\AethelHook\\hook_debug.log";
function log(msg) {
  try {
    fs.appendFileSync(DEBUG_LOG, `${new Date().toISOString()} [OpenCode] ${msg}\n`, { encoding: "utf8" });
  } catch (e) { /* never let logging crash the plugin */ }
}

const TOKEN_PATH = "C:\\ProgramData\\AethelHook\\api_token.txt";
function getApiToken() {
  try { return fs.readFileSync(TOKEN_PATH, "utf8").trim(); } catch (e) { return ""; }
}

const PHONE_ALLOW_PATH = "C:\\ProgramData\\AethelHook\\phone_allow.txt";
function isPhoneAllowed(fullCommand) {
  try {
    const lines = fs.readFileSync(PHONE_ALLOW_PATH, "utf8").split(/\r?\n/).map(l => l.trim()).filter(Boolean);
    return lines.includes(fullCommand);
  } catch (e) { return false; }
}
function addToPhoneAllow(fullCommand) {
  try { fs.appendFileSync(PHONE_ALLOW_PATH, fullCommand + "\n", { encoding: "utf8" }); } catch (e) { /* best effort */ }
}

// Loop-repetition guard for OpenCode's own "doom loop" bug (confirmed live 2026-07-13,
// see RunHeadlessOpenCodePromptAsync's kill-after-stop comment in Program.cs) - after a
// genuine reply, OpenCode injects a synthetic "Continue if you have next steps..."
// nudge and keeps looping, re-running the same read-only status/log/diff checks every
// ~80s indefinitely. The headless runner works around this by killing the process
// after the first "stop" - but a plain INTERACTIVE `opencode` session (a terminal the
// user left open) has no such guard, and each loop iteration re-fires
// tool.execute.before, spamming a fresh phone notification roughly every 80s, for as
// long as the terminal sits there (confirmed live: 12:49-15:00+, over 2 hours,
// continuous git status/log/diff approval requests from one stuck session).
//
// Fix: once the phone has explicitly approved the EXACT same command
// REPEAT_THRESHOLD times within REPEAT_WINDOW_MS, further repeats of that exact string
// auto-approve without a new notification - deliberately scoped to a tight allowlist of
// read-only git subcommands only (never anything that writes/deletes), and requires
// real human approval first each time before any auto-approval kicks in, rather than a
// blanket "it repeated so it must be safe" rule. In-memory only (resets whenever this
// OpenCode process restarts) - this is meant to tame an ACTIVE runaway loop within one
// live session, not become a permanent standing rule like phone_allow.txt.
const REPEAT_WINDOW_MS = 15 * 60 * 1000;
const REPEAT_THRESHOLD = 2;
const approvalHistory = new Map(); // fullCommand -> { approvals, lastSeenAt }

// Only a tight allowlist of git's own read-only subcommands - fails closed (returns
// false) if ANY chained segment (split on &&/;/||/|) doesn't match, so something like
// "git status && rm -rf ." can never slip through as "read-only" just because its
// first segment looks safe.
const READ_ONLY_GIT_SUBCOMMANDS = new Set([
  "status", "log", "diff", "show", "branch", "remote",
  "rev-parse", "describe", "blame", "shortlog", "ls-files", "ls-tree", "cat-file"
]);
function isReadOnlyGitCommand(fullCommand) {
  const segments = fullCommand.split(/&&|\|\||[;|]/).map(s => s.trim()).filter(Boolean);
  if (segments.length === 0) return false;
  return segments.every(seg => {
    const parts = seg.split(/\s+/);
    return parts[0] === "git" && READ_ONLY_GIT_SUBCOMMANDS.has(parts[1]);
  });
}

function checkRepeatAutoApprove(fullCommand) {
  const entry = approvalHistory.get(fullCommand);
  if (!entry) return false;
  if (Date.now() - entry.lastSeenAt > REPEAT_WINDOW_MS) {
    approvalHistory.delete(fullCommand);
    return false;
  }
  return entry.approvals >= REPEAT_THRESHOLD;
}

function recordDecision(fullCommand, wasApproved) {
  if (!isReadOnlyGitCommand(fullCommand)) return; // only ever track the safe subset
  if (!wasApproved) {
    approvalHistory.delete(fullCommand); // any denial resets trust for this exact command
    return;
  }
  const entry = approvalHistory.get(fullCommand) || { approvals: 0, lastSeenAt: 0 };
  entry.approvals += 1;
  entry.lastSeenAt = Date.now();
  approvalHistory.set(fullCommand, entry);
}

const API_BASE = "http://localhost:5266";

async function apiFetch(path, options = {}) {
  const token = getApiToken();
  const headers = Object.assign(
    { "Content-Type": "application/json; charset=utf-8" },
    token ? { "X-AethelHook-Token": token } : {},
    options.headers || {}
  );
  return fetch(`${API_BASE}${path}`, Object.assign({}, options, { headers }));
}

// Build a human-readable preview from a tool call's args - field names confirmed live
// for "bash" (args.command); other tool names/arg shapes are guessed by analogy with
// the other three IDEs' hook scripts and best-effort JSON-stringified as a fallback.
function buildPreview(toolName, args) {
  if (!args) return `Agent is requesting permission to run ${toolName}`;
  if (args.command) return String(args.command);
  if (args.filePath) return String(args.filePath);
  if (args.path) return String(args.path);
  try { return JSON.stringify(args); } catch (e) { return `Agent is requesting permission to run ${toolName}`; }
}

// Strips common markdown so the phone shows plain text - mirrors the same stripping
// logic in the other three IDEs' Stop-hook scripts (on_agent_done.ps1).
function stripMarkdown(text) {
  return text
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/\*([^*\r\n]+)\*/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .trim();
}

export const AethelHookPlugin = async (ctx) => {
  const directory = ctx.directory;
  const client = ctx.client;
  log(`Plugin loaded for directory=${directory}`);

  // session.idle's own payload has no message text (see comment above) - pull the
  // session's last real assistant reply back out via the SDK client instead, same
  // "grab the final assistant text block" logic as the other three IDEs' Stop hooks.
  async function lastAssistantText(sessionID) {
    try {
      const res = await client.session.messages({ path: { id: sessionID } });
      const messages = (res && res.data) || [];
      for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i];
        if (!m.info || m.info.role !== "assistant") continue;
        const textParts = (m.parts || []).filter(p => p.type === "text" && !p.synthetic && p.text && p.text.trim());
        if (textParts.length) {
          const text = stripMarkdown(textParts[textParts.length - 1].text.trim());
          return text.length > 4000 ? text.slice(0, 4000) + "..." : text;
        }
      }
    } catch (e) {
      log(`Failed to fetch session messages for summary: ${e}`);
    }
    return "";
  }

  return {
    "tool.execute.before": async (input, output) => {
      const toolName = input.tool;

      // Claude Code's own hook config never gates TodoWrite at all - it isn't in
      // settings.json's PreToolUse matcher list, since it's pure internal task-list
      // bookkeeping with no filesystem/execution side effects. OpenCode's plugin gates
      // every tool with no such exemption, which is what made a stuck/looping OpenCode
      // session's own todo-list updates spam phone approvals for something with zero
      // real-world impact (confirmed live: no Write/Edit approval ever accompanied
      // these calls). Match Claude Code's own precedent instead of partially covering
      // it via the repeat-tracker below.
      if (toolName === "todowrite") return;

      const preview = buildPreview(toolName, output && output.args);
      const fullCommand = preview.trim();

      if (isPhoneAllowed(fullCommand)) {
        log(`'${fullCommand}' is in phone allow list - auto-approving silently`);
        return;
      }

      if (isReadOnlyGitCommand(fullCommand) && checkRepeatAutoApprove(fullCommand)) {
        log(`'${fullCommand}' auto-approved (repeated read-only command, already approved ${REPEAT_THRESHOLD}+ times in the last ${REPEAT_WINDOW_MS / 60000}min)`);
        return;
      }

      const sessionId = crypto.randomUUID(); // fresh id per call - never OpenCode's own sessionID,
      // matching the same cross-call contamination gotcha already fixed for Claude Code
      // (a conversation-scoped id would let a late phone answer leak into the next call).
      log(`Approval request: tool=${toolName} preview=${preview} sessionId=${sessionId}`);

      try {
        const postRes = await apiFetch("/hook/event", {
          method: "POST",
          body: JSON.stringify({
            event_type: "APPROVAL_REQUEST",
            message: `[${toolName}] Approve or Decline?`,
            detail: preview,
            session_id: sessionId,
            timestamp: new Date().toISOString(),
            tool_name: toolName,
            command_name: fullCommand.split(/\s+/)[0] || toolName,
          }),
        });
        if (!postRes.ok) {
          log(`POST /hook/event failed (${postRes.status}) - failing open, letting OpenCode's own permission flow decide`);
          return;
        }
      } catch (e) {
        log(`POST /hook/event unreachable - failing open: ${e}`);
        return;
      }

      let decision = "deny";
      let reason = "";
      try {
        const waitRes = await apiFetch(`/hook/wait-decision/${sessionId}`, {
          method: "GET",
          signal: AbortSignal.timeout(80000),
        });
        if (waitRes.ok) {
          const body = await waitRes.json();
          if (body && body.decision) {
            decision = body.decision;
            reason = body.reason || "";
          }
        }
      } catch (e) {
        log(`GET /hook/wait-decision failed (timeout or error): ${e}`);
        decision = "deny";
      }

      log(`Decision: ${decision}`);

      switch (decision) {
        case "allow":
        case "allow_once":
          recordDecision(fullCommand, true);
          return; // let the tool proceed
        case "always_allow_project":
        case "always_allow_global":
          addToPhoneAllow(fullCommand);
          return;
        case "deny_with_reason":
          recordDecision(fullCommand, false);
          throw new Error(reason || "User declined via phone");
        case "deny":
          recordDecision(fullCommand, false);
          throw new Error("Denied via phone");
        default:
          recordDecision(fullCommand, false);
          throw new Error("No phone response (timed out)");
      }
    },

    "tool.execute.after": async (input) => {
      const toolName = input.tool;
      const preview = buildPreview(toolName, input.args);
      const detail = preview.length > 150 ? preview.slice(0, 150) + "..." : preview;
      try {
        await apiFetch("/hook/session-update", {
          method: "POST",
          body: JSON.stringify({
            message: "Still working...",
            detail,
            tool_name: toolName,
            cwd: directory,
          }),
        });
      } catch (e) {
        log(`POST /hook/session-update failed: ${e}`);
      }
    },

    "event": async (input) => {
      const evt = input && input.event;
      if (!evt || evt.type !== "session.idle") return;
      const sessionID = evt.properties && evt.properties.sessionID;
      log(`session.idle fired for ${sessionID}`);
      const detail = sessionID ? await lastAssistantText(sessionID) : "";
      try {
        await apiFetch("/hook/notify", {
          method: "POST",
          body: JSON.stringify({ message: "OpenCode finished", detail, cwd: directory }),
        });
      } catch (e) {
        log(`POST /hook/notify failed: ${e}`);
      }
    },
  };
};
