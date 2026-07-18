package com.aethelhook.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object AethelHookWebSocket {

    private const val TAG = "AethelHookWS"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var running = false
    private var udpSocket: DatagramSocket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appCtx: Context? = null

    // Which interface the socket should be bound to when phone is the hotspot provider.
    @Volatile private var hotspotBindIp: String? = null

    @Volatile private var connected = false
    private var consecutiveFailures = 0

    enum class ConnectionType { NONE, LAN, TAILSCALE }
    @Volatile var connectionType: ConnectionType = ConnectionType.NONE
        private set

    val isConnected: Boolean get() = connected

    // Phase 2 (Session Access): chunked progress pings while a turn is running.
    // Latest-value only, not a log - SessionActivity accumulates its own running
    // list from what it collects while the screen is open. No system notification
    // is fired per chunk (that would spam one notification per tool call); only the
    // existing final "agent_done" Stop notification remains user-visible when the
    // app is backgrounded.
    val sessionUpdates = MutableStateFlow<JSONObject?>(null)

    // Approvals/questions/plan-reviews used to have zero presence in the Sessions chat -
    // they only ever showed as a system notification + a full-screen Activity, with no
    // `cwd` on the payload to route them into SessionChatStore. Now that the three hook
    // scripts forward `cwd`, this flow carries the same raw event alongside (not instead
    // of) the existing notification, so SessionActivity can render it as an inline
    // actionable chat item too - useful when the app is already open and foregrounded.
    val actionableEvents = MutableStateFlow<JSONObject?>(null)

    private fun wsUrl(ctx: Context): String? {
        val base  = AppPrefs.getApiUrl(ctx).ifBlank { return null }
        val token = AppPrefs.getApiToken(ctx)
        val ws    = base.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        return if (token.isNotBlank()) "$ws?token=$token" else ws
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect(ctx: Context) {
        if (running) return
        running = true
        appCtx = ctx.applicationContext
        Log.d(TAG, "Starting - LAN + Tailscale auto-switch enabled")
        startNetworkMonitor(ctx)
        evaluateAndSwitchUrl(ctx)
        scheduleConnect(ctx)
        startUdpDiscovery(ctx)
    }

    fun disconnect() {
        running = false
        connected = false
        connectionType = ConnectionType.NONE
        reconnectJob?.cancel()
        webSocket?.close(1000, "App stopped")
        webSocket = null
        udpSocket?.close()
        udpSocket = null
        appCtx?.let { ctx ->
            networkCallback?.let {
                try {
                    (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                        .unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
        }
        networkCallback = null
        appCtx = null
    }

    fun forceReconnect(ctx: Context) {
        if (!running) return
        consecutiveFailures = 0
        webSocket?.close(1000, "Settings changed")
        webSocket = null
        evaluateAndSwitchUrl(ctx)
    }

    // ── Network monitoring - auto-switches URL on WiFi ↔ mobile data ─────────

    private fun startNetworkMonitor(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                evaluateAndSwitchUrl(ctx, caps)
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost - waiting for reconnect")
                connected = false
                connectionType = ConnectionType.NONE
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            Log.d(TAG, "Network monitor registered")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
        }
    }

    /**
     * Picks the right URL based on current network and updates api_url if it changed.
     *
     * Priority:
     *   1. Same subnet as PC (WiFi or phone-as-hotspot) → LAN URL, always preferred
     *   2. Mobile data + Tailscale active → Tailscale URL
     *   3. Anything else → LAN best-effort (will likely fail; FCM handles notifications)
     *
     * "Same subnet" is detected via hotspotBindIp (set by the UDP beacon when the phone's
     * own IP shares a /24 with the PC). This covers both direct WiFi and hotspot scenarios
     * without relying on TRANSPORT_WIFI, which is false when the phone IS the hotspot.
     */
    fun evaluateAndSwitchUrl(ctx: Context, caps: NetworkCapabilities? = null) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeCaps = caps ?: cm.getNetworkCapabilities(cm.activeNetwork)

        val onSameSubnet = hotspotBindIp?.let { isInterfaceAvailable(it) } == true
        val onWifi       = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                           activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val tailscaleActive = isTailscaleActive()

        // Use Tailscale only when we have no direct route to the PC
        val useTailscale = tailscaleActive && !onSameSubnet && !onWifi

        val targetUrl = if (useTailscale) {
            AppPrefs.getTailscaleUrl(ctx).ifBlank { AppPrefs.getLanUrl(ctx) }
        } else {
            AppPrefs.getLanUrl(ctx)
        }

        if (targetUrl.isBlank()) {
            Log.d(TAG, "No URL configured - waiting for beacon or manual entry in Settings")
            return
        }

        val current = AppPrefs.getApiUrl(ctx)
        if (targetUrl != current) {
            Log.d(TAG, "Network switch: ${if (onWifi) "WiFi→LAN" else if (tailscaleActive) "Cellular→Tailscale" else "Cellular→LAN(best-effort)"} · $current → $targetUrl")
            AppPrefs.setApiUrl(ctx, targetUrl)
            hotspotBindIp = null
            consecutiveFailures = 0
            if (running) {
                webSocket?.close(1000, "Network changed")
                webSocket = null
                scheduleConnect(ctx, 500)
            }
        }
    }

    /** Returns true if a Tailscale interface (100.x.x.x) is active on this device. */
    private fun isTailscaleActive(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.any { it.hostAddress?.startsWith("100.") == true } ?: false
    } catch (_: Exception) { false }

    // ── WebSocket connect / reconnect ─────────────────────────────────────────

    private fun scheduleConnect(ctx: Context, delayMs: Long = 0) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!running) return@launch
            doConnect(ctx)
        }
    }

    private fun doConnect(ctx: Context) {
        val url = wsUrl(ctx) ?: run {
            Log.d(TAG, "No gateway URL - waiting for beacon or Settings entry")
            if (running) scheduleConnect(ctx, 5_000)
            return
        }

        val bindIp = hotspotBindIp?.takeIf { isInterfaceAvailable(it) }
        if (bindIp == null) hotspotBindIp = null

        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .apply { if (bindIp != null) socketFactory(BoundSocketFactory(bindIp)) }
            .pinnedFromPrefs(ctx)
            .build()

        Log.d(TAG, "Connecting → ${url.substringBefore("?")}${if (bindIp != null) " (bound to $bindIp)" else ""}")

        webSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {

            // A superseded socket (replaced by a later reconnect, e.g. triggered by Tailscale's
            // tun interface flapping) can still fire callbacks while it's gracefully closing.
            // Ignore anything not coming from the currently-tracked socket to avoid duplicate
            // notifications and hard-cancel the stale one instead of waiting on its close handshake.
            fun isStale(ws: WebSocket): Boolean {
                if (ws !== webSocket) {
                    ws.cancel()
                    return true
                }
                return false
            }

            override fun onOpen(ws: WebSocket, response: Response) {
                if (isStale(ws)) return
                // NOT "connected" yet - the raw socket handshake succeeds before the server
                // has decided whether to register this connection (it may still be gated
                // behind another device's transfer approval, see /ws in Program.cs). `connected`
                // only flips true once the server's own "connected" message arrives below,
                // confirming this socket was actually registered in WsClientStore.
                consecutiveFailures = 0
                connectionType = if (url.substringAfter("://").startsWith("100."))
                    ConnectionType.TAILSCALE else ConnectionType.LAN
                Log.d(TAG, "Socket open via ${connectionType.name} (${url.substringBefore("?")}) - awaiting registration")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isStale(ws)) return
                handleMessage(ctx, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isStale(ws)) return
                connected = false
                connectionType = ConnectionType.NONE
                consecutiveFailures++
                Log.w(TAG, "WS failed: ${t.message} (attempt #$consecutiveFailures)")
                if (consecutiveFailures >= 3) tryTailscaleFallback(ctx)
                if (running) scheduleConnect(ctx, 5_000)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (isStale(ws)) return
                connected = false
                connectionType = ConnectionType.NONE
                Log.d(TAG, "WS closed ($code $reason) - retrying in 3s")
                if (running) scheduleConnect(ctx, 3_000)
            }
        })
    }

    /**
     * Safety net: after 3 consecutive WS failures, re-evaluate the network.
     * The network monitor handles proactive switching; this catches edge cases
     * where the callback didn't fire (e.g. gradual signal loss).
     */
    private fun tryTailscaleFallback(ctx: Context) {
        val tsUrl = AppPrefs.getTailscaleUrl(ctx).ifBlank { return }
        if (tsUrl == AppPrefs.getApiUrl(ctx)) return
        Log.d(TAG, "3 consecutive failures - evaluating Tailscale fallback")
        consecutiveFailures = 0
        evaluateAndSwitchUrl(ctx)
    }

    // ── UDP beacon - auto-discovers PC's LAN and Tailscale IPs ───────────────

    private fun startUdpDiscovery(ctx: Context) {
        scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(47263))
                socket.soTimeout = 5000
                udpSocket = socket
                Log.d(TAG, "UDP discovery listening on port 47263")
                val buf = ByteArray(256)
                while (running && !socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }

                    val msg = String(packet.data, 0, packet.length)
                    if (!msg.startsWith("AETHELHOOK:")) continue

                    // Beacon format: "AETHELHOOK:{port}" or "AETHELHOOK:{port}:{tailscaleIp}"
                    // No token - the token is only ever handed out via QR pairing (/pair/claim).
                    val parts        = msg.removePrefix("AETHELHOOK:").trim().split(":")
                    val port         = parts.getOrNull(0)?.toIntOrNull() ?: 5264
                    val tailscaleIp  = parts.getOrNull(1)?.takeIf { it.startsWith("100.") }
                    val senderIp     = packet.address?.hostAddress ?: continue

                    // Once bound to a PC, ignore beacons from other PCs on the same network.
                    // Only accept a new sender if lan_ip is empty (first-time setup).
                    val currentLanIp = AppPrefs.getLanIp(ctx)
                    if (currentLanIp.isNotEmpty() && senderIp != currentLanIp) {
                        Log.d(TAG, "Beacon from $senderIp ignored - locked to $currentLanIp")
                        continue
                    }

                    // Persist discovered IPs + token
                    AppPrefs.setPort(ctx, port)
                    if (senderIp != AppPrefs.getLanIp(ctx)) {
                        Log.d(TAG, "Beacon: new LAN IP $senderIp")
                        AppPrefs.setLanIp(ctx, senderIp)
                    }
                    if (tailscaleIp != null && tailscaleIp != AppPrefs.getTailscaleIp(ctx)) {
                        Log.d(TAG, "Beacon: new Tailscale IP $tailscaleIp")
                        AppPrefs.setTailscaleIp(ctx, tailscaleIp)
                    }

                    // Update hotspot bind IP when phone is on same /24 as PC
                    val localIp = findLocalIpForSubnet(senderIp)
                    hotspotBindIp = localIp  // null when not on same subnet

                    // Re-evaluate which URL to use now that we have fresh IPs
                    evaluateAndSwitchUrl(ctx)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP discovery error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    // ── Decision sending ──────────────────────────────────────────────────────

    fun sendDecision(sessionId: String, decision: String, reason: String = "") {
        val json = JSONObject().apply {
            put("type",       "decision")
            put("session_id", sessionId)
            put("decision",   decision)
            put("reason",     reason)
        }.toString()
        webSocket?.send(json)
        Log.d(TAG, "Decision sent over WS: session=$sessionId decision=$decision")
    }

    fun sendQuestionAnswer(sessionId: String, answers: JSONObject) {
        val json = JSONObject().apply {
            put("type",       "question_answer")
            put("session_id", sessionId)
            put("answers",    answers)
        }.toString()
        webSocket?.send(json)
        Log.d(TAG, "Question answer sent over WS: session=$sessionId")
    }

    fun sendPlanReviewDecision(sessionId: String, decision: String, feedback: String = "") {
        val json = JSONObject().apply {
            put("type",       "plan_review_decision")
            put("session_id", sessionId)
            put("decision",   decision)
            put("feedback",   feedback)
        }.toString()
        webSocket?.send(json)
        Log.d(TAG, "Plan review decision sent over WS: session=$sessionId decision=$decision")
    }

    fun newBoundHttpClient(ctx: Context? = null): OkHttpClient {
        val bindIp = hotspotBindIp
        val token  = ctx?.let { AppPrefs.getApiToken(it) } ?: ""
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .apply { if (bindIp != null) socketFactory(BoundSocketFactory(bindIp)) }
            .apply {
                if (token.isNotBlank()) addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .addHeader("X-AethelHook-Token", token)
                            .build()
                    )
                }
            }
            .apply { if (ctx != null) pinnedFromPrefs(ctx) }
            .build()
    }

    // ── Message handling ──────────────────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission")
    private fun handleMessage(ctx: Context, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "connected"        -> { connected = true; Log.d(TAG, "Registered: ${obj.optString("message")}") }
            "approval_request" -> { showApprovalNotification(ctx, obj); actionableEvents.value = obj }
            "agent_done"       -> showDoneNotification(ctx, obj)
            "ask_question"     -> { showQuestionNotification(ctx, obj); actionableEvents.value = obj }
            "plan_review"      -> { showPlanReviewNotification(ctx, obj); actionableEvents.value = obj }
            "session_update"   -> sessionUpdates.value = obj
            "prompt_result"    -> sessionUpdates.value = obj
            "ack"              -> { Log.d(TAG, "Decision ack'd: ${obj.optString("session_id")} → ${obj.optString("decision")}"); actionableEvents.value = obj }
            "connection_transferred" -> {
                connected = false
                showInfoNotification(ctx, "Connection ended", obj.optString("message", "A new device was authorized on your PC"))
            }
            else               -> Log.d(TAG, "Unknown WS type: ${obj.optString("type")}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showDoneNotification(ctx: Context, obj: JSONObject) {
        val message = obj.optString("message", "Agent finished working")
        val detail  = obj.optString("detail", "")
        val title   = message.removeSuffix(" finished").removeSuffix(" finished working").ifBlank { "AethelHook" }
        val body    = detail.ifBlank { "Finished working" }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aethelhook_done", "AethelHook Done", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openPi = PendingIntent.getActivity(
            ctx, System.currentTimeMillis().toInt(),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("summary_title", title)
                putExtra("summary_body", body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(ctx).notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(ctx, "aethelhook_done")
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build()
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showApprovalNotification(ctx: Context, obj: JSONObject) {
        val sessionId   = obj.optString("session_id")
        val detail      = obj.optString("detail")
        val toolName    = obj.optString("tool_name").ifBlank { "run_command" }
        val commandName = obj.optString("command_name").ifBlank { detail.trim().split(" ").firstOrNull() ?: "tool" }
        val respondUrl  = obj.optString("respond_url")

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aethelhook_channel", "AethelHook Alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val notificationId = System.currentTimeMillis().toInt()

        val openIntent = Intent(ctx, ApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("session_id",      sessionId)
            putExtra("respond_url",     respondUrl)
            putExtra("tool_name",       toolName)
            putExtra("command_name",    commandName)
            putExtra("command_preview", detail)
            putExtra("notification_id", notificationId)
            putExtra("use_websocket",   true)
        }
        val openPi = PendingIntent.getActivity(
            ctx, notificationId + 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun makeIntent(action: String) =
            Intent(ctx, DecisionBroadcastReceiver::class.java).apply {
                this.action = action
                putExtra(DecisionBroadcastReceiver.EXTRA_SESSION_ID,      sessionId)
                putExtra(DecisionBroadcastReceiver.EXTRA_RESPOND_URL,     respondUrl)
                putExtra(DecisionBroadcastReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(DecisionBroadcastReceiver.EXTRA_TOOL_NAME,       toolName)
                putExtra(DecisionBroadcastReceiver.EXTRA_COMMAND_PREVIEW, detail)
            }

        val allowPi  = PendingIntent.getBroadcast(ctx, notificationId,     makeIntent(DecisionBroadcastReceiver.ACTION_ALLOW_ONCE),           PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alwaysPi = PendingIntent.getBroadcast(ctx, notificationId + 1, makeIntent(DecisionBroadcastReceiver.ACTION_ALWAYS_ALLOW_PROJECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val denyPi   = PendingIntent.getBroadcast(ctx, notificationId + 2, makeIntent(DecisionBroadcastReceiver.ACTION_DENY),                 PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val via = if (connectionType == ConnectionType.TAILSCALE) "Tailscale" else "LAN"
        NotificationManagerCompat.from(ctx).notify(
            notificationId,
            NotificationCompat.Builder(ctx, "aethelhook_channel")
                .setContentTitle("$toolName [$via]")
                .setContentText(detail.take(120).ifBlank { "Approval required" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_send, "Allow once",                  allowPi)
                .addAction(android.R.drawable.ic_menu_add,  "Always allow '$commandName'", alwaysPi)
                .addAction(android.R.drawable.ic_delete,    "Deny",                        denyPi)
                .build()
        )
        Log.d(TAG, "Approval notification shown for $sessionId (via $via)")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showQuestionNotification(ctx: Context, obj: JSONObject) {
        val sessionId    = obj.optString("session_id")
        val answerUrl    = obj.optString("answer_url")
        val questions    = obj.optJSONArray("questions") ?: org.json.JSONArray()
        val questionsStr = questions.toString()

        val firstQuestion = questions.optJSONObject(0)
        val firstHeader   = firstQuestion?.optString("header").orEmpty().ifBlank { "Question" }
        val firstText     = firstQuestion?.optString("question").orEmpty()
        val title         = if (questions.length() > 1) "Claude has ${questions.length()} questions" else "Claude: $firstHeader"
        val body          = firstText.take(120).ifBlank { "Tap to answer" }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aethelhook_channel", "AethelHook Alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val notificationId = System.currentTimeMillis().toInt()

        // Tap-to-open only - no quick-action buttons, multi-choice needs the full screen.
        val openIntent = Intent(ctx, QuestionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("session_id",     sessionId)
            putExtra("answer_url",     answerUrl)
            putExtra("questions_json", questionsStr)
        }
        val openPi = PendingIntent.getActivity(
            ctx, notificationId + 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val via = if (connectionType == ConnectionType.TAILSCALE) "Tailscale" else "LAN"
        NotificationManagerCompat.from(ctx).notify(
            notificationId,
            NotificationCompat.Builder(ctx, "aethelhook_channel")
                .setContentTitle("$title [$via]")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build()
        )
        Log.d(TAG, "Question notification shown for $sessionId (via $via)")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showPlanReviewNotification(ctx: Context, obj: JSONObject) {
        val sessionId   = obj.optString("session_id")
        val respondUrl  = obj.optString("respond_url")
        val planUrl     = obj.optString("plan_url")
        val planText    = obj.optString("plan")
        val planUrls    = obj.optJSONArray("plan_urls")?.toString().orEmpty()
        val respondUrls = obj.optJSONArray("respond_urls")?.toString().orEmpty()
        val preview     = obj.optString("plan_preview").ifBlank { "Tap to review the full plan" }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aethelhook_channel", "AethelHook Alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val notificationId = System.currentTimeMillis().toInt()

        // Tap-to-open only - reading the full plan needs the full screen.
        val openIntent = Intent(ctx, PlanReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("session_id",   sessionId)
            putExtra("respond_url",  respondUrl)
            putExtra("plan_url",     planUrl)
            putExtra("plan_text",    planText)
            putExtra("plan_preview", preview)
            putExtra("plan_urls",    planUrls)
            putExtra("respond_urls", respondUrls)
        }
        val openPi = PendingIntent.getActivity(
            ctx, notificationId + 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val via = if (connectionType == ConnectionType.TAILSCALE) "Tailscale" else "LAN"
        NotificationManagerCompat.from(ctx).notify(
            notificationId,
            NotificationCompat.Builder(ctx, "aethelhook_channel")
                .setContentTitle("Review Claude's plan [$via]")
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build()
        )
        Log.d(TAG, "Plan review notification shown for $sessionId (via $via)")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showInfoNotification(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aethelhook_done", "AethelHook Done", NotificationManager.IMPORTANCE_DEFAULT)
        )
        NotificationManagerCompat.from(ctx).notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(ctx, "aethelhook_done")
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isInterfaceAvailable(ip: String): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.any { it.hostAddress == ip } ?: false
    } catch (_: Exception) { false }

    private fun findLocalIpForSubnet(senderIp: String): String? {
        val prefix = senderIp.substringBeforeLast(".") + "."
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    addr.hostAddress?.startsWith(prefix) == true &&
                    addr.hostAddress != senderIp
                }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "findLocalIpForSubnet: ${e.message}")
            null
        }
    }

    private class BoundSocketFactory(private val localIp: String) : javax.net.SocketFactory() {
        private fun newBound() = java.net.Socket().also {
            it.bind(InetSocketAddress(InetAddress.getByName(localIp), 0))
        }
        override fun createSocket(): java.net.Socket = newBound()
        override fun createSocket(h: String, p: Int): java.net.Socket =
            newBound().also { it.connect(InetSocketAddress(h, p)) }
        override fun createSocket(h: String, p: Int, la: InetAddress?, lp: Int): java.net.Socket =
            newBound().also { it.connect(InetSocketAddress(h, p)) }
        override fun createSocket(a: InetAddress, p: Int): java.net.Socket =
            newBound().also { it.connect(InetSocketAddress(a, p)) }
        override fun createSocket(a: InetAddress, p: Int, la: InetAddress?, lp: Int): java.net.Socket =
            newBound().also { it.connect(InetSocketAddress(a, p)) }
    }
}
