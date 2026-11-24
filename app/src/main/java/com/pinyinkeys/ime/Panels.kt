package com.pinyinkeys.ime

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.asImageBitmap

/** Which panel replaces the letter keys. */
enum class Panel { Keys, Emoji, Clipboard, Edit }

/** Cursor and editing actions for the text-editing panel. */
enum class EditAction { Left, Right, Up, Down, Home, End, SelectAll, Cut, Copy, Paste }

/** Same height as the key area, so switching panels doesn't make the keyboard jump. */
val PANEL_HEIGHT: androidx.compose.ui.unit.Dp
    @Composable get() = LocalKeyHeights.current.let { it.number + it.key * 4 + KEY_GAP_V * 5 }

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
fun EmojiHeader(selected: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, onBackspace: () -> Unit, onSearch: () -> Unit = {}) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIcon(Icons.Outlined.Keyboard, "Keyboard", onClick = onClose)
        HeaderIcon(Icons.Outlined.Search, "Search emoji", onClick = onSearch)
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 22.dp).background(kb.label.copy(alpha = 0.3f)))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(EMOJI_TABS) { i, c ->
                HeaderIcon(c.icon, active = i == selected) { onSelect(i) }
            }
        }
        HeaderIcon(Icons.AutoMirrored.Outlined.Backspace, "Delete", onClick = onBackspace)
    }
}

/**
 * Emoji search: the query (typed with the keys below) and matching emoji in a row.
 * Tap one to type it; ✕ goes back to the emoji grid.
 */
@Composable
fun EmojiSearchBar(query: String, onEmoji: (String) -> Unit, onClose: () -> Unit) {
    val kb = LocalKb.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // The keyword list is loaded once, in the background, the first time you search.
    val index by androidx.compose.runtime.produceState<EmojiSearch?>(EmojiSearch.loaded) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EmojiSearch.get(context) }
    }
    val results = remember(query, index) { index?.search(query) ?: emptyList() }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(kb.toolbar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderIcon(Icons.Outlined.Close, "Close search", onClick = onClose)
        Text(
            if (query.isEmpty()) "Search emoji…" else query,
            fontSize = 15.sp, maxLines = 1,
            color = kb.label.copy(alpha = if (query.isEmpty()) 0.5f else 1f),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results) { e ->
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).clickable { onEmoji(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 26.sp) }
            }
        }
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
            items(items) { e -> EmojiCell(e, onEmoji) }
        }
    }
}

/**
 * One emoji. Tap: type it once. Hold: type it, then keep repeating until you let go.
 * Scrolling the grid cancels the press, so swiping doesn't type anything.
 */
@Composable
private fun EmojiCell(e: String, onEmoji: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest by androidx.compose.runtime.rememberUpdatedState(onEmoji)
    Box(
        Modifier.height(50.dp).pointerInput(e) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var held = false
                val repeat = scope.launch {
                    delay(400)
                    held = true
                    while (true) {
                        latest(e)
                        delay(90)
                    }
                }
                val up = waitForUpOrCancellation()
                repeat.cancel()
                if (up != null && !held) latest(e)  // a normal tap
            }
        },
        contentAlignment = Alignment.Center,
    ) { Text(e, fontSize = 30.sp) }
}

/** A round line-icon button in a panel header. */
@Composable
private fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(kb.toolbar).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(22.dp), tint = kb.label) }
}

/** A round icon in a panel header (emoji category tabs); [active] draws a highlight behind it. */
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
        HeaderIcon(Icons.Outlined.Keyboard, "Keyboard", onClick = onClose)
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 22.dp).background(kb.label.copy(alpha = 0.3f)))
        Text("Clipboard", fontSize = 17.sp, color = kb.label, modifier = Modifier.weight(1f).padding(start = 6.dp))
        HeaderIcon(Icons.Outlined.DeleteSweep, "Clear history", onClick = onClearHistory)
    }
}

/** One clipboard item and where it came from. */
data class ClipItem(val text: String, val pinned: Boolean, val current: Boolean)

/** Small preview of a copied image, decoded at reduced size to keep it quick. */
@Composable
private fun ClipImage(entry: String, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(entry) {
        Clips.imageFile(context, entry)?.takeIf { it.exists() }?.let { f ->
            android.graphics.BitmapFactory.decodeFile(
                f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 },
            )?.asImageBitmap()
        }
    }
    if (bitmap != null) androidx.compose.foundation.Image(
        bitmap, contentDescription = "Copied image", modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    ) else Text("🖼 Image", fontSize = 14.sp, color = LocalKb.current.label, modifier = modifier)
}

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
                    if (Clips.isImage(item.text)) {
                        ClipImage(item.text, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                    } else {
                        Text(item.text, fontSize = 14.sp, color = kb.label, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.current) Text("Latest", fontSize = 11.sp, color = kb.accent, modifier = Modifier.weight(1f))
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
fun EditPanel(
    onEdit: (EditAction) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onOneHanded: () -> Unit = {},
) {
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
            PanelButton("↶ Undo", onClick = onUndo)
            PanelButton("↷ Redo", onClick = onRedo)
            PanelButton("One-handed", onClick = onOneHanded)
            PanelButton("⌫", onClick = onBackspace)
        }
    }
}
