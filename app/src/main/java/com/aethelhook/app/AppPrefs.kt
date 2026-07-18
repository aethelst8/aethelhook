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

// Local cache of the Session Access settings sheet's last-picked model/effort/
// permission-mode for one (project, agent) pair - purely a UI convenience so the sheet
// reopens showing what was last chosen. The server keeps its own copy too
// (ProjectAgentSettings in Program.cs, keyed the same "{project}|{agent}" way) which is
// the actual source of truth once a prompt has been sent; this local copy just means a
// phone reinstall/clear-data resets the sheet to defaults rather than losing anything
// the server still remembers.
@Serializable
data class ProjectAgentSettings(
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String? = null,
    val useWorktree: Boolean = false
)

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

    // Network config - stored as IPs, URLs are derived
    private const val KEY_LAN_IP       = "lan_ip"
    private const val KEY_TAILSCALE_IP = "tailscale_ip"
    private const val KEY_PORT         = "port"

    // Active URL - managed by the network monitor, not set directly by the user
    private const val KEY_API_URL = "api_url"

    private const val KEY_TIMEOUT                = "timeout_sec"
    private const val KEY_HISTORY                = "approval_history"
    private const val KEY_DARK_MODE              = "dark_mode"
    private const val KEY_GATEWAY_ENABLED        = "gateway_enabled"
    private const val KEY_CODEX_GATEWAY_ENABLED  = "codex_gateway_enabled"
    private const val KEY_OPENCODE_GATEWAY_ENABLED = "opencode_gateway_enabled"
    private const val KEY_API_TOKEN              = "api_token"
    private const val KEY_CERT_FINGERPRINT       = "cert_fingerprint"

    // Persistent counters - not capped by history list size
    private const val KEY_TOTAL_COUNT    = "stat_total"
    private const val KEY_APPROVED_COUNT = "stat_approved"
    private const val KEY_DENIED_COUNT   = "stat_denied"

    // Auto-clear - epoch day (ms / 86400000) of last history wipe
    private const val KEY_LAST_CLEAR_DAY = "last_clear_day"
    private const val KEY_LAST_AGENT       = "last_agent"
    private const val KEY_PROJECT_AGENT_SETTINGS = "project_agent_settings"

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

    // ── Session Access: last-picked agent ("claude", "codex", or "opencode"), sticky like the project ──

    fun getLastAgent(ctx: Context): String =
        securePrefs(ctx).getString(KEY_LAST_AGENT, "claude") ?: "claude"

    fun setLastAgent(ctx: Context, agent: String) =
        securePrefs(ctx).edit { putString(KEY_LAST_AGENT, agent) }

    // ── Session Access: per (project, agent) settings sheet cache ─────────────

    private fun getAllProjectAgentSettings(ctx: Context): Map<String, ProjectAgentSettings> {
        val json = securePrefs(ctx).getString(KEY_PROJECT_AGENT_SETTINGS, "{}") ?: "{}"
        return try { Json.decodeFromString(json) } catch (e: Exception) { emptyMap() }
    }

    fun getProjectAgentSettings(ctx: Context, projectKey: String, agent: String): ProjectAgentSettings =
        getAllProjectAgentSettings(ctx)["$projectKey|$agent"] ?: ProjectAgentSettings()

    fun setProjectAgentSettings(ctx: Context, projectKey: String, agent: String, settings: ProjectAgentSettings) {
        val all = getAllProjectAgentSettings(ctx).toMutableMap()
        all["$projectKey|$agent"] = settings
        securePrefs(ctx).edit { putString(KEY_PROJECT_AGENT_SETTINGS, Json.encodeToString(all)) }
    }

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
        // Deliberately uncapped - the stat counters below are derived from this same
        // list, so capping it (the original 50-entry cap) meant Total/Approved/Denied
        // could never exceed 50 between them either, causing the count for one to fall
        // as another rose even though real new decisions kept happening (confirmed live
        // - reported as "Total capped at 50, Approved drops as Denied climbs"). Safe to
        // leave uncapped because maybeClearOldHistory already wipes this list every 48
        // hours, which bounds real-world growth to at most ~2 days of activity.
        prefs.edit {
            putString(KEY_HISTORY, Json.encodeToString(list))
        }
    }

    fun updateDecision(ctx: Context, sessionId: String, decision: String) {
        val prefs = securePrefs(ctx)
        val list = getHistory(ctx).toMutableList()
        val idx = list.indexOfFirst { it.sessionId == sessionId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(decision = decision)
            prefs.edit { putString(KEY_HISTORY, Json.encodeToString(list)) }
        }
    }

    fun clearHistory(ctx: Context) {
        val today = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        securePrefs(ctx).edit {
            remove(KEY_HISTORY)
            // Legacy keys from before stat counters were derived from history - remove
            // any stale values still sitting from an older install.
            remove(KEY_TOTAL_COUNT)
            remove(KEY_APPROVED_COUNT)
            remove(KEY_DENIED_COUNT)
            putLong(KEY_LAST_CLEAR_DAY, today)
        }
    }

    // ── Stat counters ─────────────────────────────────────────────────────────
    // Derived live from the stored (50-entry-capped) history list rather than
    // tracked as separate ever-incrementing counters. Previously these were
    // independent persistent counters that never got trimmed - once more than 50
    // decisions had accumulated, an old denial could age out of the visible history
    // list while the counter kept counting it forever, so the dashboard could show
    // e.g. "Denied: 2" while tapping into it (which filters this same history list)
    // showed zero matching records. Deriving both from the same list makes that
    // impossible - the dashboard number always matches exactly what tapping it shows.

    fun getTotalCount(ctx: Context): Int = getHistory(ctx).size

    fun getApprovedCount(ctx: Context): Int = getHistory(ctx).count { it.decision in APPROVED_DECISIONS }

    fun getDeniedCount(ctx: Context): Int = getHistory(ctx).count { it.decision in DENIED_DECISIONS }

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

    fun getOpenCodeGatewayEnabled(ctx: Context): Boolean =
        securePrefs(ctx).getBoolean(KEY_OPENCODE_GATEWAY_ENABLED, true)

    fun setOpenCodeGatewayEnabled(ctx: Context, enabled: Boolean) =
        securePrefs(ctx).edit { putBoolean(KEY_OPENCODE_GATEWAY_ENABLED, enabled) }

    // ── API token (shared secret - auto-populated from UDP beacon) ────────────

    fun getApiToken(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(ctx: Context, token: String) =
        securePrefs(ctx).edit { putString(KEY_API_TOKEN, token) }

    // ── Pinned HTTPS cert fingerprint (SHA-256 hex, received via the QR pairing
    // payload's `c` field - not from the PC over the network, since the QR scan
    // itself is the trust root here, not a certificate authority) ──────────────

    fun getCertFingerprint(ctx: Context): String =
        securePrefs(ctx)
            .getString(KEY_CERT_FINGERPRINT, "") ?: ""

    fun setCertFingerprint(ctx: Context, fingerprint: String) =
        securePrefs(ctx).edit { putString(KEY_CERT_FINGERPRINT, fingerprint) }
}
