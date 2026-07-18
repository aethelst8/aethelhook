package com.aethelhook.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// Shared decision-submission logic for approvals/questions/plan-reviews. Previously each
// of ApprovalActivity/QuestionActivity/PlanReviewActivity inlined its own HTTP POST (and
// Question/PlanReview additionally tried WebSocket first, Approval never did) - this is
// the one WS-then-HTTP-fallback implementation per decision type, reused by all three
// full-screen Activities AND the inline chat action chips in SessionActivity, so a
// decision answered from any of those four surfaces goes through the same code path.
object DecisionActions {

    suspend fun submitApprovalDecision(
        ctx: Context,
        respondUrl: String,
        sessionId: String,
        toolName: String,
        preview: String,
        decision: String,
        reason: String = ""
    ) = withContext(Dispatchers.IO) {
        AppPrefs.addRecord(ctx, ApprovalRecord(
            sessionId   = sessionId,
            toolName    = toolName,
            preview     = preview,
            decision    = decision,
            timestampMs = System.currentTimeMillis()
        ))
        if (AethelHookWebSocket.isConnected) {
            AethelHookWebSocket.sendDecision(sessionId, decision, reason)
        } else if (respondUrl.isNotBlank()) {
            try {
                val conn = URL(respondUrl).openConnection() as HttpURLConnection
                conn.pinnedFromPrefs(ctx)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-AethelHook-Token", AppPrefs.getApiToken(ctx))
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val body = JSONObject().apply {
                    put("session_id", sessionId)
                    put("decision", decision)
                    put("reason", reason)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun submitQuestionAnswer(
        ctx: Context,
        answerUrl: String,
        sessionId: String,
        answers: JSONObject
    ) = withContext(Dispatchers.IO) {
        if (AethelHookWebSocket.isConnected) {
            AethelHookWebSocket.sendQuestionAnswer(sessionId, answers)
        } else if (answerUrl.isNotBlank()) {
            try {
                val conn = URL(answerUrl).openConnection() as HttpURLConnection
                conn.pinnedFromPrefs(ctx)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-AethelHook-Token", AppPrefs.getApiToken(ctx))
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val body = JSONObject().apply {
                    put("session_id", sessionId)
                    put("answers", answers)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun submitPlanDecision(
        ctx: Context,
        respondUrls: List<String>,
        sessionId: String,
        decision: String,
        feedback: String = ""
    ) = withContext(Dispatchers.IO) {
        if (AethelHookWebSocket.isConnected) {
            AethelHookWebSocket.sendPlanReviewDecision(sessionId, decision, feedback)
            return@withContext
        }
        for (url in respondUrls) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.pinnedFromPrefs(ctx)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-AethelHook-Token", AppPrefs.getApiToken(ctx))
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val body = JSONObject().apply {
                    put("session_id", sessionId)
                    put("decision", decision)
                    put("feedback", feedback)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return@withContext
            } catch (_: Exception) {
                // try next candidate URL
            }
        }
    }
}
