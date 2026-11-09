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
import com.focuslock.app.domain.BreakPolicy

private val Blue = Color(0xFF12B9F4)
private val Panel = Color(0xFF102747)
private val Cyan = Color(0xFF25D7E8)

private fun formatDuration(ms: Long, ar: Boolean): String {
    val mins = ms.coerceAtLeast(0L) / 60_000L
    val hourUnit = if (ar) "\u0633\u0627\u0639\u0629" else "h"
    val minuteUnit = if (ar) "\u062f\u0642" else "m"
    return if (mins >= 60) "${mins / 60} $hourUnit ${mins % 60} $minuteUnit"
    else if (mins > 0) "$mins $minuteUnit"
    else if (ar) "0 $minuteUnit" else "0m"
}

@Composable
fun Metric(title: String, value: String, fg: Color, muted: Color) {
    Column { Text(title, color = muted, fontSize = 12.sp); Text(value, color = fg, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun DashboardScreen(sessions: List<FocusSessionEntity>, ar: Boolean, fg: Color, muted: Color, card: Color, onStart: () -> Unit, onStats: () -> Unit, modifier: Modifier = Modifier) {
    val today = sessions.filter {
        val now = java.time.LocalDate.now()
        java.time.Instant.ofEpochMilli(it.startTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == now && it.sessionStatus == "COMPLETED"
    }
    val total = today.sumOf { it.durationMillis }
    val latestCompletedToday = today.maxByOrNull { it.startTime }
    val breaksDue = latestCompletedToday?.let { BreakPolicy.breakCount(it.durationMillis, it.breakEnabled) } ?: 0
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(if (ar) "حان وقت التركيز." else "Make room for focus.", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            Text(if (ar) "خطوة صغيرة الآن، إنجاز أكبر لاحقاً." else "One intentional session can change your day.", color = muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (ar) "تركيز اليوم" else "Today's focus", color = muted)
                            Text(formatDuration(total, ar), color = fg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.size(58.dp).clip(CircleShape).background(Blue.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Bolt, null, tint = Blue, modifier = Modifier.size(30.dp))
                        }
                    }
                    HorizontalDivider(color = muted.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric(if (ar) "الجلسات المكتملة" else "Completed sessions", today.size.toString(), fg, muted)
                        Metric(if (ar) "كل الجلسات" else "All sessions", sessions.size.toString(), fg, muted)
                    }
                    HorizontalDivider(color = muted.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ar) "الاستراحات المستحقة" else "Breaks due", color = muted, fontSize = 13.sp)
                        Text(breaksDue.toString(), color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Lock, null, tint = Cyan, modifier = Modifier.size(28.dp))
                    Text(if (ar) "ابدأ جلسة تركيز جديدة" else "Start a focused session", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(if (ar) "اختر المدة والاستراحات قبل البدء." else "Choose your duration and break plan before you begin.", color = Color(0xFFB7CBE2))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (ar) "ابدأ التركيز" else "Start focus session", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onStats, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Insights, null); Spacer(Modifier.width(8.dp)); Text(if (ar) "عرض الإحصاءات" else "View statistics")
            }
        }
    }
}
