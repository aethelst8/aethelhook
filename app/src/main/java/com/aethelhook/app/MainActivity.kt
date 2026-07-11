package com.aethelhook.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aethelhook.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("ÆthelHook", "POST_NOTIFICATIONS granted: $granted")
        }

    private val pendingSummary = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (AppPrefs.getGatewayEnabled(this)) {
            AethelHookWebSocketService.start(this)
        }
        checkSummaryIntent(intent)
        enableEdgeToEdge()
        val ctx = applicationContext
        setContent {
            var isDark by rememberSaveable { mutableStateOf(AppPrefs.getDarkMode(ctx)) }
            val summary = pendingSummary.value
            AethelHookTheme(darkTheme = isDark) {
                AethelHookApp(
                    isDark = isDark,
                    onToggleTheme = { isDark = !isDark; AppPrefs.setDarkMode(ctx, isDark) },
                    summary = summary,
                    onSummaryDismissed = { pendingSummary.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkSummaryIntent(intent)
    }

    private fun checkSummaryIntent(intent: Intent) {
        val title = intent.getStringExtra("summary_title") ?: return
        val body  = intent.getStringExtra("summary_body")  ?: return
        if (title.isNotBlank() && body.isNotBlank()) {
            pendingSummary.value = Pair(title, body)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Root
// ──────────────────────────────────────────────────────────────────────────────

enum class Screen { Dashboard, Session, History, Settings }

@Composable
fun AethelHookApp(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    summary: Pair<String, String>? = null,
    onSummaryDismissed: () -> Unit = {}
) {
    val c = LocalAethelColors.current
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
    val ctx = LocalContext.current

    // Dashboard is the app's root screen: back from any other tab returns here first,
    // and only backing out of Dashboard itself closes the app. Disabled while already on
    // Dashboard so the system default (finish the activity) applies there. The Sessions
    // tab's chat view registers its own BackHandler (see SessionChatScreen) that takes
    // priority over this one while it's on screen - back there goes to the Sessions list
    // instead of straight to Dashboard.
    androidx.activity.compose.BackHandler(enabled = currentScreen != Screen.Dashboard) {
        currentScreen = Screen.Dashboard
    }

    Box(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        GlassBackground()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                FloatingPillNav(current = currentScreen, onSelect = { currentScreen = it })
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        fadeIn(tween(240)) + slideInVertically { 20 } togetherWith
                        fadeOut(tween(180)) + slideOutVertically { -20 }
                    },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.Dashboard -> DashboardScreen(ctx, isDark, onToggleTheme)
                        Screen.Session   -> SessionScreen(ctx)
                        Screen.History   -> HistoryScreen(ctx)
                        Screen.Settings  -> SettingsScreen(ctx, isDark, onToggleTheme)
                    }
                }
            }
        }
    }

    if (summary != null) {
        SummaryPopup(
            title   = summary.first,
            body    = summary.second,
            onDismiss = onSummaryDismissed
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Animated blob background
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassBackground() {
    val c = LocalAethelColors.current
    val inf = rememberInfiniteTransition(label = "blobs")
    val pulse by inf.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = size.width * 0.55f * pulse
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c.blobA, Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.12f),
                radius = r
            ),
            radius = r,
            center = Offset(size.width * 0.88f, size.height * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(c.blobB, Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.78f),
                radius = r * 0.75f
            ),
            radius = r * 0.75f,
            center = Offset(size.width * 0.12f, size.height * 0.78f)
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Floating pill navigation
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun FloatingPillNav(current: Screen, onSelect: (Screen) -> Unit) {
    val c = LocalAethelColors.current
    data class NavItem(val screen: Screen, val icon: ImageVector, val label: String)
    val items = listOf(
        NavItem(Screen.Dashboard, Icons.Default.Home,          "Dashboard"),
        NavItem(Screen.Session,   Icons.AutoMirrored.Filled.Chat, "Sessions"),
        NavItem(Screen.History,   Icons.Default.Notifications, "History"),
        NavItem(Screen.Settings,  Icons.Default.Settings,      "Settings"),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(if (c.isDark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.60f))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (c.isDark) 0.22f else 0.80f),
                            Color.White.copy(alpha = if (c.isDark) 0.04f else 0.20f)
                        )
                    ),
                    RoundedCornerShape(50)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    NavPillItem(
                        selected = current == item.screen,
                        icon = item.icon,
                        label = item.label,
                        onClick = { onSelect(item.screen) }
                    )
                }
            }
        }
    }
}

@Composable
fun NavPillItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalAethelColors.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) c.accentCyan.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(200), label = "navBg"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) c.accentCyan else c.textMuted,
        animationSpec = tween(200), label = "navIcon"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = if (selected) 18.dp else 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(20.dp))
            AnimatedVisibility(visible = selected, enter = fadeIn(tween(150)) + expandHorizontally(), exit = fadeOut(tween(100)) + shrinkHorizontally()) {
                Text(label, fontSize = 12.sp, color = iconColor, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Dashboard
// ──────────────────────────────────────────────────────────────────────────────

// The launcher icon art is a black "8" glyph on a black background. For light mode,
// swap the background to white in-place rather than shipping a second icon asset.
private fun recolorBlackBackgroundToWhite(bmp: android.graphics.Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val tLow = 10.0
    val tHigh = 60.0
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        if (luminance >= tHigh) continue
        val newR: Int
        val newG: Int
        val newB: Int
        if (luminance <= tLow) {
            newR = 255; newG = 255; newB = 255
        } else {
            val f = (luminance - tLow) / (tHigh - tLow)
            newR = (r * f + 255 * (1 - f)).toInt().coerceIn(0, 255)
            newG = (g * f + 255 * (1 - f)).toInt().coerceIn(0, 255)
            newB = (b * f + 255 * (1 - f)).toInt().coerceIn(0, 255)
        }
        pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
}

@Composable
fun DashboardScreen(ctx: Context, isDark: Boolean, onToggleTheme: () -> Unit) {
    val c = LocalAethelColors.current
    val scope = rememberCoroutineScope()
    var apiStatus by remember { mutableStateOf<Boolean?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var apiUrl by remember { mutableStateOf(AppPrefs.getApiUrl(ctx)) }
    var gatewayEnabled by remember { mutableStateOf(AppPrefs.getGatewayEnabled(ctx)) }
    var codexGatewayEnabled by remember { mutableStateOf(AppPrefs.getCodexGatewayEnabled(ctx)) }
    var history by remember { mutableStateOf(AppPrefs.getHistory(ctx)) }
    var totalCount by remember { mutableStateOf(AppPrefs.getTotalCount(ctx)) }
    var approvedCount by remember { mutableStateOf(AppPrefs.getApprovedCount(ctx)) }
    var deniedCount by remember { mutableStateOf(AppPrefs.getDeniedCount(ctx)) }
    var statsPopupFilter by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    // Poll WebSocket connection state and stats every 2s
    LaunchedEffect(Unit) {
        while (true) {
            apiUrl              = AppPrefs.getApiUrl(ctx)
            gatewayEnabled      = AppPrefs.getGatewayEnabled(ctx)
            codexGatewayEnabled = AppPrefs.getCodexGatewayEnabled(ctx)
            val anyEnabled = gatewayEnabled || codexGatewayEnabled
            apiStatus     = if (anyEnabled) AethelHookWebSocket.isConnected else false
            history       = AppPrefs.getHistory(ctx)
            totalCount    = AppPrefs.getTotalCount(ctx)
            approvedCount = AppPrefs.getApprovedCount(ctx)
            deniedCount   = AppPrefs.getDeniedCount(ctx)
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 56.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ctx = LocalContext.current
            val iconBitmap = remember(ctx, isDark) {
                val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!
                val bmp = android.graphics.Bitmap.createBitmap(176, 176, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 176, 176)
                d.draw(android.graphics.Canvas(bmp))
                if (!isDark) recolorBlackBackgroundToWhite(bmp)
                bmp.asImageBitmap()
            }
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ÆthelHook", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)
                Text("Agent Permission Gateway", fontSize = 12.sp, color = c.textSecondary)
            }
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = "Toggle theme",
                    tint = c.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Gateway status hero card
        LiquidGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val anyGatewayEnabled = gatewayEnabled || codexGatewayEnabled
                val statusTitle = when {
                    !anyGatewayEnabled -> "Gateway Disabled"
                    apiStatus == true  -> "Gateway Live"
                    apiStatus == false -> "Gateway Offline"
                    else               -> "Connecting..."
                }
                val connType = AethelHookWebSocket.connectionType
                val lanIpNow = AppPrefs.getLanIp(ctx)
                val tsIpNow  = AppPrefs.getTailscaleIp(ctx)
                val statusSub = when {
                    !anyGatewayEnabled -> "Toggle a gateway ON to enable phone approvals"
                    lanIpNow.isBlank() && tsIpNow.isBlank()
                                      -> "Open Settings - connect to the same Wi-Fi as your PC to auto-discover"
                    apiStatus == true -> when (connType) {
                        AethelHookWebSocket.ConnectionType.LAN       -> "LAN · ${maskMiddle(lanIpNow)}"
                        AethelHookWebSocket.ConnectionType.TAILSCALE -> "Tailscale · ${maskMiddle(tsIpNow)}"
                        else                                          -> maskIpInText(apiUrl)
                    }
                    apiStatus == false -> when {
                        lanIpNow.isBlank() -> "No LAN IP yet - ensure PC is on the same Wi-Fi"
                        else               -> "Cannot reach ${maskIpInText(apiUrl)}"
                    }
                    else -> "Checking connection..."
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    PulseDot(connected = apiStatus, disabled = !anyGatewayEnabled)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(statusTitle, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.textPrimary)
                        Text(statusSub, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            apiUrl    = AppPrefs.getApiUrl(ctx)
                            apiStatus = if (gatewayEnabled) AethelHookWebSocket.isConnected else false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = c.accentCyan, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = c.divider.copy(alpha = 0.35f))

                // Claude Code gateway toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (gatewayEnabled) "Claude Code - Active" else "Claude Code - Inactive",
                            color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (gatewayEnabled) "Tool calls route to phone" else "Approve dialogs in IDE",
                            color = c.textSecondary, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = gatewayEnabled,
                        onCheckedChange = { enabled ->
                            gatewayEnabled = enabled
                            AppPrefs.setGatewayEnabled(ctx, enabled)
                            val url = AppPrefs.getApiUrl(ctx)
                            val anyEnabled = enabled || AppPrefs.getCodexGatewayEnabled(ctx)
                            if (enabled) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val client = AethelHookWebSocket.newBoundHttpClient(ctx)
                                            val req = Request.Builder()
                                                .url("$url/gateway/activate")
                                                .post("".toRequestBody())
                                                .build()
                                            client.newCall(req).execute().close()
                                        } catch (_: Exception) {}
                                    }
                                }
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val client = AethelHookWebSocket.newBoundHttpClient(ctx)
                                            val req = Request.Builder()
                                                .url("$url/gateway/deactivate")
                                                .post("".toRequestBody())
                                                .build()
                                            client.newCall(req).execute().close()
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            if (anyEnabled) AethelHookWebSocketService.start(ctx)
                            else AethelHookWebSocketService.stop(ctx)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = c.bgDeep,
                            checkedTrackColor   = c.accentCyan,
                            uncheckedThumbColor = c.textSecondary,
                            uncheckedTrackColor = c.divider
                        )
                    )
                }

                HorizontalDivider(color = c.divider.copy(alpha = 0.20f))

                // Codex gateway toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (codexGatewayEnabled) "Codex - Active" else "Codex - Inactive",
                            color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (codexGatewayEnabled) "Tool calls route to phone" else "Approve dialogs in Codex",
                            color = c.textSecondary, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = codexGatewayEnabled,
                        onCheckedChange = { enabled ->
                            codexGatewayEnabled = enabled
                            AppPrefs.setCodexGatewayEnabled(ctx, enabled)
                            val url = AppPrefs.getApiUrl(ctx)
                            val anyEnabled = enabled || AppPrefs.getGatewayEnabled(ctx)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = AethelHookWebSocket.newBoundHttpClient(ctx)
                                        val endpoint = if (enabled) "codex/gateway/activate" else "codex/gateway/deactivate"
                                        val req = Request.Builder()
                                            .url("$url/$endpoint")
                                            .post("".toRequestBody())
                                            .build()
                                        client.newCall(req).execute().close()
                                    } catch (_: Exception) {}
                                }
                            }
                            if (anyEnabled) AethelHookWebSocketService.start(ctx)
                            else AethelHookWebSocketService.stop(ctx)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = c.bgDeep,
                            checkedTrackColor   = c.accentCyan,
                            uncheckedThumbColor = c.textSecondary,
                            uncheckedTrackColor = c.divider
                        )
                    )
                }
            }
        }

        // Stats row - counters are persistent and not capped by history list size
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip(Modifier.weight(1f), totalCount.toString(),    "Total",    c.accentCyan,  Icons.Default.Add)
            StatChip(Modifier.weight(1f), approvedCount.toString(), "Approved", c.accentGreen, Icons.Default.Done,
                onClick = { if (approvedCount > 0) statsPopupFilter = "approved" })
            StatChip(Modifier.weight(1f), deniedCount.toString(),   "Denied",   c.accentRed,   Icons.Default.Close,
                onClick = { if (deniedCount > 0) statsPopupFilter = "denied" })
        }

        // Test card
        LiquidGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Test Pipeline", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                Text("Fire a mock approval request to verify the full hook-to-phone flow.", color = c.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                GlassButton(
                    onClick = {
                        scope.launch {
                            testing = true; testResult = null
                            testResult = sendTestEvent(apiUrl, ctx); testing = false
                        }
                    },
                    enabled = !testing,
                    color = c.accentCyan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), color = c.accentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (testing) "Sending..." else "Send Test Ping", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                AnimatedVisibility(visible = testResult != null) {
                    val ok = testResult?.startsWith("OK") == true
                    val col = if (ok) c.accentGreen else c.accentRed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(col.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(if (ok) Icons.Default.Done else Icons.Default.Warning, contentDescription = null, tint = col, modifier = Modifier.size(15.dp))
                        Text(testResult ?: "", color = c.textPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Stats detail popup - renders in its own window above everything
    if (statsPopupFilter != null) {
        StatsDetailPopup(
            filter       = statsPopupFilter!!,
            history      = history,
            onDismiss    = { statsPopupFilter = null },
            fmt          = fmt
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// History
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun HistoryScreen(ctx: Context) {
    val c = LocalAethelColors.current
    var history by remember { mutableStateOf(AppPrefs.getHistory(ctx)) }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    // Clear history if 48+ hours have passed since last wipe
    LaunchedEffect(Unit) {
        AppPrefs.maybeClearOldHistory(ctx)
        history = AppPrefs.getHistory(ctx)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("History", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = { AppPrefs.clearHistory(ctx); history = emptyList() }) {
                    Text("Clear", color = c.accentRed, fontSize = 13.sp)
                }
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(c.textMuted.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(36.dp))
                    }
                    Text("No decisions yet", color = c.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Approval requests will appear here", color = c.textMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { record -> HistoryCard(record = record, fmt = fmt) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun HistoryCard(record: ApprovalRecord, fmt: SimpleDateFormat) {
    val c = LocalAethelColors.current
    var expanded by remember { mutableStateOf(false) }

    val isAllow       = record.decision in listOf("allow", "allow_once")
    val isAlwaysAllow = record.decision.startsWith("always_allow")
    val isDenyReason  = record.decision == "deny_with_reason"
    val isDeny        = record.decision == "deny"
    val badgeColor = when {
        isAlwaysAllow -> c.accentCyan
        isAllow       -> c.accentGreen
        isDenyReason  -> c.accentRed
        isDeny        -> c.accentRed
        else          -> c.accentAmber
    }
    val badgeText = when {
        isAlwaysAllow -> "Always"
        isAllow       -> "Allowed"
        isDenyReason  -> "Denied"
        isDeny        -> "Denied"
        else          -> "Pending"
    }
    val badgeIcon = when {
        isAlwaysAllow -> Icons.Default.Done
        isAllow       -> Icons.Default.Done
        isDenyReason  -> Icons.Default.Close
        isDeny        -> Icons.Default.Close
        else          -> Icons.Default.Refresh
    }

    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.20f else 0.75f),
                        Color.White.copy(alpha = if (c.isDark) 0.04f else 0.18f)
                    )
                ),
                shape
            )
            .clickable { expanded = !expanded }
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(badgeColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(badgeIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(record.toolName.ifBlank { "tool" }, fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 14.sp)
                    Text(fmt.format(Date(record.timestampMs)), color = c.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(badgeText, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = c.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Preview - collapses to 2 lines, expands to full command
            Spacer(Modifier.height(6.dp))
            Text(
                text = record.preview,
                color = c.textSecondary,
                fontSize = 12.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                fontFamily = if (expanded) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Settings
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(ctx: Context, isDark: Boolean, onToggleTheme: () -> Unit) {
    val c = LocalAethelColors.current
    var lanIp       by remember { mutableStateOf(AppPrefs.getLanIp(ctx)) }
    var tailscaleIp by remember { mutableStateOf(AppPrefs.getTailscaleIp(ctx)) }
    var port        by remember { mutableStateOf(AppPrefs.getPort(ctx).toString()) }
    var timeout     by remember { mutableStateOf(AppPrefs.getTimeout(ctx).toString()) }
    var apiToken    by remember { mutableStateOf(AppPrefs.getApiToken(ctx)) }
    var saved       by remember { mutableStateOf(false) }
    var lanIpRevealed       by remember { mutableStateOf(false) }
    var tailscaleIpRevealed by remember { mutableStateOf(false) }
    var apiTokenRevealed    by remember { mutableStateOf(false) }
    var pairingMsg  by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (isError, message)
    val scope = rememberCoroutineScope()

    // Track the last beacon-synced values so we only overwrite fields the user hasn't edited
    var syncedLanIp    by remember { mutableStateOf(lanIp) }
    var syncedTsIp     by remember { mutableStateOf(tailscaleIp) }
    var syncedApiToken by remember { mutableStateOf(apiToken) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val payload = AethelHookPairing.parsePayload(raw)
        if (payload == null) {
            pairingMsg = true to "Not an AethelHook pairing code"
            return@rememberLauncherForActivityResult
        }
        pairingMsg = false to "Pairing…"
        scope.launch {
            AethelHookPairing.claim(payload).fold(
                onSuccess = { claimed ->
                    AppPrefs.setLanIp(ctx, claimed.ip)
                    AppPrefs.setPort(ctx, claimed.port)
                    AppPrefs.setApiToken(ctx, claimed.token)
                    // From the QR scan itself (the trust root here), not the claim
                    // response - claim() already refused to run at all without it.
                    AppPrefs.setCertFingerprint(ctx, payload.c ?: "")
                    lanIp = claimed.ip
                    port = claimed.port.toString()
                    apiToken = claimed.token
                    syncedLanIp = claimed.ip
                    syncedApiToken = claimed.token
                    AethelHookWebSocket.evaluateAndSwitchUrl(ctx)
                    pairingMsg = false to "Paired! Settings updated."
                },
                onFailure = { e -> pairingMsg = true to "Pairing failed: ${e.message}" }
            )
        }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false))
        else pairingMsg = true to "Camera permission is required to scan"
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            val newLanIp = AppPrefs.getLanIp(ctx)
            val newTsIp  = AppPrefs.getTailscaleIp(ctx)
            val newToken = AppPrefs.getApiToken(ctx)
            if (newLanIp != syncedLanIp && lanIp == syncedLanIp) {
                lanIp = newLanIp; syncedLanIp = newLanIp
            }
            if (newTsIp != syncedTsIp && tailscaleIp == syncedTsIp) {
                tailscaleIp = newTsIp; syncedTsIp = newTsIp
            }
            if (newToken != syncedApiToken && apiToken == syncedApiToken) {
                apiToken = newToken; syncedApiToken = newToken
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 56.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)

        // Appearance card
        LiquidGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Appearance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(if (isDark) "Dark Mode" else "Light Mode", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = { onToggleTheme() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.bgDeep,
                            checkedTrackColor = c.accentCyan,
                            uncheckedThumbColor = c.textSecondary,
                            uncheckedTrackColor = c.divider
                        )
                    )
                }
            }
        }

        // API config card
        LiquidGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("API Configuration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                GlassButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false))
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    color = c.accentCyan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR to Pair", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                pairingMsg?.let { (isError, msg) ->
                    Text(
                        msg,
                        color = if (isError) c.accentRed else c.accentGreen,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // LAN IP
                SensitiveTextField(
                    value = lanIp,
                    onValueChange = { v -> if (v.all { it.isDigit() || it == '.' }) { lanIp = v; saved = false } },
                    label = "LAN IP  (auto-discovered on Wi-Fi)",
                    placeholder = "192.168.x.x",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Default.Link,
                    revealed = lanIpRevealed,
                    onToggleReveal = {
                        if (lanIpRevealed) lanIpRevealed = false
                        else requestRevealAuth(ctx, "LAN IP") { lanIpRevealed = true }
                    }
                )
                if (lanIp.isNotBlank()) {
                    Text(
                        "→ http://${maskMiddle(lanIp)}:${port.ifBlank { "5264" }}",
                        color = c.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // Tailscale IP
                SensitiveTextField(
                    value = tailscaleIp,
                    onValueChange = { v -> if (v.all { it.isDigit() || it == '.' }) { tailscaleIp = v; saved = false } },
                    label = "Tailscale IP  (mobile data - enter once)",
                    placeholder = "100.x.x.x",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Default.Link,
                    revealed = tailscaleIpRevealed,
                    onToggleReveal = {
                        if (tailscaleIpRevealed) tailscaleIpRevealed = false
                        else requestRevealAuth(ctx, "Tailscale IP") { tailscaleIpRevealed = true }
                    }
                )
                if (tailscaleIp.isNotBlank()) {
                    Text(
                        "→ http://${maskMiddle(tailscaleIp)}:${port.ifBlank { "5264" }}",
                        color = c.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // Port
                AethelTextField(
                    value = port,
                    onValueChange = { if (it.all(Char::isDigit)) { port = it; saved = false } },
                    label = "Port",
                    placeholder = "5264",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Default.Refresh
                )
                AethelTextField(
                    value = timeout,
                    onValueChange = { timeout = it; saved = false },
                    label = "Wait Timeout (seconds)",
                    placeholder = "80",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Default.Refresh
                )
                SensitiveTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it; saved = false },
                    label = "API Token  (auto-filled by QR pairing)",
                    placeholder = "scan the QR shown on the PC's /pair page",
                    icon = Icons.Default.Lock,
                    revealed = apiTokenRevealed,
                    onToggleReveal = {
                        if (apiTokenRevealed) apiTokenRevealed = false
                        else requestRevealAuth(ctx, "API Token") { apiTokenRevealed = true }
                    }
                )
                if (apiToken.isNotBlank()) {
                    Text(
                        "Token: ${maskMiddle(apiToken)}",
                        color = c.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                GlassButton(
                    onClick = {
                        AppPrefs.setLanIp(ctx, lanIp)
                        AppPrefs.setTailscaleIp(ctx, tailscaleIp)
                        AppPrefs.setPort(ctx, port.toIntOrNull() ?: 5264)
                        AppPrefs.setTimeout(ctx, timeout.toIntOrNull() ?: 80)
                        if (apiToken.isNotBlank()) AppPrefs.setApiToken(ctx, apiToken)
                        saved = true
                        AethelHookWebSocket.evaluateAndSwitchUrl(ctx)
                    },
                    color = c.accentPurple,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Settings", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                AnimatedVisibility(visible = saved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, tint = c.accentGreen, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Settings saved", color = c.accentGreen, fontSize = 13.sp)
                    }
                }
            }
        }

        // About card
        LiquidGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("About", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                }
                InfoRow("App", "ÆthelHook v1.0")
                Text(
                    text = "Copyright © 2026 ÆthelSt8\nAll rights reserved",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared composables
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalAethelColors.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.22f else 0.80f),
                        Color.White.copy(alpha = if (c.isDark) 0.04f else 0.20f)
                    )
                ),
                shape
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        // Inner specular highlight - simulates light hitting the top edge of the glass
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (c.isDark) 0.06f else 0.16f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val c = LocalAethelColors.current
    val effectiveColor = if (enabled) color else c.textMuted
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(effectiveColor.copy(alpha = 0.12f))
            .border(1.dp, effectiveColor.copy(alpha = if (enabled) 0.35f else 0.12f), RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides effectiveColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = { content() }
            )
        }
    }
}

@Composable
fun PulseDot(connected: Boolean?, disabled: Boolean = false) {
    val c = LocalAethelColors.current
    val inf = rememberInfiniteTransition(label = "pulse")
    val scale by inf.animateFloat(
        initialValue = 0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "scale"
    )
    val dotColor = when {
        disabled           -> c.textMuted
        connected == true  -> c.accentGreen
        connected == false -> c.accentRed
        else               -> c.accentAmber
    }
    val shouldPulse = connected == true && !disabled
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).background(dotColor.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .scale(if (shouldPulse) scale else 1f)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
fun StatChip(modifier: Modifier, value: String, label: String, color: Color, icon: ImageVector, onClick: (() -> Unit)? = null) {
    val c = LocalAethelColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (c.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.50f))
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.20f else 0.75f),
                        Color.White.copy(alpha = if (c.isDark) 0.03f else 0.15f)
                    )
                ),
                RoundedCornerShape(16.dp)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 22.sp)
            Text(label, color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
fun AethelTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    icon: ImageVector
) {
    val c = LocalAethelColors.current
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = c.textMuted) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = c.accentCyan) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = c.accentCyan,
            unfocusedBorderColor  = c.divider,
            focusedLabelColor     = c.accentCyan,
            unfocusedLabelColor   = c.textSecondary,
            cursorColor           = c.accentCyan,
            focusedTextColor      = c.textPrimary,
            unfocusedTextColor    = c.textPrimary,
        )
    )
}

// Like AethelTextField, but for values we don't want visible by default (IP addresses,
// API tokens): masks all but the first/last character until the user taps the trailing
// eye icon to reveal it. Masking is purely visual (VisualTransformation) so the field
// stays fully editable while hidden, same as a password field.
@Composable
fun SensitiveTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    icon: ImageVector,
    revealed: Boolean,
    onToggleReveal: () -> Unit
) {
    val c = LocalAethelColors.current
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = c.textMuted) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = c.accentCyan) },
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (revealed) "Hide $label" else "Show $label",
                    tint = c.textSecondary
                )
            }
        },
        visualTransformation = if (revealed) androidx.compose.ui.text.input.VisualTransformation.None else MaskMiddleVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = c.accentCyan,
            unfocusedBorderColor  = c.divider,
            focusedLabelColor     = c.accentCyan,
            unfocusedLabelColor   = c.textSecondary,
            cursorColor           = c.accentCyan,
            focusedTextColor      = c.textPrimary,
            unfocusedTextColor    = c.textPrimary,
        )
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    val c = LocalAethelColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = c.textSecondary, fontSize = 13.sp)
        Text(value, color = c.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Agent summary popup - shown when user taps a "finished working" notification
// ──────────────────────────────────────────────────────────────────────────────

// Parsed segments of a markdown body (plain text vs. tables)
private sealed class MdSegment {
    data class TextBlock(val text: String) : MdSegment()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdSegment()
}

private fun parseMdRow(line: String): List<String> =
    line.trim().trimStart('|').trimEnd('|').split("|").map { it.trim() }

private val MD_SEP_RE = Regex("""^\|[\s\-:|]+(\|[\s\-:|]+)*\|$""")

private fun parseMd(text: String): List<MdSegment> {
    val lines = text.lines()
    val result = mutableListOf<MdSegment>()
    val buf = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trim().startsWith("|") && i + 1 < lines.size &&
            lines[i + 1].trim().matches(MD_SEP_RE)) {
            if (buf.isNotBlank()) {
                result += MdSegment.TextBlock(buf.toString().trimEnd())
                buf.clear()
            }
            val headers = parseMdRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows += parseMdRow(lines[i++])
            }
            result += MdSegment.Table(headers, rows)
        } else {
            buf.appendLine(line)
            i++
        }
    }
    if (buf.isNotBlank()) result += MdSegment.TextBlock(buf.toString().trimEnd())
    return result
}

@Composable
private fun MdTable(table: MdSegment.Table) {
    val c = LocalAethelColors.current
    val colCount = table.headers.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, c.divider.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Header row - weight(1f) per cell keeps all columns the same width
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.textPrimary.copy(alpha = 0.08f))
                .height(IntrinsicSize.Min)
        ) {
            table.headers.forEachIndexed { idx, header ->
                if (idx > 0) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider.copy(alpha = 0.40f)))
                }
                Text(
                    header,
                    color = c.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        HorizontalDivider(color = c.divider.copy(alpha = 0.35f))
        // Data rows
        table.rows.forEachIndexed { idx, row ->
            val cells = if (row.size < colCount) row + List(colCount - row.size) { "" } else row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (idx % 2 == 1) Modifier.background(c.textPrimary.copy(alpha = 0.04f)) else Modifier)
                    .height(IntrinsicSize.Min)
            ) {
                cells.take(colCount).forEachIndexed { cellIdx, cell ->
                    if (cellIdx > 0) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider.copy(alpha = 0.25f)))
                    }
                    Text(
                        cell,
                        color = c.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            if (idx < table.rows.lastIndex)
                HorizontalDivider(color = c.divider.copy(alpha = 0.15f))
        }
    }
}

@Composable
private fun MdBody(text: String) {
    val c = LocalAethelColors.current
    val segments = remember(text) { parseMd(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MdSegment.TextBlock -> SelectionContainer {
                    Text(seg.text, color = c.textPrimary, fontSize = 14.sp, lineHeight = 22.sp)
                }
                is MdSegment.Table -> MdTable(seg)
            }
        }
    }
}

@Composable
fun SummaryPopup(title: String, body: String, onDismiss: () -> Unit) {
    val c = LocalAethelColors.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.88f),
                exit  = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.88f)
            ) {
                val shape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (c.isDark) Color.Black.copy(alpha = 0.88f)
                            else          Color.White.copy(alpha = 0.92f)
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (c.isDark) 0.28f else 0.90f),
                                    Color.White.copy(alpha = if (c.isDark) 0.06f else 0.25f)
                                )
                            ),
                            shape
                        )
                        .clickable(enabled = false) {}
                ) {
                    // Specular highlight
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = if (c.isDark) 0.08f else 0.20f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(c.accentCyan.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = c.accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.accentCyan)
                                Text("Agent Summary", color = c.textMuted, fontSize = 11.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c.textMuted.copy(alpha = 0.10f))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = c.divider.copy(alpha = 0.30f))
                        Spacer(Modifier.height(12.dp))

                        // Scrollable body - with a visible thumb scrollbar drawn alongside
                        val scrollState = rememberScrollState()
                        val scrollOffset = scrollState.value  // observed here → redraws thumb on scroll
                        val isDark = c.isDark
                        val thumbColor = c.accentCyan
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = (screenHeightDp * 0.60f).dp)
                                .drawWithContent {
                                    drawContent()
                                    val maxScroll = scrollState.maxValue
                                    if (maxScroll > 0) {
                                        val barW  = 3.dp.toPx()
                                        val padR  = 2.dp.toPx()
                                        val vH    = size.height
                                        val thumbH = (vH / (vH + maxScroll)) * vH
                                        val thumbY = (scrollOffset.toFloat() / maxScroll) * (vH - thumbH)
                                        // Track
                                        drawRoundRect(
                                            color = Color.White.copy(alpha = if (isDark) 0.08f else 0.20f),
                                            topLeft = Offset(size.width - barW - padR, 0f),
                                            size = Size(barW, vH),
                                            cornerRadius = CornerRadius(barW / 2)
                                        )
                                        // Thumb
                                        drawRoundRect(
                                            color = thumbColor.copy(alpha = 0.70f),
                                            topLeft = Offset(size.width - barW - padR, thumbY),
                                            size = Size(barW, thumbH),
                                            cornerRadius = CornerRadius(barW / 2)
                                        )
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 8.dp)  // leave space for scrollbar thumb
                                    .verticalScroll(scrollState)
                            ) {
                                MdBody(text = body)
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        GlassButton(
                            onClick = onDismiss,
                            color = c.accentCyan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Done", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Stats detail popup
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun StatsDetailPopup(filter: String, history: List<ApprovalRecord>, onDismiss: () -> Unit, fmt: SimpleDateFormat) {
    val c = LocalAethelColors.current
    val isApprovedFilter = filter == "approved"
    val titleText  = if (isApprovedFilter) "Approved" else "Denied"
    val titleColor = if (isApprovedFilter) c.accentGreen else c.accentRed
    val filtered = remember(filter, history) {
        if (isApprovedFilter) history.filter { it.decision in APPROVED_DECISIONS }
        else                  history.filter { it.decision in DENIED_DECISIONS }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.88f),
                exit  = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.88f)
            ) {
                val shape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (c.isDark) Color.Black.copy(alpha = 0.88f)
                            else          Color.White.copy(alpha = 0.92f)
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (c.isDark) 0.28f else 0.90f),
                                    Color.White.copy(alpha = if (c.isDark) 0.06f else 0.25f)
                                )
                            ),
                            shape
                        )
                        .clickable(enabled = false) {}
                ) {
                    // Specular highlight
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = if (c.isDark) 0.08f else 0.20f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                titleText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = titleColor
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(titleColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("${filtered.size}", color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c.textMuted.copy(alpha = 0.10f))
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = c.divider.copy(alpha = 0.30f))
                        Spacer(Modifier.height(10.dp))

                        if (filtered.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No ${titleText.lowercase()} decisions in history",
                                    color = c.textSecondary,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filtered) { record ->
                                    PopupHistoryRow(record = record, isApproved = isApprovedFilter, fmt = fmt)
                                }
                                item { Spacer(Modifier.height(4.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PopupHistoryRow(record: ApprovalRecord, isApproved: Boolean, fmt: SimpleDateFormat) {
    val c = LocalAethelColors.current
    val color = if (isApproved) c.accentGreen else c.accentRed
    val icon  = if (isApproved) Icons.Default.Done else Icons.Default.Close

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.toolName.ifBlank { "tool" }, fontWeight = FontWeight.SemiBold, color = c.textPrimary, fontSize = 13.sp)
            Text(record.preview.take(70), color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            fmt.format(Date(record.timestampMs)),
            color = c.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Network helpers
// ──────────────────────────────────────────────────────────────────────────────

suspend fun sendTestEvent(baseUrl: String, ctx: android.content.Context): String = withContext(Dispatchers.IO) {
    try {
        val sessionId = UUID.randomUUID().toString()
        val json = """{"event_type":"APPROVAL_REQUEST","message":"Test from ÆthelHook app","detail":"test ping","session_id":"$sessionId","timestamp":"${Date()}"}"""
        val body    = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/hook/event").post(body).build()
        AethelHookWebSocket.newBoundHttpClient(ctx).newCall(request).execute().use { response ->
            if (response.isSuccessful) "OK - notification sent (${sessionId.take(8)}...)"
            else friendlyHttpError(response.code, response.body?.string(), "AethelHook")
        }
    } catch (e: Exception) { friendlyNetworkError(e) }
}
