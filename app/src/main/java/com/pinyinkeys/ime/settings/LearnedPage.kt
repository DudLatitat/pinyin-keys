package com.pinyinkeys.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinyinkeys.ime.Settings

/** One row in the Fixes tab. `line` is the exact stored string, so removal matches it. */
private data class FixEntry(val line: String, val from: String, val to: String, val isPick: Boolean)

/** Parse "a\tb[\t...]" defensively: extra fields (count, day...) are ignored. */
private fun parseFix(line: String, isPick: Boolean): FixEntry? {
    val parts = line.split('\t')
    if (parts.size < 2) return null
    return FixEntry(line, parts[0], parts[1], isPick)
}

private const val PAGE_SIZE = 30

/** What the keyboard learned from your typing; remove anything wrong. */
@Composable
fun LearnedPage(settings: Settings) {
    // Local copies of the stored lists; we write back to Settings on every change.
    var fixes by remember { mutableStateOf(settings.learnedFixes) }
    var picks by remember { mutableStateOf(settings.preferredPicks) }
    var words by remember { mutableStateOf(settings.userWords) }
    var keysLearned by remember { mutableIntStateOf(settings.touchBias.size) }
    var pairs by remember { mutableIntStateOf(settings.nextWordData.lines().count { it.isNotBlank() }) }
    // Tell the keyboard to reload learned data next time it opens.
    fun bump() { settings.learnVersion = settings.learnVersion + 1 }

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(PAGE_SIZE) }
    // Which confirm dialog is open: null, "clear" or "forget".
    var confirm by remember { mutableStateOf<String?>(null) }

    // Stored oldest → newest, so reverse for newest first. Picks and fixes are merged.
    val fixEntries = fixes.asReversed().mapNotNull { parseFix(it, false) } +
        picks.asReversed().mapNotNull { parseFix(it, true) }
    val fixCount = fixEntries.size

    Text(
        "Learned so far: ${words.size} words · $pairs word pairs · $fixCount fixes · " +
            "touch habits for $keysLearned keys",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    // TabRow + Tab: material3's tab strip. selectedTabIndex highlights the current tab.
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0; shown = PAGE_SIZE }, text = { Text("Fixes ($fixCount)") })
        Tab(selected = tab == 1, onClick = { tab = 1; shown = PAGE_SIZE }, text = { Text("Words (${words.size})") })
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; shown = PAGE_SIZE },
        label = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val q = query.trim()

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (tab == 0) {
            Text("Typos I fix for you", style = MaterialTheme.typography.bodySmall)
            val list = fixEntries.filter { q.isEmpty() || it.from.contains(q, true) || it.to.contains(q, true) }
            if (list.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
            list.take(shown).forEach { e ->
                EntryRow("${e.from} → ${e.to}") {
                    if (e.isPick) { picks = picks - e.line; settings.preferredPicks = picks }
                    else { fixes = fixes - e.line; settings.learnedFixes = fixes }
                    bump()
                }
            }
            if (list.size > shown) TextButton(onClick = { shown += PAGE_SIZE }) { Text("Show more") }
        } else {
            Text("Your words (never corrected)", style = MaterialTheme.typography.bodySmall)
            val list = words.asReversed().filter { q.isEmpty() || it.contains(q, true) }
            if (list.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
            list.take(shown).forEach { w ->
                EntryRow(w) { words = words - w; settings.userWords = words; bump() }
            }
            if (list.size > shown) TextButton(onClick = { shown += PAGE_SIZE }) { Text("Show more") }
        }
    }
    OutlinedButton(onClick = { confirm = "clear" }) { Text("Clear this list") }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Touch habits learned for $keysLearned keys", Modifier.weight(1f))
        TextButton(onClick = { settings.touchBias = emptyList(); keysLearned = 0; bump() }) { Text("Reset") }
    }
    HorizontalDivider()
    Button(onClick = { confirm = "forget" }) { Text("Forget everything learned") }
    Text(
        "Also forgets your phrases and touch habits. Long-press a suggestion on the " +
            "keyboard to forget a single word.",
        style = MaterialTheme.typography.bodySmall,
    )

    // AlertDialog is a pop-up drawn over the page while `confirm` is set.
    when (confirm) {
        "clear" -> ConfirmDialog(
            text = if (tab == 0) "Remove all $fixCount learned fixes?" else "Remove all ${words.size} learned words?",
            onDismiss = { confirm = null },
        ) {
            if (tab == 0) {
                fixes = emptyList(); picks = emptyList()
                settings.learnedFixes = fixes; settings.preferredPicks = picks
            } else {
                words = emptyList(); settings.userWords = words
            }
            bump(); confirm = null
        }
        "forget" -> ConfirmDialog(
            text = "Forget all words, fixes, word pairs and touch habits? This can't be undone.",
            onDismiss = { confirm = null },
        ) {
            settings.forgetAllLearning() // also bumps learnVersion itself
            fixes = emptyList(); picks = emptyList(); words = emptyList(); keysLearned = 0; pairs = 0
            confirm = null
        }
    }
}

@Composable
private fun EntryRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
