package com.grabkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabkit.util.PlatformDetector

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val fetch = { keyboard?.hide(); vm.fetch() }

    Box(Modifier.fillMaxSize().background(GK.Bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("GrabKit", color = GK.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            item { InputBar(vm.input, { vm.input = it }, fetch) }
            item { Hint(vm.input) }
            items(cards, key = { it.id }) { card ->
                UrlCard(
                    card = card, job = jobs[card.id],
                    onToggle = { vm.toggle(card.id, it) },
                    onToggleAll = { vm.toggleAll(card.id) },
                    onFormat = { vm.setFormat(card.id, it) },
                    onQuality = { vm.setQuality(card.id, it) },
                    onDownload = { vm.download(card.id) },
                )
            }
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "This session", color = GK.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 26.dp),
                    )
                }
                items(history) { HistoryRow(it) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, onFetch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(GK.Panel)
            .border(1.dp, if (focused) GK.Accent else GK.Border, shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BasicTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(color = GK.Text, fontSize = 19.sp, lineHeight = 26.sp),
            cursorBrush = SolidColor(GK.Accent),
            minLines = 1, maxLines = 6,
            // Done triggers the fetch. Several links come in by pasting (one per line).
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFetch() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Paste links, one per line", color = GK.Muted, fontSize = 19.sp)
                    inner()
                }
            },
        )
        Button(
            onClick = onFetch, shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GK.Accent, contentColor = GK.OnAccent),
        ) { Text("Fetch", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Hint(input: String) {
    val links = remember(input) { PlatformDetector.extractUrls(input) }
    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            links.isEmpty() ->
                Text("YouTube, TikTok, Instagram, X and Facebook. Public posts only.", color = GK.Muted, fontSize = 13.sp)
            links.size > 1 -> {
                PlatformBadge("${links.size} links", null)
                Text("queued one after another", color = GK.Muted, fontSize = 13.sp)
            }
            else -> {
                val p = PlatformDetector.detectLoose(links[0])
                if (p != null) {
                    PlatformBadge(p.label, p)
                    Text("detected", color = GK.Muted, fontSize = 13.sp)
                } else {
                    Text("Unknown host, will try to resolve the link", color = GK.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}
