package com.il90.appmanager.util

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.toColorInt
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> String.format(Locale.US, "%d KB", (bytes + 512) / 1024)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

fun parseInstant(iso: String?): Long? = try {
    if (iso.isNullOrBlank()) null else Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    null
}

/** "3 hours ago", "Yesterday", or a date once it is more than a week old. */
fun relativeTime(context: Context, millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val now = System.currentTimeMillis()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) {
        return DateUtils.getRelativeTimeSpanString(now, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    return if (now - millis < 7 * DateUtils.DAY_IN_MILLIS) {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}

fun parseColor(hex: String?): Color? = try {
    if (hex.isNullOrBlank()) null else Color(hex.toColorInt())
} catch (_: Exception) {
    null
}

/** Keeps an app's accent readable as a foreground colour on the current theme. */
fun Color.readableOn(dark: Boolean): Color {
    val l = luminance()
    return when {
        dark && l < 0.35f -> lerp(this, Color.White, 0.45f)
        dark && l < 0.5f -> lerp(this, Color.White, 0.2f)
        !dark && l > 0.45f -> lerp(this, Color.Black, 0.4f)
        else -> this
    }
}

fun appLanguage(): String = Locale.getDefault().language.let { if (it == "iw") "he" else it }
