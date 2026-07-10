package com.aethelhook.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethelhook.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class PlanReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionId     = intent.getStringExtra("session_id")    ?: ""
        val respondUrl    = intent.getStringExtra("respond_url")   ?: ""
        val planUrl       = intent.getStringExtra("plan_url")      ?: ""
        val planTextExtra = intent.getStringExtra("plan_text")
        val planPreview   = intent.getStringExtra("plan_preview")  ?: ""
        val planUrls      = parseUrlList(intent.getStringExtra("plan_urls"), planUrl)
        val respondUrls   = parseUrlList(intent.getStringExtra("respond_urls"), respondUrl)
        val isDark        = AppPrefs.getDarkMode(applicationContext)
        setContent {
            AethelHookTheme(darkTheme = isDark) {
                PlanReviewScreen(
                    sessionId      = sessionId,
                    respondUrls    = respondUrls,
                    planUrls       = planUrls,
                    planTextInline = planTextExtra,
                    planPreview    = planPreview,
                    onDone         = { finish() }
                )
            }
        }
    }
}

// Server sends an ordered list of candidate base URLs (Tailscale first, then LAN) as a
// JSON array string; falls back to the single legacy field for older payload shapes.
private fun parseUrlList(json: String?, fallback: String): List<String> {
    if (!json.isNullOrBlank()) {
        try {
            val arr = JSONArray(json)
            val list = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            if (list.isNotEmpty()) return list
        } catch (_: Exception) { /* fall through to legacy field */ }
    }
    return if (fallback.isNotBlank()) listOf(fallback) else emptyList()
}

private sealed class PlanFetchResult {
    data class Success(val plan: String) : PlanFetchResult()
    object NotFound : PlanFetchResult()
    object NetworkError : PlanFetchResult()
    object Malformed : PlanFetchResult()
}

// Fetches { "plan": "..." }, trying each candidate URL in order — first reachable one wins.
private suspend fun fetchPlan(ctx: Context, planUrls: List<String>, token: String): PlanFetchResult = withContext(Dispatchers.IO) {
    if (planUrls.isEmpty()) return@withContext PlanFetchResult.NetworkError
    var sawNotFound = false
    for (url in planUrls) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.pinnedFromPrefs(ctx)
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-AethelHook-Token", token)
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            val code = conn.responseCode
            if (code == 404) { conn.disconnect(); sawNotFound = true; continue }
            if (code != 200) { conn.disconnect(); continue }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val plan = JSONObject(body).optString("plan")
            return@withContext if (plan.isNotBlank()) PlanFetchResult.Success(plan) else PlanFetchResult.Malformed
        } catch (_: Exception) {
            // try next candidate URL
        }
    }
    return@withContext if (sawNotFound) PlanFetchResult.NotFound else PlanFetchResult.NetworkError
}

// Posts the decision, trying each candidate respond URL in order. Returns true on any 2xx.
private suspend fun postDecision(
    ctx: Context,
    respondUrls: List<String>,
    token: String,
    sessionId: String,
    decision: String,
    feedbackText: String
): Boolean = withContext(Dispatchers.IO) {
    for (url in respondUrls) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.pinnedFromPrefs(ctx)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-AethelHook-Token", token)
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            val body = JSONObject().apply {
                put("session_id", sessionId)
                put("decision", decision)
                put("feedback", feedbackText)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) return@withContext true
        } catch (_: Exception) {
            // try next candidate URL
        }
    }
    return@withContext false
}

@Composable
fun PlanReviewScreen(
    sessionId: String,
    respondUrls: List<String>,
    planUrls: List<String>,
    planTextInline: String?,
    planPreview: String,
    onDone: () -> Unit
) {
    val c = LocalAethelColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var fetchState  by remember { mutableStateOf<PlanFetchResult?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }
    var rejecting   by remember { mutableStateOf(false) }
    var feedback    by remember { mutableStateOf("") }
    var submitting  by remember { mutableStateOf(false) }

    LaunchedEffect(retryTrigger) {
        fetchState = if (retryTrigger == 0 && !planTextInline.isNullOrBlank()) {
            // Plan arrived inline over WebSocket — no fetch needed at all.
            PlanFetchResult.Success(planTextInline)
        } else {
            fetchPlan(ctx, planUrls, AppPrefs.getApiToken(ctx))
        }
    }

    fun submit(decision: String, feedbackText: String = "") {
        submitting = true
        scope.launch(Dispatchers.IO) {
            if (AethelHookWebSocket.isConnected) {
                AethelHookWebSocket.sendPlanReviewDecision(sessionId, decision, feedbackText)
            } else {
                postDecision(ctx, respondUrls, AppPrefs.getApiToken(ctx), sessionId, decision, feedbackText)
            }
            onDone()
        }
    }

    val currentState = fetchState
    val planText = (currentState as? PlanFetchResult.Success)?.plan

    Box(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        GlassBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PlanHeaderCard()

                when {
                    currentState == null -> PlanLoadingCard()
                    currentState is PlanFetchResult.Success -> PlanTextCard(currentState.plan)
                    else -> PlanLoadErrorCard(
                        result = currentState,
                        preview = planPreview,
                        onRetry = { retryTrigger++ }
                    )
                }
            }

            PlanReviewBar(
                enabled    = planText != null && !submitting,
                submitting = submitting,
                rejecting  = rejecting,
                feedback   = feedback,
                onFeedbackChange = { feedback = it },
                onAutoAccept     = { submit("yes_auto_accept") },
                onManualApprove  = { submit("yes_manual_approve") },
                onStartReject    = { rejecting = true },
                onSendReject     = { submit("keep_planning", feedback) }
            )
        }
    }
}

@Composable
private fun PlanHeaderCard() {
    val c = LocalAethelColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.22f else 0.80f),
                        Color.White.copy(alpha = if (c.isDark) 0.04f else 0.20f)
                    )
                ),
                RoundedCornerShape(22.dp)
            )
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(c.accentPurple.copy(alpha = 0.15f))
                    .border(1.dp, c.accentPurple.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = c.accentPurple, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Accept this plan?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                Text("Read the plan below before responding", fontSize = 13.sp, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun PlanLoadingCard() {
    val c = LocalAethelColors.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.accentCyan, strokeWidth = 2.dp)
            Text("Loading plan...", color = c.textSecondary, fontSize = 14.sp)
        }
    }
}

private fun planLoadErrorMessage(result: PlanFetchResult): String = when (result) {
    is PlanFetchResult.NotFound ->
        "This plan is no longer available on the PC — it may have already been resolved. Approve/reject from the PC instead."
    is PlanFetchResult.NetworkError ->
        "Can't reach the PC right now — check your Wi-Fi/Tailscale connection, or approve/reject from the PC instead."
    is PlanFetchResult.Malformed ->
        "Got an unexpected response from the PC. Approve/reject from the PC instead."
    is PlanFetchResult.Success -> "" // unreachable — Success renders PlanTextCard instead
}

@Composable
private fun PlanLoadErrorCard(result: PlanFetchResult, preview: String, onRetry: () -> Unit) {
    val c = LocalAethelColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgCardAlt)
            .border(1.dp, c.accentRed.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(planLoadErrorMessage(result), color = c.accentRed, fontSize = 13.sp)
        if (preview.isNotBlank()) {
            Text("Preview: $preview", color = c.textSecondary, fontSize = 13.sp)
        }
        PlanPillButton(
            label = "Retry",
            color = c.accentCyan,
            icon = Icons.Default.Refresh,
            enabled = true,
            onClick = onRetry
        )
    }
}

@Composable
private fun PlanTextCard(plan: String) {
    val c = LocalAethelColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgCardAlt)
            .border(1.dp, c.divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SelectionContainer {
            Text(
                text = plan,
                color = c.textPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun PlanReviewBar(
    enabled: Boolean,
    submitting: Boolean,
    rejecting: Boolean,
    feedback: String,
    onFeedbackChange: (String) -> Unit,
    onAutoAccept: () -> Unit,
    onManualApprove: () -> Unit,
    onStartReject: () -> Unit,
    onSendReject: () -> Unit
) {
    val c = LocalAethelColors.current
    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (c.isDark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.65f))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (c.isDark) 0.22f else 0.80f),
                            Color.White.copy(alpha = if (c.isDark) 0.04f else 0.20f)
                        )
                    ),
                    RoundedCornerShape(24.dp)
                )
                .padding(12.dp)
        ) {
            if (submitting) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.accentCyan, strokeWidth = 2.dp)
                        Text("Sending...", color = c.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rejecting) {
                        OutlinedTextField(
                            value = feedback,
                            onValueChange = onFeedbackChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Tell Claude what to do instead", color = c.textMuted, fontSize = 12.sp) },
                            minLines = 2,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = c.accentAmber,
                                unfocusedBorderColor = c.divider,
                                focusedTextColor     = c.textPrimary,
                                unfocusedTextColor   = c.textPrimary,
                                cursorColor          = c.accentAmber
                            )
                        )
                        PlanPillButton(
                            label = "Send feedback",
                            color = c.accentAmber,
                            icon = Icons.AutoMirrored.Filled.Send,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onSendReject
                        )
                    } else {
                        PlanPillButton(
                            label = "Yes, auto-accept edits",
                            color = c.accentGreen,
                            icon = Icons.Default.Done,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onAutoAccept
                        )
                        PlanPillButton(
                            label = "Yes, manually approve edits",
                            color = c.accentCyan,
                            icon = Icons.Default.Done,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onManualApprove
                        )
                        PlanPillButton(
                            label = "No, keep planning",
                            color = c.accentRed,
                            icon = Icons.Default.Close,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onStartReject
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanPillButton(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) color.copy(alpha = 0.14f) else Color.Gray.copy(alpha = 0.10f))
            .border(1.dp, if (enabled) color.copy(alpha = 0.40f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = if (enabled) color else Color.Gray, modifier = Modifier.size(16.dp))
            Text(label, color = if (enabled) color else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
