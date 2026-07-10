package com.aethelhook.app

import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Translates a non-2xx HTTP response from the AethelHook API into a message a
// non-technical user can act on, instead of a bare status code. The server already
// writes a human-readable "detail" (ASP.NET ProblemDetails, e.g. "Claude Code CLI not
// found on this machine.") or "error" field for failures it can anticipate - prefer
// that verbatim over inventing our own wording, and only fall back to a generic
// status-code message when the body doesn't have one.
fun friendlyHttpError(code: Int, rawBody: String?, agentLabel: String): String {
    val serverDetail = rawBody?.takeIf { it.isNotBlank() }?.let {
        try {
            val obj = JSONObject(it)
            obj.optString("detail").ifBlank { null } ?: obj.optString("error").ifBlank { null }
        } catch (_: Exception) { null }
    }
    if (!serverDetail.isNullOrBlank()) return serverDetail

    return when (code) {
        401 -> "This phone isn't paired with your PC anymore. Re-pair it from Settings."
        400 -> "That message couldn't be sent — please try again."
        in 500..599 -> "$agentLabel couldn't run on your PC. Make sure it's installed and AethelHook can find it."
        else -> "Failed to send (HTTP $code)"
    }
}

// Translates a thrown exception (almost always a connectivity failure - the request
// never even reached the PC) into plain language.
fun friendlyNetworkError(e: Exception): String = when (e) {
    is UnknownHostException, is ConnectException ->
        "Couldn't reach your PC. Make sure it's turned on, AethelHook is running, and your phone is on the same Wi-Fi (or Tailscale on mobile data)."
    is SocketTimeoutException ->
        "Your PC didn't respond in time. Check that it's on and reachable, then try again."
    else -> "Something went wrong sending that: ${e.message ?: e.javaClass.simpleName}"
}
