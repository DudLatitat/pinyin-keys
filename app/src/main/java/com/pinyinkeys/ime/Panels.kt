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

/** Emoji categories with "Recent" first. */
val EMOJI_TABS: List<Emoji.Category> =
    listOf(Emoji.Category("🕘", "Recent", emptyList())) + Emoji.categories

/**
 * Top bar while the emoji panel is open (it replaces the toolbar, like a standard
 * keyboard): back to letters, the category icons, and backspace.
 */
@Composable
fun EmojiHeader(selected: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, onBackspace: () -> Unit) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIcon("⌨", onClick = onClose)
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 22.dp).background(kb.label.copy(alpha = 0.3f)))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(EMOJI_TABS) { i, c ->
                HeaderIcon(c.icon, active = i == selected) { onSelect(i) }
            }
        }
        HeaderIcon("⌫", onClick = onBackspace)
    }
}

/** Big emoji grid (8 per row on a phone), filling the key area. */
@Composable
fun EmojiGrid(selected: Int, recent: List<String>, onEmoji: (String) -> Unit) {
    val kb = LocalKb.current
    val tab = EMOJI_TABS[selected.coerceIn(0, EMOJI_TABS.lastIndex)]
    val items = if (tab.name == "Recent") recent else tab.items
    Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
        if (items.isEmpty()) {
            Text("Emoji you use will show up here.", fontSize = 14.sp, color = kb.label.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize()) {
            items(items) { e ->
                Box(
                    Modifier.height(50.dp).clickable { onEmoji(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 30.sp) }
            }
        }
    }
}

/** A round icon in a panel header; [active] draws a highlight behind it. */
@Composable
private fun HeaderIcon(icon: String, active: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (active) kb.key else kb.toolbar)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(icon, fontSize = 20.sp, color = kb.label) }
}

/** Top bar while the clipboard panel is open. */
@Composable
fun ClipboardHeader(onClose: () -> Unit, onClearHistory: () -> Unit) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIcon("⌨", onClick = onClose)
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 22.dp).background(kb.label.copy(alpha = 0.3f)))
        Text("Clipboard", fontSize = 17.sp, color = kb.label, modifier = Modifier.weight(1f).padding(start = 6.dp))
        HeaderIcon("🗑", onClick = onClearHistory)
    }
}

/** One clipboard item and where it came from. */
data class ClipItem(val text: String, val pinned: Boolean, val current: Boolean)

/**
 * Clipboard as a grid of cards: what's copied now (read only because you opened
 * this panel), pinned snippets, then things you've pasted with this keyboard.
 * Tap a card to paste it; 📌 pins or unpins; ✕ removes it from the history.
 */
@Composable
fun ClipboardGrid(
    items: List<ClipItem>,
    onPaste: (String) -> Unit,
    onTogglePin: (ClipItem) -> Unit,
    onDelete: (ClipItem) -> Unit,
) {
    val kb = LocalKb.current
    Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
        if (items.isEmpty()) {
            Text("Copy something, then open this again.", fontSize = 14.sp, color = kb.label.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items) { item ->
                Column(
                    Modifier
                        .height(108.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (item.current) kb.functionKey else kb.key)
                        .clickable { onPaste(item.text) }
                        .padding(start = 10.dp, end = 4.dp, top = 8.dp),
                ) {
                    Text(item.text, fontSize = 14.sp, color = kb.label, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.current) Text("Now", fontSize = 11.sp, color = kb.accent, modifier = Modifier.weight(1f))
                        else Box(Modifier.weight(1f))
                        Text(
                            "📌", fontSize = 14.sp,
                            color = kb.label.copy(alpha = if (item.pinned) 1f else 0.35f),
                            modifier = Modifier.clickable { onTogglePin(item) }.padding(6.dp),
                        )
                        if (!item.pinned && !item.current) Text(
                            "✕", fontSize = 13.sp, color = kb.label.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { onDelete(item) }.padding(6.dp),
                        )
                    }
                }
            }
        }
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
