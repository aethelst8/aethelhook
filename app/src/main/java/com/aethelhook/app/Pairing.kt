package com.aethelhook.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Scanned from the QR shown by the PC's loopback-only /pair page. v=2 added `c` (the
// HTTPS cert's SHA-256 fingerprint) - a v=1 QR from before the PC was updated has no
// certificate to pin against, so it's rejected rather than silently pairing unpinned.
@Serializable
data class PairPayload(
    val v: Int,
    val i: String,          // LAN IP
    val p: Int,             // port
    val s: String,          // sid
    val k: String,          // psk
    val c: String? = null   // HTTPS cert SHA-256 fingerprint (v2+)
)

data class ClaimResult(val deviceId: String, val token: String, val ip: String, val port: Int)

// Redeems a one-time pairing session for a real per-device API token.
object AethelHookPairing {

    fun parsePayload(raw: String): PairPayload? =
        runCatching { Json.decodeFromString<PairPayload>(raw) }.getOrNull()

    suspend fun claim(payload: PairPayload): Result<ClaimResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (payload.v < 2 || payload.c.isNullOrBlank()) {
                throw IllegalStateException("This QR code is from an older AethelHook version - update the PC and generate a new one")
            }

            val body = JSONObject().apply {
                put("sid", payload.s)
                put("psk", payload.k)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://${payload.i}:${payload.p}/pair/claim")
                .post(body)
                .build()

            // Pinned to the fingerprint fresh off this QR scan - AppPrefs isn't
            // populated yet at this point, since a successful claim is what causes it
            // to be persisted (see MainActivity's scanLauncher callback).
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .pinnedTo(payload.c)
                .build()

            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful || !json.optBoolean("ok")) {
                    throw IllegalStateException(json.optString("error", "pairing failed"))
                }
                ClaimResult(
                    deviceId = json.getString("deviceId"),
                    token    = json.getString("token"),
                    ip       = json.optString("ip", payload.i),
                    port     = json.optInt("port", payload.p)
                )
            }
        }
    }
}
