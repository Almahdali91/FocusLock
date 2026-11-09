package com.focuslock.app.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslock.app.R
import com.focuslock.app.data.FocusSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val Blue = Color(0xFF12B9F4)
private val Panel = Color(0xFF102747)
private val Cyan = Color(0xFF25D7E8)

fun formatDateTime(timestamp: Long, ar: Boolean): String {
    val locale = if (ar) Locale.forLanguageTag("ar") else Locale.US
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, locale)
        .format(java.util.Date(timestamp))
}
private fun formatDuration(ms: Long, ar: Boolean): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val mins = totalSeconds / 60L
    val hourUnit = if (ar) "\u0633\u0627\u0639\u0629" else "h"
    val minuteUnit = if (ar) "\u062f\u0642" else "m"
    val secondUnit = if (ar) "\u062b" else "s"
    return if (mins >= 60) "${mins / 60} $hourUnit ${mins % 60} $minuteUnit"
    else if (mins > 0) "$mins $minuteUnit"
    else if (totalSeconds > 0) "$totalSeconds $secondUnit"
    else if (ar) "0 $minuteUnit" else "0m"
}

@Composable
fun StatisticsScreen(sessions: List<FocusSessionEntity>, ar: Boolean, fg: Color, muted: Color, card: Color, modifier: Modifier = Modifier) {
    val completed = sessions.filter { it.sessionStatus == "COMPLETED" }
    val total = completed.sumOf { it.durationMillis }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val today = remember { java.time.LocalDate.now(zone) }
    val todayStart = remember(today, zone) { today.atStartOfDay(zone).toInstant().toEpochMilli() }
    val weekStart = remember(today, zone) { today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli() }
    val monthStart = remember(today, zone) { today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli() }
    val todayTotal = completed.filter { it.startTime >= todayStart }.sumOf { it.durationMillis }
    val weekTotal = completed.filter { it.startTime >= weekStart }.sumOf { it.durationMillis }
    val monthTotal = completed.filter { it.startTime >= monthStart }.sumOf { it.durationMillis }
    val interruptedCount = sessions.count { it.sessionStatus == "INTERRUPTED" }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (ar) "إحصاءاتك" else "Your statistics", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(if (ar) "الوقت المكتمل في السجل" else "Completed focus time in history", color = muted)
                    Text(formatDuration(total, ar), color = fg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("${completed.size} ${if (ar) "جلسة مكتملة" else "completed sessions"}", color = Blue)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(if (ar) "تركيز اليوم" else "Today's focus", color = muted)
                    Text(formatDuration(todayTotal, ar), color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(if (ar) "آخر 7 أيام" else "Last 7 days", color = muted)
                    Text(formatDuration(weekTotal, ar), color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(if (ar) "آخر 30 يومًا" else "Last 30 days", color = muted)
                    Text(formatDuration(monthTotal, ar), color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(if (ar) "الجلسات المتوقفة" else "Interrupted sessions", color = muted)
                    Text(interruptedCount.toString(), color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text(if (ar) "الجلسات الأخيرة" else "Recent sessions", color = fg, fontWeight = FontWeight.Bold) }
        if (sessions.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.HourglassEmpty, null, tint = muted, modifier = Modifier.size(28.dp))
                        Text(if (ar) "لا توجد جلسات بعد" else "No sessions yet", color = fg, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (ar) "ستظهر جلسات التركيز هنا بعد إكمال أول جلسة أو إيقافها." else "Your focus sessions will appear here after your first completed or interrupted session.",
                            color = muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(sessions.size) { index ->
                val s = sessions[index]
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (s.sessionStatus == "COMPLETED") Icons.Default.CheckCircle else Icons.Default.History, null, tint = Blue)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(formatDuration(s.durationMillis, ar), color = fg, fontWeight = FontWeight.Bold)
                            Text(formatDateTime(s.startTime, ar), color = muted, fontSize = 11.sp)
                        }
                        Text(when (s.sessionStatus) {
                            "COMPLETED" -> if (ar) "مكتملة" else "Completed"
                            "INTERRUPTED" -> if (ar) "متوقفة" else "Interrupted"
                            "ACTIVE" -> if (ar) "نشطة" else "Active"
                            else -> s.sessionStatus
                        }, color = muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
