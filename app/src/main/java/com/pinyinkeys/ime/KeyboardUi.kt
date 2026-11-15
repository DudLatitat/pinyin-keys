package com.pinyinkeys.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.text.font.FontWeight
import com.pinyinkeys.ime.translate.Sentence
import com.pinyinkeys.ime.translate.SentenceDetails
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOL_ROWS = listOf("1234567890", "@#\$%&-+()/", "*\"':;!?")

/**
 * The QWERTY keyboard. Pure UI: every key just calls one of the callbacks,
 * and the service turns that into edits on the text field.
 *
 * When `sensitive` is true (password / incognito fields) the Assist panel is hidden
 * entirely and the keyboard is plain.
 */
@Composable
fun KeyboardUi(
    sensitive: Boolean,
    mode: Mode,
    onMode: (Mode) -> Unit,
    practice: PracticeState,
    onStartPractice: () -> Unit,
    onHint: () -> Unit,
    assist: AssistState,
    onTranslate: () -> Unit,
    onInsertChinese: () -> Unit,
    detailsStates: Map<Sentence, Load<SentenceDetails>>,
    onExpand: (Sentence) -> Unit,
    whyStates: Map<Sentence, Load<String>>,
    onWhy: (Sentence) -> Unit,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            // On the wide inner screen of a foldable, cap the width so keys don't get huge.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!sensitive) {
                        ModeToggle(mode, onMode)
                        when (mode) {
                            Mode.Assist -> AssistPanel(assist, onTranslate, onInsertChinese, detailsStates, onExpand, whyStates, onWhy)
                            Mode.Practice -> PracticePanel(practice, onStartPractice, onHint)
                        }
                    }
                    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
                    rows.forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (i == 2) {
                                if (symbols) Spacer1(1.5f)
                                else Key(if (shift) "⇧" else "⇪", 1.5f) { shift = !shift }
                            }
                            if (i == 1) Spacer1(0.5f)
                            row.forEach { c ->
                                val label = if (shift && !symbols) c.uppercase() else c.toString()
                                Key(label) {
                                    onText(label)
                                    shift = false
                                }
                            }
                            if (i == 1) Spacer1(0.5f)
                            if (i == 2) Key("⌫", 1.5f, onClick = onBackspace)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Key(if (symbols) "ABC" else "?123", 1.5f) { symbols = !symbols }
                        Key("🌐", 1f, onClick = onSwitchKeyboard)
                        Key(",", 1f) { onText(",") }
                        Key("space", 4f) { onText(" ") }
                        Key(".", 1f) { onText(".") }
                        Key("⏎", 1.5f, onClick = onEnter)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, weight: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(weight)
            .height(46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.Spacer1(weight: Float) {
    Box(Modifier.weight(weight))
}

/** Translate button, sentence cards and "Insert Chinese", above the keys. */
@Composable
private fun AssistPanel(
    state: AssistState,
    onTranslate: () -> Unit,
    onInsertChinese: () -> Unit,
    detailsStates: Map<Sentence, Load<SentenceDetails>>,
    onExpand: (Sentence) -> Unit,
    whyStates: Map<Sentence, Load<String>>,
    onWhy: (Sentence) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onTranslate, enabled = state !is AssistState.Loading) {
                Text("Translate")
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                is AssistState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is AssistState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                is AssistState.Result -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = onInsertChinese) { Text("Insert Chinese") }
                }
                AssistState.Idle -> {}
            }
        }
        if (state is AssistState.Result) {
            // Cap the height so the keys stay on screen; scroll if there are many sentences.
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.result.sentences.forEachIndexed { i, sentence ->
                    // key(): remember each card's expanded state separately, reset on a new translation.
                    androidx.compose.runtime.key(state.result, i) {
                        SentenceCard(sentence, detailsStates[sentence], whyStates[sentence], { onExpand(sentence) }, { onWhy(sentence) })
                    }
                }
            }
        }
    }
}

/**
 * One sentence: hanzi and pinyin. Tap to expand into structure chips and a "Why?" button;
 * tap again to collapse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceCard(
    sentence: Sentence,
    details: Load<SentenceDetails>?,
    why: Load<String>?,
    onExpand: () -> Unit,
    onWhy: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                expanded = !expanded
                if (expanded) onExpand()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(sentence.hanzi, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(sentence.pinyin, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        if (!expanded) return@Column
        when (details) {
            null, Load.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            is Load.Error -> ErrorRow(details.message, onExpand)
            is Load.Loaded -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    details.value.chunks.forEach { chunk ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(chunk.hanzi, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(chunk.pinyin, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Text(chunk.role, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                when (why) {
                    null -> TextButton(onClick = onWhy) { Text("Why?") }
                    Load.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    is Load.Loaded -> Text(why.value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    is Load.Error -> ErrorRow(why.message, onWhy)
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Assist / Practice switch at the top of the panel. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModeToggle(mode: Mode, onMode: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Mode.entries.forEach { m ->
            FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.name) })
        }
    }
}

/** Practice: the Chinese stays hidden; type each syllable in pinyin to earn the character. */
@Composable
private fun PracticePanel(state: PracticeState, onStart: () -> Unit, onHint: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onStart, enabled = state !is PracticeState.Loading) {
                Text(if (state is PracticeState.Active) "Restart" else "Translate")
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                PracticeState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is PracticeState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                PracticeState.Idle -> Text("Type English, then Translate.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is PracticeState.Active -> if (!state.done) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = onHint) { Text("Hint") }
                    }
                }
            }
        }
        if (state is PracticeState.Active) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.done) {
                    Text("Done! Check it, then send. 🎉", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    val n = state.units.size
                    Text(
                        "Sentence ${state.sentenceIndex + 1}/$n · ${state.doneInSentence}/${state.totalInSentence}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(state.sentences[state.sentenceIndex].en, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    val target = state.current?.syllable.orEmpty()
                    val next = if (state.hintShown) Practice.toToneMarks(target) else "?"
                    Text("Next: $next", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    if (state.buffer.isNotEmpty()) {
                        // Red once what's typed can't become the right syllable.
                        val ok = Practice.isPrefix(state.buffer, target, strictTones = true) ||
                            Practice.isPrefix(state.buffer, target, strictTones = false)
                        Text(
                            state.buffer, fontSize = 20.sp,
                            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
