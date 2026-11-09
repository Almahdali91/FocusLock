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

private val Blue = Color(0xFF12B9F4)
private val Panel = Color(0xFF102747)
private val Cyan = Color(0xFF25D7E8)

@Composable
fun OnboardingScreen(ar: Boolean, fg: Color, muted: Color, card: Color, onLanguage: (String) -> Unit, onComplete: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Spacer(Modifier.height(28.dp)); Icon(Icons.Default.Shield, null, tint = Blue, modifier = Modifier.size(72.dp)) }
        item { Text("FocusLock", color = fg, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) }
        item { Text(if (ar) "مساحتك للتركيز بوعي" else "A calmer space for focused work", color = muted) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (ar) "ابدأ بالطريقة التي تناسبك" else "Make FocusLock yours", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (ar) "اختر العربية أو الإنجليزية. يمكنك تغيير اللغة لاحقًا من الإعدادات." else "Choose Arabic or English. You can change this later in Settings.", color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onLanguage("ar") }, modifier = Modifier.weight(1f)) { Text("العربية") }
                        OutlinedButton(onClick = { onLanguage("en") }, modifier = Modifier.weight(1f)) { Text("English") }
                    }
                    Text(if (ar) "جلساتك وإعداداتك محفوظة محليًا على جهازك." else "Your sessions and preferences are stored locally on your device.", color = muted)
                }
            }
        }
        item { Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (ar) "لنبدأ" else "Get started") } }
    }
}
