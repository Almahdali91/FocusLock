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
fun BreakInfoScreen(ar: Boolean, fg: Color, muted: Color, card: Color, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (ar) "نظام الاستراحات" else "Break schedule", color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = card)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (ar) "30 \u062f\u0642\u064a\u0642\u0629: \u062f\u0648\u0646 \u0627\u0633\u062a\u0631\u0627\u062d\u0629\n60 \u062f\u0642\u064a\u0642\u0629: \u062f\u0648\u0646 \u0627\u0633\u062a\u0631\u0627\u062d\u0629\n90 \u062f\u0642\u064a\u0642\u0629: \u062f\u0648\u0646 \u0627\u0633\u062a\u0631\u0627\u062d\u0629\n\u0633\u0627\u0639\u062a\u0627\u0646: \u062f\u0648\u0646 \u0627\u0633\u062a\u0631\u0627\u062d\u0629\n\u0633\u0627\u0639\u062a\u0627\u0646 \u0648\u0646\u0635\u0641: \u0627\u0633\u062a\u0631\u0627\u062d\u0629 \u0648\u0627\u062d\u062f\u0629\n3 \u0633\u0627\u0639\u0627\u062a: \u0627\u0633\u062a\u0631\u0627\u062d\u0629 \u0648\u0627\u062d\u062f\u0629\n4 \u0633\u0627\u0639\u0627\u062a: \u0627\u0633\u062a\u0631\u0627\u062d\u062a\u0627\u0646\n5 \u0633\u0627\u0639\u0627\u062a: 3 \u0627\u0633\u062a\u0631\u0627\u062d\u0627\u062a" else "30 min: no break\n60 min: no break\n90 min: no break\n2 hours: no break\n2.5 hours: one break\n3 hours: one break\n4 hours: two breaks\n5 hours: three breaks", color = fg)
            Text(if (ar) "\u062a\u0628\u062f\u0623 \u0623\u0648\u0644 \u0627\u0633\u062a\u0631\u0627\u062d\u0629 \u0628\u0639\u062f \u0625\u0643\u0645\u0627\u0644 \u0633\u0627\u0639\u062a\u064a\u0646 \u0645\u0646 \u0627\u0644\u062a\u0631\u0643\u064a\u0632\u060c \u062b\u0645 \u062a\u062a\u0643\u0631\u0631 \u0643\u0644 \u0633\u0627\u0639\u0629 \u062a\u0631\u0643\u064a\u0632 \u0645\u0643\u062a\u0645\u0644\u0629 \u0625\u0630\u0627 \u0628\u0642\u064a \u0648\u0642\u062a \u0641\u064a \u0627\u0644\u062c\u0644\u0633\u0629. \u0645\u062f\u0629 \u0643\u0644 \u0627\u0633\u062a\u0631\u0627\u062d\u0629 10 \u062f\u0642\u0627\u0626\u0642." else "The first break starts after two completed hours of focus, then repeats after each additional completed focus hour while session time remains. Each break lasts 10 minutes; no break starts at the end of a session.", color = muted)
        } } }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(if (ar) "العودة" else "Back") } }
    }
}
