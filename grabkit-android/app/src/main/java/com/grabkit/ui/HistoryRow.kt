package com.grabkit.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grabkit.model.HistoryEntry
import com.grabkit.model.Platform
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlatformBadge(label: String, platform: Platform?, modifier: Modifier = Modifier) {
    val color = platform?.let { Color(it.borderColor) } ?: GK.Border
    Text(
        label, color = GK.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

@Composable
fun HistoryRow(e: HistoryEntry) {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.timeMillis))
    val sizeText = String.format(Locale.US, "%.1f MB", e.sizeBytes / 1048576.0)
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(GK.Border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformBadge(e.platform.label, e.platform)
        Text(e.fileName, color = GK.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$sizeText · $time", color = GK.Muted, fontSize = 13.sp)
    }
}
