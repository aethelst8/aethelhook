package com.aethelhook.app

import android.content.Context
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
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

private data class ChatItem(
    val text: String,
    val timestamp: Long,
    val isUser: Boolean,
    val label: String = ""
)

private data class ProjectInfo(val path: String, val label: String)

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
}

private fun agentLabel(agent: String, longForm: Boolean = false): String = when {
    agent.equals("codex", ignoreCase = true)    -> "Codex"
    agent.equals("opencode", ignoreCase = true) -> "OpenCode"
    longForm                                    -> "Claude Code"
    else                                         -> "Claude"
}

private suspend fun sendPromptToApi(baseUrl: String, ctx: Context, prompt: String, projectDir: String?, agent: String): String = withContext(Dispatchers.IO) {
    val label = agentLabel(agent, longForm = true)
    try {
        val json    = JSONObject().apply {
            put("prompt", prompt)
            if (!projectDir.isNullOrBlank()) put("project_dir", projectDir)
            put("agent", agent)
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
                    ProjectHomeCard(proj = proj, onClick = { onSelectProject(proj) })
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectHomeCard(proj: ProjectInfo, onClick: () -> Unit) {
    val c = LocalAethelColors.current
    val shape = RoundedCornerShape(16.dp)
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
            Text(proj.path, color = c.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    LaunchedEffect(chat.size, thinking) {
        val lastIndex = chat.size - 1 + if (thinking) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
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
            val error = sendPromptToApi(AppPrefs.getApiUrl(ctx), ctx, text, project.path, selectedAgent)
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
        }

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
            items(chat) { item -> ChatBubble(item) }
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
}

@Composable
private fun ChatBubble(item: ChatItem) {
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
            Text(
                timeText, color = c.textMuted, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
            )
        }
    }
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
