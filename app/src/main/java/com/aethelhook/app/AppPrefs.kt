package com.aethelhook.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ApprovalRecord(
    val sessionId: String,
    val toolName: String,
    val preview: String,
    val decision: String,      // "allow" | "deny" | "pending"
    val timestampMs: Long
)

internal val APPROVED_DECISIONS = setOf("allow", "allow_once", "always_allow_project", "always_allow_global")
internal val DENIED_DECISIONS   = setOf("deny", "deny_with_reason")

object AppPrefs {
    // Renamed from the old plaintext "aethelhook_prefs" - this file is now encrypted
    // (Android Keystore-backed AES-GCM via EncryptedSharedPreferences), so it's a
    // distinct underlying file rather than an in-place migration of plaintext data.
    // Practical effect: upgrading from a pre-encryption install clears the stored
    // token/IPs, requiring one re-pair - same precedent as the TLS-pinning rollout.
    private const val PREFS = "aethelhook_prefs_secure"

    @Volatile private var cachedPrefs: SharedPreferences? = null

    // Android Keystore-backed encrypted prefs for the token/fingerprint/IPs this file
    // stores. EncryptedSharedPreferences is deprecated as of security-crypto 1.1.0
    // (Google now points new code at raw Keystore APIs directly) but still fully
    // functional - kept here rather than hand-rolling Keystore/Cipher/IV handling for
    // what's a small, low-traffic set of string values.
    private fun securePrefs(ctx: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val appCtx = ctx.applicationContext
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                appCtx,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cachedPrefs = created
            return created
        }
    }

    // Network config — stored as IPs, URLs are derived
    private const val KEY_LAN_IP       = "lan_ip"
    private const val KEY_TAILSCALE_IP = "tailscale_ip"
    private const val KEY_PORT         = "port"

    // Active URL — managed by the network monitor, not set directly by the user
    private const val KEY_API_URL = "api_url"

    private const val KEY_TIMEOUT                = "timeout_sec"
    private const val KEY_HISTORY                = "approval_history"
    private const val KEY_DARK_MODE              = "dark_mode"
    private const val KEY_GATEWAY_ENABLED        = "gateway_enabled"
    private const val KEY_CODEX_GATEWAY_ENABLED  = "codex_gateway_enabled"
    private const val KEY_API_TOKEN              = "api_token"
    private const val KEY_CERT_FINGERPRINT       = "cert_fingerprint"

    // Persistent counters — not capped by history list size
    private const val KEY_TOTAL_COUNT    = "stat_total"
    private const val KEY_APPROVED_COUNT = "stat_approved"
    private const val KEY_DENIED_COUNT   = "stat_denied"

    // Auto-clear — epoch day (ms / 86400000) of last history wipe
    private const val KEY_LAST_CLEAR_DAY = "last_clear_day"
    private const val KEY_LAST_AGENT       = "last_agent"

    // ── LAN IP ────────────────────────────────────────────────────────────────

    fun getLanIp(ctx: Context): String {
        val prefs = securePrefs(ctx)
        val stored = prefs.getString(KEY_LAN_IP, "") ?: ""
        if (stored.isNotBlank()) return stored
        val oldUrl = prefs.getString(KEY_API_URL, "") ?: ""
        if (oldUrl.isNotBlank()) {
            val ip = oldUrl.removePrefix("http://").removePrefix("https://").substringBefore(":")
            if (ip.isNotBlank() && ip.contains('.')) return ip
        }
        return ""
    }

    fun setLanIp(ctx: Context, ip: String) =
        securePrefs(ctx).edit { putString(KEY_LAN_IP, ip.trim()) }

    // ── Tailscale IP ──────────────────────────────────────────────────────────

    fun getTailscaleIp(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_TAILSCALE_IP, "") ?: ""

    fun setTailscaleIp(ctx: Context, ip: String) =
        securePrefs(ctx).edit { putString(KEY_TAILSCALE_IP, ip.trim()) }

    // ── Port ──────────────────────────────────────────────────────────────────

    fun getPort(ctx: Context): Int =
        securePrefs(ctx).getInt(KEY_PORT, 5264)

    fun setPort(ctx: Context, port: Int) =
        securePrefs(ctx).edit { putInt(KEY_PORT, port) }

    // ── Derived full URLs ─────────────────────────────────────────────────────

    fun getLanUrl(ctx: Context): String {
        val ip = getLanIp(ctx).ifBlank { return "" }
        return "https://$ip:${getPort(ctx)}"
    }

    fun getTailscaleUrl(ctx: Context): String {
        val ip = getTailscaleIp(ctx).ifBlank { return "" }
        return "https://$ip:${getPort(ctx)}"
    }

    // ── Active URL (set by network monitor, not by user directly) ─────────────

    fun getApiUrl(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_API_URL, "") ?: ""

    fun setApiUrl(ctx: Context, url: String) =
        securePrefs(ctx).edit { putString(KEY_API_URL, url) }

    // ── Hook wait timeout ─────────────────────────────────────────────────────

    fun getTimeout(ctx: Context): Int =
        securePrefs(ctx).getInt(KEY_TIMEOUT, 80)

    fun setTimeout(ctx: Context, secs: Int) =
        securePrefs(ctx).edit { putInt(KEY_TIMEOUT, secs) }

    // ── Session Access: last-picked agent ("claude" or "codex"), sticky like the project ──

    fun getLastAgent(ctx: Context): String =
        securePrefs(ctx).getString(KEY_LAST_AGENT, "claude") ?: "claude"

    fun setLastAgent(ctx: Context, agent: String) =
        securePrefs(ctx).edit { putString(KEY_LAST_AGENT, agent) }

    // ── Approval history ──────────────────────────────────────────────────────

    fun getHistory(ctx: Context): List<ApprovalRecord> {
        val json = securePrefs(ctx)
            .getString(KEY_HISTORY, "[]") ?: "[]"
        return try { Json.decodeFromString(json) } catch (e: Exception) { emptyList() }
    }

    fun addRecord(ctx: Context, record: ApprovalRecord) {
        val prefs = securePrefs(ctx)
        val list = getHistory(ctx).toMutableList()
        list.add(0, record)
        val trimmed = list.take(50)
        val newTotal    = prefs.getInt(KEY_TOTAL_COUNT, 0) + 1
        val newApproved = prefs.getInt(KEY_APPROVED_COUNT, 0) + if (record.decision in APPROVED_DECISIONS) 1 else 0
        val newDenied   = prefs.getInt(KEY_DENIED_COUNT, 0)   + if (record.decision in DENIED_DECISIONS) 1 else 0
        prefs.edit {
            putString(KEY_HISTORY, Json.encodeToString(trimmed))
            putInt(KEY_TOTAL_COUNT, newTotal)
            putInt(KEY_APPROVED_COUNT, newApproved)
            putInt(KEY_DENIED_COUNT, newDenied)
        }
    }

    fun updateDecision(ctx: Context, sessionId: String, decision: String) {
        val prefs = securePrefs(ctx)
        val list = getHistory(ctx).toMutableList()
        val idx = list.indexOfFirst { it.sessionId == sessionId }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(decision = decision)
            val approvedDelta = if (old.decision == "pending" && decision in APPROVED_DECISIONS) 1 else 0
            val deniedDelta   = if (old.decision == "pending" && decision in DENIED_DECISIONS) 1 else 0
            prefs.edit {
                putString(KEY_HISTORY, Json.encodeToString(list))
                if (approvedDelta > 0) putInt(KEY_APPROVED_COUNT, prefs.getInt(KEY_APPROVED_COUNT, 0) + 1)
                if (deniedDelta > 0)   putInt(KEY_DENIED_COUNT,   prefs.getInt(KEY_DENIED_COUNT, 0)   + 1)
            }
        }
    }

    fun clearHistory(ctx: Context) {
        val today = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        securePrefs(ctx).edit {
            remove(KEY_HISTORY)
            putInt(KEY_TOTAL_COUNT, 0)
            putInt(KEY_APPROVED_COUNT, 0)
            putInt(KEY_DENIED_COUNT, 0)
            putLong(KEY_LAST_CLEAR_DAY, today)
        }
    }

    // ── Stat counters ─────────────────────────────────────────────────────────

    fun getTotalCount(ctx: Context): Int =
        securePrefs(ctx).getInt(KEY_TOTAL_COUNT, 0)

    fun getApprovedCount(ctx: Context): Int =
        securePrefs(ctx).getInt(KEY_APPROVED_COUNT, 0)

    fun getDeniedCount(ctx: Context): Int =
        securePrefs(ctx).getInt(KEY_DENIED_COUNT, 0)

    // ── Auto-clear every 48 hours at midnight boundary ────────────────────────

    fun maybeClearOldHistory(ctx: Context) {
        val prefs = securePrefs(ctx)
        val today = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        if (!prefs.contains(KEY_LAST_CLEAR_DAY)) {
            prefs.edit { putLong(KEY_LAST_CLEAR_DAY, today) }
            return
        }
        val lastClear = prefs.getLong(KEY_LAST_CLEAR_DAY, today)
        if (today - lastClear >= 2) clearHistory(ctx)
    }

    // ── Appearance / gateway ──────────────────────────────────────────────────

    fun getDarkMode(ctx: Context): Boolean =
        securePrefs(ctx).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(ctx: Context, dark: Boolean) =
        securePrefs(ctx).edit { putBoolean(KEY_DARK_MODE, dark) }

    fun getGatewayEnabled(ctx: Context): Boolean =
        securePrefs(ctx).getBoolean(KEY_GATEWAY_ENABLED, true)

    fun setGatewayEnabled(ctx: Context, enabled: Boolean) =
        securePrefs(ctx).edit { putBoolean(KEY_GATEWAY_ENABLED, enabled) }

    fun getCodexGatewayEnabled(ctx: Context): Boolean =
        securePrefs(ctx).getBoolean(KEY_CODEX_GATEWAY_ENABLED, true)

    fun setCodexGatewayEnabled(ctx: Context, enabled: Boolean) =
        securePrefs(ctx).edit { putBoolean(KEY_CODEX_GATEWAY_ENABLED, enabled) }

    // ── API token (shared secret — auto-populated from UDP beacon) ────────────

    fun getApiToken(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(ctx: Context, token: String) =
        securePrefs(ctx).edit { putString(KEY_API_TOKEN, token) }

    // ── Pinned HTTPS cert fingerprint (SHA-256 hex, received via the QR pairing
    // payload's `c` field — not from the PC over the network, since the QR scan
    // itself is the trust root here, not a certificate authority) ──────────────

    fun getCertFingerprint(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_CERT_FINGERPRINT, "") ?: ""

    fun setCertFingerprint(ctx: Context, fingerprint: String) =
        securePrefs(ctx).edit { putString(KEY_CERT_FINGERPRINT, fingerprint) }
}
