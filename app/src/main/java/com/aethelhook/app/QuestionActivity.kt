package com.aethelhook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethelhook.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class QOption(val label: String, val description: String)
data class QuestionUi(val question: String, val header: String, val options: List<QOption>, val multiSelect: Boolean)

class QuestionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sessionId    = intent.getStringExtra("session_id")     ?: ""
        val answerUrl    = intent.getStringExtra("answer_url")     ?: ""
        val questionsRaw = intent.getStringExtra("questions_json") ?: "[]"
        val questions    = parseQuestions(questionsRaw)
        val isDark       = AppPrefs.getDarkMode(applicationContext)
        setContent {
            AethelHookTheme(darkTheme = isDark) {
                QuestionScreen(
                    sessionId = sessionId,
                    answerUrl = answerUrl,
                    questions = questions,
                    onDone    = { finish() }
                )
            }
        }
    }
}

private fun parseQuestions(raw: String): List<QuestionUi> {
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val q = arr.optJSONObject(i) ?: return@mapNotNull null
        val opts = q.optJSONArray("options") ?: JSONArray()
        val optionList = (0 until opts.length()).mapNotNull { j ->
            val o = opts.optJSONObject(j) ?: return@mapNotNull null
            QOption(o.optString("label"), o.optString("description"))
        }
        QuestionUi(
            question    = q.optString("question"),
            header      = q.optString("header").ifBlank { "Question" },
            options     = optionList,
            multiSelect = q.optBoolean("multiSelect", false)
        )
    }
}

// Per-question answer state: selected labels + optional custom "Other" text.
private class QuestionAnswerState {
    var selected by mutableStateOf(setOf<String>())
    var otherSelected by mutableStateOf(false)
    var otherText by mutableStateOf("")

    fun isAnswered(): Boolean = selected.isNotEmpty() || (otherSelected && otherText.isNotBlank())

    fun toAnswerValue(multiSelect: Boolean): Any {
        val values = mutableListOf<String>()
        values += selected
        if (otherSelected && otherText.isNotBlank()) values += otherText.trim()
        return if (multiSelect) JSONArray(values) else (values.firstOrNull() ?: "")
    }
}

@Composable
fun QuestionScreen(
    sessionId: String,
    answerUrl: String,
    questions: List<QuestionUi>,
    onDone: () -> Unit
) {
    val c = LocalAethelColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val states = remember { questions.map { QuestionAnswerState() } }
    var submitting by remember { mutableStateOf(false) }
    val allAnswered = states.all { it.isAnswered() }

    fun submit() {
        submitting = true
        val answers = JSONObject()
        questions.forEachIndexed { i, q ->
            answers.put(q.question, states[i].toAnswerValue(q.multiSelect))
        }
        scope.launch(Dispatchers.IO) {
            DecisionActions.submitQuestionAnswer(ctx, answerUrl, sessionId, answers)
            onDone()
        }
    }

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
                QuestionHeaderCard(count = questions.size)

                questions.forEachIndexed { i, q ->
                    QuestionCard(question = q, state = states[i])
                }
            }

            SubmitBar(
                enabled    = allAnswered,
                submitting = submitting,
                onSubmit   = { submit() }
            )
        }
    }
}

@Composable
private fun QuestionHeaderCard(count: Int) {
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
                    .background(c.accentCyan.copy(alpha = 0.15f))
                    .border(1.dp, c.accentCyan.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Claude has a question", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                Text(if (count > 1) "$count questions" else "Answer to continue", fontSize = 13.sp, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun QuestionCard(question: QuestionUi, state: QuestionAnswerState) {
    val c = LocalAethelColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgCardAlt)
            .border(1.dp, c.divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.accentCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(question.header, color = c.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(question.question, color = c.textPrimary, fontSize = 15.sp, lineHeight = 20.sp)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEach { opt ->
                    OptionRow(
                        label       = opt.label,
                        description = opt.description,
                        selected    = state.selected.contains(opt.label),
                        multiSelect = question.multiSelect,
                        onClick     = {
                            if (question.multiSelect) {
                                state.selected = if (state.selected.contains(opt.label)) state.selected - opt.label
                                else state.selected + opt.label
                            } else {
                                state.selected = setOf(opt.label)
                                state.otherSelected = false
                            }
                        }
                    )
                }
                // "Other" - always available, matches AskUserQuestion's native semantics.
                OptionRow(
                    label       = "Other",
                    description = "Type a custom answer",
                    selected    = state.otherSelected,
                    multiSelect = question.multiSelect,
                    onClick     = {
                        state.otherSelected = if (question.multiSelect) !state.otherSelected else true
                        if (!question.multiSelect && state.otherSelected) state.selected = emptySet()
                    }
                )
                if (state.otherSelected) {
                    OutlinedTextField(
                        value = state.otherText,
                        onValueChange = { state.otherText = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        placeholder = { Text("Your answer", color = c.textMuted, fontSize = 12.sp) },
                        minLines = 1,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = c.accentCyan,
                            unfocusedBorderColor = c.divider,
                            focusedTextColor     = c.textPrimary,
                            unfocusedTextColor   = c.textPrimary,
                            cursorColor          = c.accentCyan
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit
) {
    val c = LocalAethelColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accentCyan.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) c.accentCyan.copy(alpha = 0.35f) else c.divider, RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (multiSelect) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = c.accentCyan)
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = c.accentCyan)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) {
                Text(description, color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun SubmitBar(enabled: Boolean, submitting: Boolean, onSubmit: () -> Unit) {
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
                        Text("Submitting...", color = c.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(if (enabled) c.accentCyan.copy(alpha = 0.14f) else c.divider.copy(alpha = 0.3f))
                        .border(1.dp, if (enabled) c.accentCyan.copy(alpha = 0.40f) else Color.Transparent, RoundedCornerShape(50))
                        .clickable(enabled = enabled) { onSubmit() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Done, contentDescription = null, tint = if (enabled) c.accentCyan else c.textMuted, modifier = Modifier.size(16.dp))
                        Text("Submit answers", color = if (enabled) c.accentCyan else c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
