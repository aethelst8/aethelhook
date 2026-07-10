package com.aethelhook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethelhook.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionId   = intent.getStringExtra("session_id")      ?: ""
        val respondUrl  = intent.getStringExtra("respond_url")     ?: ""
        val toolName    = intent.getStringExtra("tool_name")       ?: "tool"
        val commandName = intent.getStringExtra("command_name")    ?: "command"
        val cmdPreview  = intent.getStringExtra("command_preview") ?: ""
        val isDark      = AppPrefs.getDarkMode(applicationContext)
        setContent {
            AethelHookTheme(darkTheme = isDark) {
                ApprovalScreen(
                    sessionId    = sessionId,
                    respondUrl   = respondUrl,
                    toolName     = toolName,
                    commandName  = commandName,
                    cmdPreview   = cmdPreview,
                    onDone       = { finish() }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Decision enum - maps to API decision strings
// ─────────────────────────────────────────────────────────────────────────────

enum class ApprovalChoice {
    ALLOW_ONCE,
    ALWAYS_PROJECT,
    ALWAYS_GLOBAL,
    NO_WITH_REASON,
    DENY
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ApprovalScreen(
    sessionId: String,
    respondUrl: String,
    toolName: String,
    commandName: String,
    cmdPreview: String,
    onDone: () -> Unit
) {
    val c = LocalAethelColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var moreExpanded   by remember { mutableStateOf(false) }
    var advancedChoice by remember { mutableStateOf<ApprovalChoice?>(null) }
    var reasonText     by remember { mutableStateOf("") }
    var submitting     by remember { mutableStateOf(false) }

    // Effective labels + decisions based on advanced selection
    val allowDecision = when (advancedChoice) {
        ApprovalChoice.ALWAYS_PROJECT -> "always_allow_project"
        ApprovalChoice.ALWAYS_GLOBAL  -> "always_allow_global"
        else                          -> "allow_once"
    }
    val denyDecision = when (advancedChoice) {
        ApprovalChoice.NO_WITH_REASON -> "deny_with_reason"
        else                          -> "deny"
    }
    val allowLabel = when (advancedChoice) {
        ApprovalChoice.ALWAYS_PROJECT -> "Always Here"
        ApprovalChoice.ALWAYS_GLOBAL  -> "Always Allow"
        else                          -> "Allow"
    }
    val denyLabel = when (advancedChoice) {
        ApprovalChoice.NO_WITH_REASON -> "Send Instruction"
        else                          -> "Deny"
    }
    val allowColor = when (advancedChoice) {
        ApprovalChoice.ALWAYS_PROJECT -> c.accentCyan
        ApprovalChoice.ALWAYS_GLOBAL  -> c.accentPurple
        else                          -> c.accentGreen
    }

    fun submitDecision(decision: String, reason: String = "") {
        submitting = true
        AppPrefs.addRecord(ctx, ApprovalRecord(
            sessionId   = sessionId,
            toolName    = toolName,
            preview     = cmdPreview,
            decision    = decision,
            timestampMs = System.currentTimeMillis()
        ))
        scope.launch(Dispatchers.IO) {
            try {
                val conn = URL(respondUrl).openConnection() as HttpURLConnection
                conn.pinnedFromPrefs(ctx)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-AethelHook-Token", AppPrefs.getApiToken(ctx))
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val safe = reason.replace("\\", "\\\\").replace("\"", "\\\"")
                OutputStreamWriter(conn.outputStream).use {
                    it.write("""{"session_id":"$sessionId","decision":"$decision","reason":"$safe"}""")
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
            onDone()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        GlassBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            // Scrollable content area - leaves room for floating bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header card
                ApprovalHeaderCard(toolName = toolName)

                // Command preview
                CommandPreviewCard(cmdPreview = cmdPreview)

                // Advanced / more options
                AdvancedOptionsSection(
                    commandName    = commandName,
                    expanded       = moreExpanded,
                    advancedChoice = advancedChoice,
                    reasonText     = reasonText,
                    onToggle       = {
                        moreExpanded = !moreExpanded
                        if (!moreExpanded) advancedChoice = null
                    },
                    onChoiceSelect = { choice ->
                        advancedChoice = if (advancedChoice == choice) null else choice
                    },
                    onReasonChange = { reasonText = it }
                )
            }

            // Floating action bar - always visible at bottom
            FloatingApprovalBar(
                allowLabel = allowLabel,
                denyLabel  = denyLabel,
                allowColor = allowColor,
                submitting = submitting,
                onAllow    = { submitDecision(allowDecision) },
                onDeny     = { submitDecision(denyDecision, if (advancedChoice == ApprovalChoice.NO_WITH_REASON) reasonText else "") }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ApprovalHeaderCard(toolName: String) {
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
                    .background(c.accentAmber.copy(alpha = 0.15f))
                    .border(1.dp, c.accentAmber.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = c.accentAmber, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Allow this command?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                Text(toolName, fontSize = 13.sp, color = c.textSecondary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Command preview card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CommandPreviewCard(cmdPreview: String) {
    val c = LocalAethelColors.current
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgCardAlt)
            .border(1.dp, c.divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(c.accentGreen))
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(c.accentAmber))
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(c.accentRed))
                Spacer(Modifier.width(4.dp))
                Text("command", color = c.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (scrollState.canScrollForward || scrollState.canScrollBackward) {
                    Spacer(Modifier.weight(1f))
                    Text("scroll ↕", color = c.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            // Capped at 220dp so the Approve/Deny buttons are always visible without outer scrolling.
            // Long-press text to select and copy the full command.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = cmdPreview.ifBlank { "(no preview)" },
                        color = c.accentCyan,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Advanced options section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdvancedOptionsSection(
    commandName: String,
    expanded: Boolean,
    advancedChoice: ApprovalChoice?,
    reasonText: String,
    onToggle: () -> Unit,
    onChoiceSelect: (ApprovalChoice) -> Unit,
    onReasonChange: (String) -> Unit
) {
    val c = LocalAethelColors.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "arrow"
    )
    val hasSelection = advancedChoice != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Toggle row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (hasSelection) c.accentPurple.copy(alpha = 0.08f)
                    else c.bgCard.copy(alpha = 0.5f)
                )
                .border(
                    1.dp,
                    if (hasSelection) c.accentPurple.copy(alpha = 0.3f) else c.divider,
                    RoundedCornerShape(14.dp)
                )
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = if (hasSelection) c.accentPurple else c.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "More options",
                    color = if (hasSelection) c.accentPurple else c.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (hasSelection) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (hasSelection) {
                    val selLabel = when (advancedChoice) {
                        ApprovalChoice.ALWAYS_PROJECT -> "Always here"
                        ApprovalChoice.ALWAYS_GLOBAL  -> "Always global"
                        ApprovalChoice.NO_WITH_REASON -> "Deny + reason"
                        else                          -> ""
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.accentPurple.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(selLabel, color = c.accentPurple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = c.textMuted,
                    modifier = Modifier.size(20.dp).rotate(arrowRotation)
                )
            }
        }

        // Expandable options
        AnimatedVisibility(
            visible = expanded,
            enter   = expandVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(tween(180)),
            exit    = shrinkVertically(tween(180)) + fadeOut(tween(130))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (c.isDark) 0.22f else 0.80f),
                                Color.White.copy(alpha = if (c.isDark) 0.04f else 0.20f)
                            )
                        ),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdvancedOptionRow(
                        choice   = ApprovalChoice.ALWAYS_PROJECT,
                        icon     = Icons.Default.Add,
                        label    = "Always allow '$commandName' in this project",
                        color    = c.accentCyan,
                        selected = advancedChoice == ApprovalChoice.ALWAYS_PROJECT,
                        onClick  = { onChoiceSelect(ApprovalChoice.ALWAYS_PROJECT) }
                    )
                    AdvancedOptionRow(
                        choice   = ApprovalChoice.ALWAYS_GLOBAL,
                        icon     = Icons.Default.Lock,
                        label    = "Always allow '$commandName' everywhere",
                        color    = c.accentPurple,
                        selected = advancedChoice == ApprovalChoice.ALWAYS_GLOBAL,
                        onClick  = { onChoiceSelect(ApprovalChoice.ALWAYS_GLOBAL) }
                    )
                    AdvancedOptionRow(
                        choice   = ApprovalChoice.NO_WITH_REASON,
                        icon     = Icons.Default.Info,
                        label    = "Deny - but tell agent what to do instead",
                        color    = c.accentAmber,
                        selected = advancedChoice == ApprovalChoice.NO_WITH_REASON,
                        onClick  = { onChoiceSelect(ApprovalChoice.NO_WITH_REASON) }
                    )

                    // Reason text field expands under option 3
                    AnimatedVisibility(
                        visible = advancedChoice == ApprovalChoice.NO_WITH_REASON,
                        enter   = expandVertically(tween(200)) + fadeIn(),
                        exit    = shrinkVertically(tween(150)) + fadeOut()
                    ) {
                        OutlinedTextField(
                            value = reasonText,
                            onValueChange = onReasonChange,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            placeholder = { Text("e.g. Use Get-Item instead of Get-Content", color = c.textMuted, fontSize = 12.sp) },
                            label = { Text("Instruction for agent") },
                            minLines = 2,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor    = c.accentAmber,
                                unfocusedBorderColor  = c.divider,
                                focusedLabelColor     = c.accentAmber,
                                unfocusedLabelColor   = c.textSecondary,
                                focusedTextColor      = c.textPrimary,
                                unfocusedTextColor    = c.textPrimary,
                                cursorColor           = c.accentAmber
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedOptionRow(
    choice: ApprovalChoice,
    icon: ImageVector,
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val c = LocalAethelColors.current
    val bgAnim by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(180), label = "optBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgAnim)
            .border(1.dp, if (selected) color.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (selected) color.copy(alpha = 0.18f) else c.divider.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (selected) Icons.Default.Done else icon,
                contentDescription = null,
                tint = if (selected) color else c.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            label,
            color = if (selected) c.textPrimary else c.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            lineHeight = 18.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Floating action bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FloatingApprovalBar(
    allowLabel: String,
    denyLabel: String,
    allowColor: Color,
    submitting: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val c = LocalAethelColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
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
                        Text("Submitting...", color = c.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Deny button
                    ActionPillButton(
                        label = denyLabel,
                        color = c.accentRed,
                        icon = Icons.Default.Close,
                        modifier = Modifier.weight(1f),
                        onClick = onDeny
                    )
                    // Allow button
                    ActionPillButton(
                        label = allowLabel,
                        color = allowColor,
                        icon = Icons.Default.Done,
                        modifier = Modifier.weight(1f),
                        onClick = onAllow
                    )
                }
            }
        }
    }
}

@Composable
fun ActionPillButton(
    label: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
