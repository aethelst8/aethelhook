package com.aethelhook.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// Masks a sensitive value (IP address, API token, etc.) for display: keeps only the
// first and last character, replaces every other character with '*', but leaves
// separators (. : -) alone so an IP/GUID's shape stays recognizable without exposing
// it, e.g. "192.168.18.3" -> "1**.***.**.3".
fun maskMiddle(value: String): String {
    if (value.isEmpty()) return value
    if (value.length <= 2) return "*".repeat(value.length)
    return value.mapIndexed { i, ch ->
        when {
            i == 0 || i == value.lastIndex -> ch
            ch == '.' || ch == ':' || ch == '-' -> ch
            else -> '*'
        }
    }.joinToString("")
}

// Masks any IPv4-looking substring inside a larger string (e.g. a full URL like
// "http://192.168.18.3:5264" or "Cannot reach http://192.168.18.3:5264"), leaving the
// rest of the text untouched.
private val IPV4_RE = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
fun maskIpInText(text: String): String =
    IPV4_RE.replace(text) { m -> maskMiddle(m.value) }

// Applies maskMiddle() to a text field's displayed value while leaving the underlying
// state untouched, so a masked field (IP, API token) can still be typed into/edited
// normally - same technique as PasswordVisualTransformation, just with our own masking
// rule instead of replacing every character.
class MaskMiddleVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(maskMiddle(text.text)), OffsetMapping.Identity)
}
