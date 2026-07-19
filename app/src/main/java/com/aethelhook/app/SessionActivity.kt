package com.aethelhook.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethelhook.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Phase 2: Session Access. A 4th Dashboard/History/Settings-style tab (not a
// separate launched Activity - just another screen in MainActivity's Scaffold,
// same pattern as DashboardScreen/HistoryScreen/SettingsScreen) for sending a
// phone-composed prompt to the PC. The PC runs it as a headless `claude -p`
// process (Program.cs's /hook/send-prompt) in the last-known project directory -
// not injected into the user's live interactive session. No OS-level automation
// involved (an earlier version did this via keystroke/clipboard injection into
// the live VS Code window; replaced after a safety review found real risk of
// misdirected input with no way to verify or undo it).
//
// This tab is two views, not one: a "Sessions" home (a list of every project the
// API has seen) and a per-project chat. Landing directly in the most-recently-used
// chat (the original design) meant there was no way to see or switch to a
// different project without already knowing the picker chip existed - so entering
// this tab now always shows the project list first, and the user explicitly picks
// one to open its chat (WhatsApp-conversation-list-style), with a back arrow to
// return to the list.

// Carries whatever an approval/question/plan-review actionable event needs to either
// render inline chips or deep-link into the existing full-screen Activity. `answered` is
// a Compose MutableState (not a plain val) so tapping a chip - or an "ack" arriving from
// ANY surface (notification quick-action, full-screen Activity, or this chip) - can flip
// it in place without needing to replace the ChatItem inside SessionChatStore's list.
private class PendingAction(
    val type: String,
    val sessionId: String,
    val toolName: String = "",
    val preview: String = "",
    val respondUrl: String = "",
    val answerUrl: String = "",
    val questionsJson: String = "",
    val planText: String = "",
    val planPreview: String = "",
    val planUrl: String = "",
    val planUrls: String = "",
    val respondUrls: String = "",
    val answered: MutableState<String?> = mutableStateOf(null)
)

private data class ChatItem(
    val text: String,
    val timestamp: Long,
    val isUser: Boolean,
    val label: String = "",
    val action: PendingAction? = null
)

private data class ProjectInfo(val path: String, val label: String)

private data class GitStatus(
    val isGitRepo: Boolean,
    val branch: String?,
    val added: Int,
    val deleted: Int,
    val dirty: Boolean
)

// Context-window usage for one agent in one project - see Program.cs's
// TokenUsageByProjectAgent for how this is populated server-side. contextWindow is exact
// for Claude (read from the CLI's own result JSON) but an approximation for Codex/OpenCode
// (neither reports its model's real max), so this is shown as a gauge, not a hard quota.
private data class TokenUsage(val agent: String, val tokensUsed: Long, val contextWindow: Long)

// on_tool_done.ps1 fires for EVERY tool call in EVERY Claude Code window (hooks are
// global, per PreToolUse/PostToolUse/SessionStart), not just phone-initiated headless
// runs - so session_update/prompt_result broadcasts arrive for whichever project is
// active on the PC at that moment, independent of what's selected on the phone. The
// server tags each broadcast with the cwd it came from; this normalizes that path into
// a stable key so messages route to the right project's bucket instead of whichever
// chat happens to be open on the phone when the broadcast lands.
private fun projectKey(path: String?): String =
    path?.trimEnd('\\', '/')?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "__unassigned__"

// Holds the chat timeline outside the composable's own lifecycle - the chat view is one
// branch of MainActivity's tab switcher, not a standalone Activity, so switching to
// another tab and back disposes and recreates it; a `remember`-scoped list would reset
// to empty every time. A top-level store (same pattern as AethelHookWebSocket's own
// singleton) survives for as long as the app process does.
//
// Keyed per-project (by projectKey) rather than one shared list: each project's
// conversation persists independently, so switching the selected project on the phone
// just changes which bucket is rendered - it never clears anything, and a broadcast
// from project A can never appear while project B's chat is open.
private object SessionChatStore {
    private val byProject = mutableMapOf<String, SnapshotStateList<ChatItem>>()
    private val thinkingByProject = mutableStateMapOf<String, Boolean>()

    fun messagesFor(key: String): SnapshotStateList<ChatItem> =
        byProject.getOrPut(key) { mutableStateListOf() }

    fun isThinking(key: String): Boolean = thinkingByProject[key] == true

    fun setThinking(key: String, value: Boolean) {
        if (value) thinkingByProject[key] = true else thinkingByProject.remove(key)
    }

    // An "ack" carries no cwd (see AethelHookWebSocket.handleMessage), so the pending
    // action it resolves could be in any project's bucket - session ids are unique
    // across the whole server, so a linear scan across the (small, personal-use-scale)
    // set of known project buckets is cheap and safe.
    fun markAnswered(sessionId: String, label: String) {
        for (list in byProject.values) {
            val item = list.firstOrNull { it.action?.sessionId == sessionId } ?: continue
            item.action?.answered?.value = label
            return
        }
    }
}

private fun agentLabel(agent: String, longForm: Boolean = false): String = when {
    agent.equals("codex", ignoreCase = true)    -> "Codex"
    agent.equals("opencode", ignoreCase = true) -> "OpenCode"
    longForm                                    -> "Claude Code"
    else                                         -> "Claude"
}

private suspend fun sendPromptToApi(
    baseUrl: String, ctx: Context, prompt: String, projectDir: String?, agent: String,
    settings: ProjectAgentSettings = ProjectAgentSettings()
): String = withContext(Dispatchers.IO) {
    val label = agentLabel(agent, longForm = true)
    try {
        val json    = JSONObject().apply {
            put("prompt", prompt)
            if (!projectDir.isNullOrBlank()) put("project_dir", projectDir)
            put("agent", agent)
            settings.model?.let { put("model", it) }
            settings.effort?.let { put("effort", it) }
            settings.permissionMode?.let { put("permission_mode", it) }
            if (settings.useWorktree) put("use_worktree", true)
        }.toString()
        val body    = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$baseUrl/hook/send-prompt").post(body).build()
        AethelHookWebSocket.newBoundHttpClient(ctx).newCall(request).execute().use { response ->
            if (response.isSuccessful) "" else friendlyHttpError(response.code, response.body?.string(), label)
        }
    } catch (e: Exception) {
        friendlyNetworkError(e)
    }
}

// Phase 2 (Session Access): lets the phone list every project directory the API has
// seen via hook events, so the user can explicitly pick which one a NEW conversation
// targets instead of trusting whichever Claude Code window was touched most recently
// (LastKnownCwd on the server) - important once you run Claude Code across multiple
// editors/projects (e.g. this project in VS Code, work projects in Cursor).
private suspend fun fetchKnownProjects(baseUrl: String, ctx: Context): List<ProjectInfo> = withContext(Dispatchers.IO) {
    try {
        val request  = Request.Builder().url("$baseUrl/hook/known-projects").get().build()
        val response = AethelHookWebSocket.newBoundHttpClient(ctx).newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return@withContext emptyList()
            val arr = org.json.JSONArray(it.body?.string().orEmpty())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ProjectInfo(path = obj.optString("path"), label = obj.optString("label"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// Phase 1 (Happy-Coder-parity work): branch + diff-stat shown per project row in the
// Sessions list. A separate call per project rather than baked into
// fetchKnownProjects/known-projects - that endpoint is a pure in-memory dictionary read
// today and folding a git shell-out into it for every known project would add real
// latency to every list refresh, so this is called once per visible card instead (see
// ProjectHomeCard's LaunchedEffect below - LazyColumn only composes on-screen rows, so
// this is naturally bounded to what's actually visible).
private suspend fun fetchGitStatus(baseUrl: String, ctx: Context, dir: String): GitStatus? = withContext(Dispatchers.IO) {
    try {
        val url = "$baseUrl/hook/git-status?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
        val request  = Request.Builder().url(url).get().build()
        val response = AethelHookWebSocket.newBoundHttpClient(ctx).newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return@withContext null
            val obj = JSONObject(it.body?.string().orEmpty())
            GitStatus(
                isGitRepo = obj.optBoolean("isGitRepo", false),
                branch = obj.optString("branch").ifBlank { null },
                added = obj.optInt("added", 0),
                deleted = obj.optInt("deleted", 0),
                dirty = obj.optBoolean("dirty", false)
            )
        }
    } catch (e: Exception) {
        null
    }
}

// Context-window usage row, shown for all three agents at once regardless of which one
// is currently selected - each keeps its own separate resumable thread per project
// already (see the agent toggle above), so seeing only the selected agent's number would
// hide real usage building up in the other two.
private suspend fun fetchTokenUsage(baseUrl: String, ctx: Context, dir: String): Map<String, TokenUsage> = withContext(Dispatchers.IO) {
    try {
        val url = "$baseUrl/hook/token-usage?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
        val request  = Request.Builder().url(url).get().build()
        val response = AethelHookWebSocket.newBoundHttpClient(ctx).newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return@withContext emptyMap()
            val arr = org.json.JSONArray(it.body?.string().orEmpty())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val agent = obj.optString("agent").ifBlank { return@mapNotNull null }
                agent to TokenUsage(agent, obj.optLong("tokens_used", 0), obj.optLong("context_window", 0))
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

private fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.0fK".format(n / 1_000.0)
    else           -> n.toString()
}

// Root of the Sessions tab - always starts on the project list (`activeProject == null`)
// on every entry into this tab, since SessionScreen is recreated each time (see
// SessionChatStore's comment above); there is deliberately no restore-last-project
// behavior here anymore.
@Composable
fun SessionScreen(ctx: Context) {
    var activeProject by remember { mutableStateOf<ProjectInfo?>(null) }

    val project = activeProject
    if (project == null) {
        SessionsHomeScreen(
            ctx = ctx,
            onSelectProject = { picked -> activeProject = picked }
        )
    } else {
        SessionChatScreen(
            ctx = ctx,
            project = project,
            onBack = { activeProject = null }
        )
    }
}

@Composable
private fun SessionsHomeScreen(ctx: Context, onSelectProject: (ProjectInfo) -> Unit) {
    val c = LocalAethelColors.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        loading = true
        scope.launch {
            projects = fetchKnownProjects(AppPrefs.getApiUrl(ctx), ctx)
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sessions", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.accentAmber.copy(alpha = 0.15f))
                    .border(1.dp, c.accentAmber.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text("BETA", color = c.accentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = c.accentCyan, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            "Pick a project to send a prompt to your PC.",
            color = c.textSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = c.accentCyan, strokeWidth = 2.dp)
            }
            projects.isEmpty() -> Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(c.textMuted.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(30.dp))
                    }
                    Text("No known projects yet", color = c.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Trigger a tool call in a Claude Code or Codex session first, then tap refresh.",
                        color = c.textMuted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects) { proj ->
                    ProjectHomeCard(ctx = ctx, proj = proj, onClick = { onSelectProject(proj) })
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectHomeCard(ctx: Context, proj: ProjectInfo, onClick: () -> Unit) {
    val c = LocalAethelColors.current
    val shape = RoundedCornerShape(16.dp)
    var gitStatus by remember(proj.path) { mutableStateOf<GitStatus?>(null) }

    LaunchedEffect(proj.path) {
        gitStatus = fetchGitStatus(AppPrefs.getApiUrl(ctx), ctx, proj.path)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
            .border(1.dp, c.divider.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(c.accentCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(proj.label, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(proj.path, color = c.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val status = gitStatus
                if (status?.isGitRepo == true) {
                    status.branch?.let {
                        Text(it, color = c.accentPurple, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (status.added > 0 || status.deleted > 0) {
                        Text("+${status.added}", color = c.accentGreen, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        Text("-${status.deleted}", color = c.accentRed, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SessionChatScreen(ctx: Context, project: ProjectInfo, onBack: () -> Unit) {
    val c = LocalAethelColors.current
    val scope = rememberCoroutineScope()

    // Registered only while a chat is on screen, so it takes priority over
    // AethelHookApp's own BackHandler (which would otherwise send back straight to
    // Dashboard) - system back / swipe-back here returns to the Sessions list first,
    // same as tapping the arrow in the header.
    androidx.activity.compose.BackHandler(onBack = onBack)
    var prompt by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    var selectedAgent by remember { mutableStateOf(AppPrefs.getLastAgent(ctx)) }
    val selectedKey = remember(project.path) { projectKey(project.path) }
    val chat = SessionChatStore.messagesFor(selectedKey)
    val thinking = SessionChatStore.isThinking(selectedKey)

    // Context-window usage row - fetched fresh on every entry into this chat (mirrors
    // ProjectHomeCard's own per-visit git-status fetch), then kept live via usage_update
    // broadcasts for as long as this screen stays open.
    var tokenUsage by remember(project.path) { mutableStateOf<Map<String, TokenUsage>>(emptyMap()) }
    LaunchedEffect(project.path) {
        tokenUsage = fetchTokenUsage(AppPrefs.getApiUrl(ctx), ctx, project.path)
    }
    val usageUpdate by AethelHookWebSocket.usageUpdates.collectAsState()
    LaunchedEffect(usageUpdate) {
        val u = usageUpdate ?: return@LaunchedEffect
        if (projectKey(u.optString("cwd").ifBlank { null }) == selectedKey) {
            val agent = u.optString("agent")
            if (agent.isNotBlank()) {
                tokenUsage = tokenUsage + (agent to TokenUsage(agent, u.optLong("tokens_used", 0), u.optLong("context_window", 0)))
            }
        }
        AethelHookWebSocket.usageUpdates.value = null
    }

    val update by AethelHookWebSocket.sessionUpdates.collectAsState()
    LaunchedEffect(update) {
        val u = update ?: return@LaunchedEffect
        val targetKey  = projectKey(u.optString("cwd").ifBlank { null })
        val targetList = SessionChatStore.messagesFor(targetKey)
        val message    = u.optString("message")
        val detail     = u.optString("detail")
        val label      = agentLabel(u.optString("agent"), longForm = true)

        when (u.optString("type")) {
            "session_update" -> {
                targetList.add(
                    ChatItem(
                        text      = detail.ifBlank { message },
                        timestamp = System.currentTimeMillis(),
                        isUser    = false,
                        label     = u.optString("tool_name").ifBlank { label }
                    )
                )
                SessionChatStore.setThinking(targetKey, false)
            }
            "prompt_result" -> {
                // On success the reply text already arrived via "session_update" chunks
                // above (the assistant's streamed text) - only add a bubble here for a
                // failure, since that path never produces one otherwise.
                val isError = message.contains("fail", ignoreCase = true) ||
                              message.contains("error", ignoreCase = true)
                if (isError) {
                    targetList.add(
                        ChatItem(
                            text      = detail.ifBlank { message },
                            timestamp = System.currentTimeMillis(),
                            isUser    = false,
                            label     = label
                        )
                    )
                }
                SessionChatStore.setThinking(targetKey, false)
            }
        }
        // Consume it - the StateFlow holds its last value forever, so leaving this
        // screen and coming back would otherwise re-fire this LaunchedEffect with the
        // same stale update and duplicate it into the (now persistent) chat list.
        AethelHookWebSocket.sessionUpdates.value = null
    }

    // Approvals/questions/plan-reviews now also show as an inline actionable chat item,
    // alongside (not instead of) the existing push notification - see actionableEvents'
    // own doc comment in AethelHookWebSocket.kt. Same cwd-routing + consume-then-null
    // pattern as the session_update effect above.
    val actionable by AethelHookWebSocket.actionableEvents.collectAsState()
    LaunchedEffect(actionable) {
        val a = actionable ?: return@LaunchedEffect

        when (a.optString("type")) {
            "approval_request" -> {
                val targetKey  = projectKey(a.optString("cwd").ifBlank { null })
                val targetList = SessionChatStore.messagesFor(targetKey)
                val toolName   = a.optString("tool_name").ifBlank { "tool" }
                targetList.add(
                    ChatItem(
                        text      = a.optString("detail").ifBlank { "Approve this $toolName call?" },
                        timestamp = System.currentTimeMillis(),
                        isUser    = false,
                        label     = "Approval needed",
                        action    = PendingAction(
                            type       = "approval_request",
                            sessionId  = a.optString("session_id"),
                            toolName   = toolName,
                            preview    = a.optString("detail"),
                            respondUrl = a.optString("respond_url")
                        )
                    )
                )
            }
            "ask_question" -> {
                val targetKey  = projectKey(a.optString("cwd").ifBlank { null })
                val targetList = SessionChatStore.messagesFor(targetKey)
                val questions  = a.optJSONArray("questions") ?: org.json.JSONArray()
                val firstText  = questions.optJSONObject(0)?.optString("question").orEmpty()
                targetList.add(
                    ChatItem(
                        text      = firstText.ifBlank { "Your PC has a question" },
                        timestamp = System.currentTimeMillis(),
                        isUser    = false,
                        label     = "Question",
                        action    = PendingAction(
                            type          = "ask_question",
                            sessionId     = a.optString("session_id"),
                            answerUrl     = a.optString("answer_url"),
                            questionsJson = questions.toString()
                        )
                    )
                )
            }
            "plan_review" -> {
                val targetKey  = projectKey(a.optString("cwd").ifBlank { null })
                val targetList = SessionChatStore.messagesFor(targetKey)
                targetList.add(
                    ChatItem(
                        text      = a.optString("plan_preview").ifBlank { "Review the proposed plan" },
                        timestamp = System.currentTimeMillis(),
                        isUser    = false,
                        label     = "Plan review",
                        action    = PendingAction(
                            type        = "plan_review",
                            sessionId   = a.optString("session_id"),
                            respondUrl  = a.optString("respond_url"),
                            planText    = a.optString("plan"),
                            planPreview = a.optString("plan_preview"),
                            planUrl     = a.optString("plan_url"),
                            planUrls    = a.optJSONArray("plan_urls")?.toString().orEmpty(),
                            respondUrls = a.optJSONArray("respond_urls")?.toString().orEmpty()
                        )
                    )
                )
            }
            "ack" -> {
                val sessionId = a.optString("session_id")
                val label = when {
                    a.has("decision") -> when (a.optString("decision")) {
                        "allow", "allow_once"                         -> "Allowed"
                        "always_allow_project", "always_allow_global" -> "Always allowed"
                        else                                          -> "Denied"
                    }
                    else -> "Answered"
                }
                SessionChatStore.markAnswered(sessionId, label)
            }
        }
        AethelHookWebSocket.actionableEvents.value = null
    }

    LaunchedEffect(chat.size, thinking) {
        val lastIndex = chat.size - 1 + if (thinking) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    // Per (project, agent) model/effort/permission-mode - reloaded whenever the agent
    // toggle is cycled, since each agent has its own settings scope (see
    // ProjectAgentSettings' own doc comment). Only Claude has a real picker for now
    // (Codex/OpenCode's model/effort mapping is a later phase, and neither has a native
    // permission-mode concept at all - see SettingsSheet.kt).
    var currentSettings by remember(selectedAgent, project.path) {
        mutableStateOf(AppPrefs.getProjectAgentSettings(ctx, selectedKey, selectedAgent))
    }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Voice-to-prompt: delegates to the system speech-recognition activity (typically the
    // Google app) rather than the raw on-device SpeechRecognizer API - no RECORD_AUDIO
    // permission needed in our own manifest, since the resolved recognizer activity holds
    // that permission itself, not us. Appends to (rather than replaces) whatever's already
    // typed, so it composes with manual editing instead of clobbering it.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                prompt = if (prompt.isBlank()) spoken else "${prompt.trimEnd()} $spoken"
            }
        }
    }
    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your prompt")
        }
        if (intent.resolveActivity(ctx.packageManager) != null) {
            speechLauncher.launch(intent)
        } else {
            Toast.makeText(ctx, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun submit() {
        val text = prompt.trim()
        if (text.isEmpty() || sending) return
        chat.add(ChatItem(text = text, timestamp = System.currentTimeMillis(), isUser = true))
        prompt = ""
        sending = true
        sendError = null
        SessionChatStore.setThinking(selectedKey, true)
        scope.launch {
            val error = sendPromptToApi(AppPrefs.getApiUrl(ctx), ctx, text, project.path, selectedAgent, currentSettings)
            sending = false
            if (error.isNotEmpty()) {
                sendError = error
                SessionChatStore.setThinking(selectedKey, false)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header - back arrow to the Sessions list, project name, agent toggle.
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Sessions", tint = c.textPrimary)
            }
            Text(
                project.label,
                fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Per-message agent toggle - each send uses whichever is currently picked,
            // and every project keeps independent Claude/Codex/OpenCode chat + resumable
            // threads (ProjectSessions/CodexProjectSessions/OpenCodeProjectSessions on
            // the server), so cycling this doesn't lose or mix up any conversation.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.bgCardAlt)
                    .clickable {
                        selectedAgent = when (selectedAgent) {
                            "claude"   -> "codex"
                            "codex"    -> "opencode"
                            else       -> "claude"
                        }
                        AppPrefs.setLastAgent(ctx, selectedAgent)
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(13.dp))
                Text(
                    agentLabel(selectedAgent),
                    color = c.textPrimary, fontSize = 11.sp, maxLines = 1
                )
            }
            // Model/effort settings, all 3 agents - permission-mode within the sheet is
            // Claude-only (see SettingsSheet.kt).
            IconButton(onClick = { showSettingsSheet = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Session settings", tint = c.textMuted, modifier = Modifier.size(18.dp))
            }
        }

        TokenUsageRow(tokenUsage)

        // Chat timeline - fills all remaining space.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (chat.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Send a prompt to get started - replies and progress will appear here.",
                            color = c.textMuted, fontSize = 13.sp
                        )
                    }
                }
            }
            items(chat) { item -> ChatBubble(ctx, item) }
            if (thinking) {
                item { ThinkingBubble() }
            }
        }

        sendError?.let {
            Text(
                it, color = c.accentRed, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Pinned input row - text field + small circular send button, no wasted space.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { startVoiceInput() }, modifier = Modifier.padding(bottom = 2.dp)) {
                Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = c.accentCyan, modifier = Modifier.size(20.dp))
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message your PC…", color = c.textMuted, fontSize = 13.sp) },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = c.accentCyan,
                    unfocusedBorderColor = c.divider,
                    focusedTextColor     = c.textPrimary,
                    unfocusedTextColor   = c.textPrimary,
                    cursorColor          = c.accentCyan
                )
            )
            val canSend = prompt.isNotBlank() && !sending
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (canSend) c.accentCyan else c.textMuted.copy(alpha = 0.15f))
                    .then(if (canSend) Modifier.clickable { submit() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.bgDeep, strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) c.bgDeep else c.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        SettingsSheet(
            agent = selectedAgent,
            current = currentSettings,
            onSave = { updated ->
                currentSettings = updated
                AppPrefs.setProjectAgentSettings(ctx, selectedKey, selectedAgent, updated)
            },
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun ChatBubble(ctx: Context, item: ChatItem) {
    val c = LocalAethelColors.current
    val timeText = remember(item.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }
    val bubbleColor = if (item.isUser) c.accentCyan.copy(alpha = 0.22f) else c.bgCardAlt
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (item.isUser) 16.dp else 4.dp,
        bottomEnd = if (item.isUser) 4.dp else 16.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (item.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bubbleColor)
                .border(1.dp, c.divider, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!item.isUser && item.label.isNotBlank()) {
                Text(item.label, color = c.accentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
            }
            Text(item.text, color = c.textPrimary, fontSize = 14.sp, lineHeight = 19.sp)
            item.action?.let { action ->
                Spacer(Modifier.height(6.dp))
                val answered = action.answered.value
                if (answered != null) {
                    Text("You: $answered", color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                } else {
                    ActionChips(ctx, action)
                }
            }
            Text(
                timeText, color = c.textMuted, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
            )
        }
    }
}

// Inline actionable chips for a pending approval/question/plan-review - the same
// decision codepaths the full-screen Activities use (via DecisionActions), reached
// directly for the simple cases (approve/deny, a single low-option-count question) and
// via a deep-link Intent into the existing Activity for anything that needs more room
// (many/multi-select question options, plan text + optional feedback).
@Composable
private fun ActionChips(ctx: Context, action: PendingAction) {
    val c = LocalAethelColors.current
    val scope = rememberCoroutineScope()

    when (action.type) {
        "approval_request" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Allow", c.accentGreen) {
                action.answered.value = "Allowed"
                scope.launch {
                    DecisionActions.submitApprovalDecision(
                        ctx, action.respondUrl, action.sessionId, action.toolName, action.preview, "allow_once"
                    )
                }
            }
            ActionChip("Deny", c.accentRed) {
                action.answered.value = "Denied"
                scope.launch {
                    DecisionActions.submitApprovalDecision(
                        ctx, action.respondUrl, action.sessionId, action.toolName, action.preview, "deny"
                    )
                }
            }
        }
        "ask_question" -> {
            val questions = remember(action.questionsJson) {
                runCatching { org.json.JSONArray(action.questionsJson) }.getOrNull() ?: org.json.JSONArray()
            }
            val single = questions.length() == 1
            val firstOptions = questions.optJSONObject(0)?.optJSONArray("options")
            val singleSelect = questions.optJSONObject(0)?.optBoolean("multiSelect", false) == false
            if (single && singleSelect && firstOptions != null && firstOptions.length() in 1..4) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val questionText = questions.optJSONObject(0)?.optString("question").orEmpty()
                    for (i in 0 until firstOptions.length()) {
                        val label = firstOptions.optJSONObject(i)?.optString("label").orEmpty()
                        if (label.isBlank()) continue
                        ActionChip(label, c.accentCyan) {
                            action.answered.value = label
                            scope.launch {
                                val answers = JSONObject().apply { put(questionText, label) }
                                DecisionActions.submitQuestionAnswer(ctx, action.answerUrl, action.sessionId, answers)
                            }
                        }
                    }
                }
            } else {
                ActionChip("Open to answer", c.accentCyan) {
                    ctx.startActivity(
                        Intent(ctx, QuestionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("session_id", action.sessionId)
                            putExtra("answer_url", action.answerUrl)
                            putExtra("questions_json", action.questionsJson)
                        }
                    )
                }
            }
        }
        "plan_review" -> ActionChip("View plan & decide", c.accentCyan) {
            ctx.startActivity(
                Intent(ctx, PlanReviewActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("session_id", action.sessionId)
                    putExtra("respond_url", action.respondUrl)
                    putExtra("plan_url", action.planUrl)
                    putExtra("plan_text", action.planText)
                    putExtra("plan_preview", action.planPreview)
                    putExtra("plan_urls", action.planUrls)
                    putExtra("respond_urls", action.respondUrls)
                }
            )
        }
    }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// Context-window usage row for the currently open project - one compact stat per agent,
// shown for all three at once even though only one is selected to send to right now (see
// the fetchTokenUsage/tokenUsage state in SessionChatScreen). An agent that has never run
// in this project yet just shows a dash rather than 0%, since 0% would misleadingly imply
// a real, empty conversation exists.
@Composable
private fun TokenUsageRow(usage: Map<String, TokenUsage>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        for (agent in listOf("claude", "codex", "opencode")) {
            TokenUsageChip(agentLabel(agent), usage[agent])
        }
    }
}

@Composable
private fun TokenUsageChip(label: String, usage: TokenUsage?) {
    val c = LocalAethelColors.current
    if (usage == null || usage.contextWindow <= 0) {
        Text("$label –", color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        return
    }
    val pct = (usage.tokensUsed * 100 / usage.contextWindow).toInt().coerceIn(0, 100)
    val color = when {
        pct >= 80 -> c.accentRed
        pct >= 50 -> c.accentAmber
        else      -> c.accentGreen
    }
    Text(
        "$label ${formatTokenCount(usage.tokensUsed)}/${formatTokenCount(usage.contextWindow)} ($pct%)",
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium
    )
}

// Shown while waiting for the PC's first reply to a sent prompt - the headless run can
// take anywhere from seconds to minutes with no other feedback, so this fills the gap
// instead of the screen looking stalled. Cleared as soon as any session_update/
// prompt_result for this project's cwd arrives (see the LaunchedEffect in SessionChatScreen).
@Composable
private fun ThinkingBubble() {
    val c = LocalAethelColors.current
    var dotCount by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(450)
            dotCount = (dotCount % 3) + 1
        }
    }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .clip(shape)
                .background(c.bgCardAlt)
                .border(1.dp, c.divider, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "thinking" + ".".repeat(dotCount),
                color = c.textMuted, fontSize = 14.sp, lineHeight = 19.sp,
                modifier = Modifier.widthIn(min = 62.dp)
            )
        }
    }
}
