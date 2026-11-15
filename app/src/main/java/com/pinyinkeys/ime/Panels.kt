package com.pinyinkeys.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which panel replaces the letter keys. */
enum class Panel { Keys, Emoji, Clipboard, Edit }

/** Cursor and editing actions for the text-editing panel. */
enum class EditAction { Left, Right, Up, Down, Home, End, SelectAll, Cut, Copy, Paste }

/** Same height as the key area, so switching panels doesn't make the keyboard jump. */
val PANEL_HEIGHT = 232.dp

/** A plain button used inside the panels. */
@Composable
private fun RowScope.PanelButton(text: String, weight: Float = 1f, onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(kb.functionKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 16.sp, color = kb.label) }
}

/** Emoji grid with category tabs; "Recent" first when there are any. */
@Composable
fun EmojiPanel(recent: List<String>, onEmoji: (String) -> Unit, onBackspace: () -> Unit, onClose: () -> Unit) {
    val kb = LocalKb.current
    val cats = remember(recent.isEmpty()) {
        (if (recent.isEmpty()) emptyList() else listOf(Emoji.Category("🕘", "Recent", emptyList()))) + Emoji.categories
    }
    var selected by remember { mutableStateOf(0) }
    val current = cats[selected.coerceIn(0, cats.lastIndex)]
    val items = if (current.name == "Recent") recent else current.items
    Column(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(items) { e ->
                Box(
                    Modifier.height(44.dp).clickable { onEmoji(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 26.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).background(kb.functionKey)
                    .clickable(onClick = onClose).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("ABC", fontSize = 15.sp, color = kb.label) }
            LazyRow(Modifier.weight(1f)) {
                itemsIndexed(cats) { i, c ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (i == selected) kb.key else kb.background)
                            .clickable { selected = i },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.icon, fontSize = 20.sp) }
                }
            }
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).background(kb.functionKey)
                    .clickable(onClick = onBackspace).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("⌫", fontSize = 18.sp, color = kb.label) }
        }
    }
}

/**
 * Clipboard: shows what's copied right now (read only because you opened this panel)
 * and your pinned snippets. Tap one to paste it.
 */
@Composable
fun ClipboardPanel(
    readClipboard: () -> String?,
    pinned: List<String>,
    onPaste: (String) -> Unit,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKb.current
    var current by remember { mutableStateOf<String?>(null) }
    // Read once each time the panel is opened (an explicit user action).
    LaunchedEffect(Unit) { current = readClipboard() }
    Column(Modifier.fillMaxWidth().height(PANEL_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Clipboard", fontSize = 14.sp, color = kb.label.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(kb.functionKey)
                    .clickable(onClick = onClose).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("ABC", fontSize = 14.sp, color = kb.label) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                val c = current
                if (c.isNullOrEmpty()) {
                    Text("Nothing copied right now.", fontSize = 14.sp, color = kb.label.copy(alpha = 0.6f))
                } else {
                    ClipRow(c, actionLabel = if (c in pinned) "Pinned" else "📌 Pin",
                        onTap = { onPaste(c) }, onAction = { if (c !in pinned) onPin(c) })
                }
            }
            if (pinned.isNotEmpty()) item {
                Text("Pinned", fontSize = 13.sp, color = kb.label.copy(alpha = 0.6f))
            }
            items(pinned) { p -> ClipRow(p, actionLabel = "✕", onTap = { onPaste(p) }, onAction = { onUnpin(p) }) }
        }
    }
}

@Composable
private fun ClipRow(text: String, actionLabel: String, onTap: () -> Unit, onAction: () -> Unit) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(kb.key),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, fontSize = 15.sp, color = kb.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onTap).padding(10.dp),
        )
        Text(
            actionLabel, fontSize = 14.sp, color = kb.accent,
            modifier = Modifier.clickable(onClick = onAction).padding(10.dp),
        )
    }
}

/** Cursor arrows and editing actions, for precise editing without long-pressing the text. */
@Composable
fun EditPanel(onEdit: (EditAction) -> Unit, onBackspace: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(PANEL_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("Select all") { onEdit(EditAction.SelectAll) }
            PanelButton("▲") { onEdit(EditAction.Up) }
            PanelButton("Copy") { onEdit(EditAction.Copy) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("◀") { onEdit(EditAction.Left) }
            PanelButton("Home") { onEdit(EditAction.Home) }
            PanelButton("End") { onEdit(EditAction.End) }
            PanelButton("▶") { onEdit(EditAction.Right) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("Cut") { onEdit(EditAction.Cut) }
            PanelButton("▼") { onEdit(EditAction.Down) }
            PanelButton("Paste") { onEdit(EditAction.Paste) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("ABC", onClick = onClose)
            PanelButton("⌫", onClick = onBackspace)
        }
    }
}
