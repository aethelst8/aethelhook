package com.aethelhook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethelhook.app.ui.theme.AethelColorPalette
import com.aethelhook.app.ui.theme.LocalAethelColors

// Claude Code's own confirmed CLI vocabulary - only values actually confirmed real via
// `claude --help` this session, not guessed. Model aliases per --help: "Provide an alias
// for the latest model (e.g. 'fable', 'opus', or 'sonnet')".
private val CLAUDE_MODELS = listOf(
    "default" to "Default model",
    "opus" to "Opus",
    "sonnet" to "Sonnet",
    "fable" to "Fable"
)

// Codex has no fixed model enum in its own CLI (`<MODEL>` is free text). "o3" (the only
// name in Codex's own --help example, `-c model="o3"`) was live-tested this session and
// rejected outright on this account: "The 'o3' model is not supported when using Codex
// with a ChatGPT account" - a real plan-dependent constraint, not something fixable
// here. Left at "Default model" only rather than offering a name that may not work for
// most Codex/ChatGPT-plan users - revisit if a reliably-working model name is
// confirmed later.
private val CODEX_MODELS = listOf(
    "default" to "Default model"
)

// Live-queried this session via `opencode models` on this dev machine - the real
// provider/model strings this install currently has configured, not guessed.
private val OPENCODE_MODELS = listOf(
    "default" to "Default model",
    "opencode/big-pickle" to "big-pickle",
    "opencode/deepseek-v4-flash-free" to "deepseek-v4-flash",
    "opencode/hy3-free" to "hy3",
    "opencode/mimo-v2.5-free" to "mimo-v2.5",
    "opencode/nemotron-3-ultra-free" to "nemotron-3-ultra",
    "opencode/north-mini-code-free" to "north-mini-code"
)

// Canonical effort vocabulary Claude itself accepts (`claude --help`). Reused as-is for
// all 3 agents - the server maps it per agent before building the CLI invocation
// (Codex's real ceiling is "xhigh", live-verified via the model's own rejection error;
// OpenCode's --variant was live-verified to accept the full list unchanged).
private val EFFORTS = listOf(
    "default" to "Default",
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "xhigh" to "XHigh",
    "max" to "Max"
)

// All 6 are Claude Code's own real --permission-mode values (`claude --help`) - labels
// below are plain-English renderings of Claude's own value names, not borrowed from any
// other app's branding. All 6 live-verified this session to NOT bypass AethelHook's own
// PreToolUse hook - the hook still fires and its decision is honored for every one of
// them, including bypassPermissions despite its name (see CLAUDE.md's permission-mode
// verification note) - it only affects Claude's own native prompting behavior, which is
// mostly moot in headless mode anyway. "plan" is functionally different (Claude refuses
// any tool but plan-file edits while it's active) rather than a safety concern - kept in
// the list as a legitimate "just plan, don't act" choice for a headless Session Access
// prompt. Claude-only - see the doc comment below on why Codex/OpenCode don't get this
// picker at all.
private val CLAUDE_PERMISSION_MODES = listOf(
    "default" to "Default permissions",
    "plan" to "Plan (read-only)",
    "dontAsk" to "Don't ask",
    "acceptEdits" to "Accept edits",
    "auto" to "Auto",
    "manual" to "Manual",
    "bypassPermissions" to "Bypass permissions"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    agent: String,
    current: ProjectAgentSettings,
    onSave: (ProjectAgentSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalAethelColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var model by remember { mutableStateOf(current.model ?: "default") }
    var effort by remember { mutableStateOf(current.effort ?: "default") }
    var permissionMode by remember { mutableStateOf(current.permissionMode ?: "default") }
    var useWorktree by remember { mutableStateOf(current.useWorktree) }

    val models = when (agent) {
        "codex" -> CODEX_MODELS
        "opencode" -> OPENCODE_MODELS
        else -> CLAUDE_MODELS
    }

    fun save() {
        onSave(
            ProjectAgentSettings(
                model = model.takeIf { it != "default" },
                effort = effort.takeIf { it != "default" },
                permissionMode = permissionMode.takeIf { it != "default" && agent == "claude" },
                useWorktree = useWorktree
            )
        )
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.bgCardAlt
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Session settings",
                color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SettingsGroup("Model", models, model, c) { model = it }
            Spacer(Modifier.height(12.dp))
            SettingsGroup("Effort", EFFORTS, effort, c) { effort = it }
            Spacer(Modifier.height(12.dp))
            // Permission mode is Claude-only: neither Codex nor OpenCode has a native
            // permission-mode concept - AethelHook's own hook (the Codex PreToolUse
            // script / the OpenCode tool.execute.before plugin) is already the sole
            // gate for both, entirely independent of any CLI flag. Showing a picker
            // with nothing behind it would be UI theater, so a static row explains
            // that instead of offering a choice that wouldn't do anything.
            if (agent == "claude") {
                SettingsGroup("Permission mode", CLAUDE_PERMISSION_MODES, permissionMode, c) { permissionMode = it }
            } else {
                Text(
                    "PERMISSION MODE", color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    "Handled by AethelHook's own approval gate for this agent - no separate mode to pick.",
                    color = c.textMuted, fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use worktree", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Runs this session in an isolated git worktree instead of the project directory directly.",
                        color = c.textMuted, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = useWorktree,
                    onCheckedChange = { useWorktree = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = c.accentCyan, checkedTrackColor = c.accentCyan.copy(alpha = 0.4f))
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan)
            ) {
                Text("Save", color = c.bgDeep, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    c: AethelColorPalette,
    onSelect: (String) -> Unit
) {
    Text(
        title.uppercase(), color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == value, onClick = { onSelect(value) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    colors = RadioButtonDefaults.colors(selectedColor = c.accentCyan)
                )
                Spacer(Modifier.width(4.dp))
                Text(label, color = c.textPrimary, fontSize = 14.sp)
            }
        }
    }
}
