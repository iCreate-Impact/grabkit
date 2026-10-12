package com.grabkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.grabkit.model.ItemType
import com.grabkit.model.JobState
import com.grabkit.model.JobStatus
import com.grabkit.model.MediaItem
import com.grabkit.model.OutputFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UrlCard(
    card: CardUi,
    job: JobState?,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onFormat: (OutputFormat) -> Unit,
    onQuality: (Int?) -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(GK.Panel).border(1.dp, GK.Border, shape).padding(16.dp),
    ) {
        val info = card.info
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlatformBadge(card.platform?.label ?: "?", card.platform)
            Text(
                info?.title ?: card.url,
                color = if (info != null) GK.Text else GK.Muted,
                fontSize = if (info != null) 16.sp else 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            when {
                card.phase == CardPhase.FETCHING -> Text("Fetching…", color = GK.Muted, fontSize = 13.sp)
                (info?.items?.size ?: 0) > 1 -> TextButton(onClick = onToggleAll) {
                    Text("Toggle all", color = GK.Muted, fontSize = 13.sp)
                }
                else -> Unit
            }
        }

        if (card.phase == CardPhase.FAILED) {
            Text(card.error.orEmpty(), color = GK.Error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        if (info == null) return@Column

        FlowRow(
            Modifier.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            info.items.forEachIndexed { i, item ->
                Thumb(item, checked = i in card.selected) { onToggle(i) }
            }
        }

        val running = job?.isActive == true
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(card.format.ext, card.formats.map { it.ext to it }, onFormat)
            if (card.format == OutputFormat.MP4 && card.qualities.isNotEmpty()) {
                val opts = listOf<Pair<String, Int?>>("Best" to null) + card.qualities.map { "${it}p" to it }
                Dropdown(card.quality?.let { "${it}p" } ?: "Best", opts, onQuality)
            }
            val n = card.selected.size
            Button(
                onClick = onDownload,
                enabled = n > 0 && !running,
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GK.Accent, contentColor = GK.OnAccent,
                    disabledContainerColor = GK.Accent.copy(alpha = 0.5f), disabledContentColor = GK.OnAccent,
                ),
            ) {
                Text("Download" + if (info.items.size > 1) " ($n)" else "", fontWeight = FontWeight.SemiBold)
            }
        }

        if (job != null && job.status != JobStatus.ERROR) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(job.label, color = GK.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${job.percent.toInt()}%", color = GK.Muted, fontSize = 13.sp)
            }
            LinearProgressIndicator(
                progress = { job.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(9.dp)),
                color = GK.Accent, trackColor = GK.Bg,
            )
        }
        job?.error?.let {
            Text(it, color = GK.Error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Thumb(item: MediaItem, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(88.dp).clip(shape).background(GK.ThumbBg)
            .border(2.dp, if (checked) GK.Accent else Color.Transparent, shape)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(item.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() },
            modifier = Modifier.align(Alignment.TopStart).size(36.dp),
            colors = CheckboxDefaults.colors(checkedColor = GK.Accent, checkmarkColor = GK.OnAccent, uncheckedColor = GK.Text),
        )
        Text(
            if (item.type == ItemType.VIDEO) "video" else "image", color = GK.Text, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun <T> Dropdown(current: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier.clip(shape).background(GK.Bg).border(1.dp, GK.Border, shape)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = GK.Text, fontSize = 15.sp)
            Spacer(Modifier.width(6.dp))
            Text("▾", color = GK.Muted, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(GK.Panel)) {
            options.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label, color = GK.Text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}
