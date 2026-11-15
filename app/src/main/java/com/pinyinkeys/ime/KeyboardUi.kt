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
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
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

private val NUMBER_ROW = "1234567890"
private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOL_PAGES = listOf(
    listOf("+×÷=/_<>[]", "!@#\$%^&*()", "-'\":;,?"),
    listOf("`~\\|{}€£¥•", "°©®™✓¶§·…¡", "«»¿‰¤¢♪"),
)

/**
 * Colors modeled on a typical phone keyboard: black/light-grey background, rounded keys,
 * and darker "function" keys (shift, backspace, ?123, enter).
 */
data class KbColors(
    val background: Color,
    val toolbar: Color,
    val key: Color,
    val functionKey: Color,
    val pressed: Color,
    val label: Color,
    val accent: Color,
)

private val DarkKb = KbColors(
    background = Color(0xFF000000), toolbar = Color(0xFF1B1B1D), key = Color(0xFF3A3A3C),
    functionKey = Color(0xFF1E1E20), pressed = Color(0xFF5A5A5E), label = Color(0xFFFFFFFF),
    accent = Color(0xFF7FA6FF),
)
private val LightKb = KbColors(
    background = Color(0xFFE8E9ED), toolbar = Color(0xFFF4F4F6), key = Color(0xFFFFFFFF),
    functionKey = Color(0xFFC6CAD1), pressed = Color(0xFFB0B4BB), label = Color(0xFF111111),
    accent = Color(0xFF2F6BFF),
)
private val LocalKb = staticCompositionLocalOf { DarkKb }

/** Shift key: off, one capital letter, or caps lock (tap cycles through them). */
enum class Shift { Off, On, Lock }

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
    savedStates: Map<Sentence, Load<Unit>>,
    onSave: (Sentence) -> Unit,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    shift: Shift,
    onShift: () -> Unit,
    chinese: Boolean,
    onToggleChinese: () -> Unit,
    composing: String,
    candidates: List<Candidate>,
    onCandidate: (Candidate) -> Unit,
) {
    // 0 = letters, 1 and 2 = symbol pages.
    var page by remember { mutableStateOf(0) }
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val kb = if (dark) DarkKb else LightKb

    MaterialTheme(colorScheme = colors) {
      CompositionLocalProvider(LocalKb provides kb) {
        Surface(color = kb.background) {
            // While typing pinyin, the suggestion bar takes the toolbar's place (like other keyboards).
            val toolbar: @Composable () -> Unit = {
                if (composing.isNotEmpty()) CandidateBar(composing, candidates, onCandidate)
                else Toolbar(sensitive, mode, onMode, assist, onTranslate, onInsertChinese,
                    practice, onStartPractice, onHint, onSwitchKeyboard)
            }
            val panel: @Composable () -> Unit = {
                if (!sensitive) when (mode) {
                    Mode.Assist -> AssistPanel(assist, detailsStates, onExpand, whyStates, onWhy, savedStates, onSave)
                    Mode.Practice -> PracticePanel(practice)
                }
            }
            val keys: @Composable () -> Unit = {
                Keys(page, { page = it }, shift, onShift, chinese, onToggleChinese, onText, onBackspace, onEnter, onSwitchKeyboard)
            }
            // BoxWithConstraints tells us how wide the keyboard window is.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = maxWidth
                if (width >= 600.dp && !sensitive) {
                    // Wide screen (foldable's inner screen): cards on the left, keys on the right.
                    Column {
                        toolbar()
                        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { panel() }
                            Column(Modifier.width(minOf(width * 0.55f, 560.dp))) { keys() }
                        }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center).widthIn(max = 720.dp).fillMaxWidth()) {
                        toolbar()
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            panel()
                            keys()
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * The slim bar above the keys: mode switch, Translate (or Restart), the next action
 * (Insert Chinese / Hint).
 */
@Composable
private fun Toolbar(
    sensitive: Boolean,
    mode: Mode,
    onMode: (Mode) -> Unit,
    assist: AssistState,
    onTranslate: () -> Unit,
    onInsertChinese: () -> Unit,
    practice: PracticeState,
    onStartPractice: () -> Unit,
    onHint: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!sensitive) {
            // Tap to switch between Assist and Practice.
            Pill(if (mode == Mode.Assist) "Assist ⇄" else "Practice ⇄", selected = true) {
                onMode(if (mode == Mode.Assist) Mode.Practice else Mode.Assist)
            }
            val loading = assist is AssistState.Loading || practice is PracticeState.Loading ||
                (assist is AssistState.Result && !assist.complete)
            when (mode) {
                Mode.Assist -> Pill("Translate", enabled = !loading, onClick = onTranslate)
                Mode.Practice -> Pill(if (practice is PracticeState.Active) "Restart" else "Translate",
                    enabled = !loading, onClick = onStartPractice)
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = kb.accent)
            Spacer(Modifier.weight(1f))
            if (mode == Mode.Assist && assist is AssistState.Result && assist.complete) Pill("Insert", accent = true, onClick = onInsertChinese)
            if (mode == Mode.Practice && practice is PracticeState.Active && !practice.done) Pill("Hint", onClick = onHint)
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** A small rounded toolbar button. */
@Composable
private fun Pill(text: String, selected: Boolean = false, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (accent) kb.accent else if (selected) kb.key else kb.functionKey)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (accent) Color.White else kb.label.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** Suggestions for the pinyin being typed. Tap one to put it in the message. */
@Composable
private fun CandidateBar(composing: String, candidates: List<Candidate>, onPick: (Candidate) -> Unit) {
    val kb = LocalKb.current
    Row(Modifier.fillMaxWidth().height(48.dp).background(kb.toolbar), verticalAlignment = Alignment.CenterVertically) {
        Text(composing, fontSize = 13.sp, color = kb.label.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(candidates) { i, c ->
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i == 0) kb.key else kb.toolbar)
                        .clickable { onPick(c) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(c.word, fontSize = 20.sp, color = kb.label) }
            }
        }
    }
}

/** Number row, letter (or symbol) rows and the bottom row. */
@Composable
private fun Keys(
    page: Int,
    onPage: (Int) -> Unit,
    shift: Shift,
    onShift: () -> Unit,
    chinese: Boolean,
    onToggleChinese: () -> Unit,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    val kb = LocalKb.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        KeyRow { NUMBER_ROW.forEach { c -> Key(c.toString(), preview = true, height = NUMBER_KEY_HEIGHT, fontSize = 19) { onText(c.toString()) } } }
        if (page == 0) {
            LETTER_ROWS.forEachIndexed { i, row ->
                KeyRow {
                    if (i == 1) Spacer1(0.5f)
                    if (i == 2) Key(
                        if (shift == Shift.Lock) "⇪" else "⇧", 1.5f, function = true,
                        labelColor = if (shift != Shift.Off) kb.accent else null, onClick = onShift,
                    )
                    row.forEach { c ->
                        val label = if (shift != Shift.Off) c.uppercase() else c.toString()
                        Key(label, preview = true) { onText(label) }
                    }
                    if (i == 1) Spacer1(0.5f)
                    if (i == 2) Key("⌫", 1.5f, function = true, repeat = true, onClick = onBackspace)
                }
            }
        } else {
            val rows = SYMBOL_PAGES[page - 1]
            rows.forEachIndexed { i, row ->
                KeyRow {
                    if (i == 2) Key(if (page == 1) "1/2" else "2/2", 1.5f, function = true) { onPage(if (page == 1) 2 else 1) }
                    row.forEach { c -> Key(c.toString(), preview = true) { onText(c.toString()) } }
                    if (i == 2) Key("⌫", 1.5f, function = true, repeat = true, onClick = onBackspace)
                }
            }
        }
        KeyRow {
            Key(if (page == 0) "!#1" else "ABC", 1.4f, function = true) { onPage(if (page == 0) 1 else 0) }
            LanguageKey(chinese, onToggleChinese)
            Key(if (chinese) "，" else ",", 1f) { onText(",") }
            Key("␣", 4.4f) { onText(" ") }
            Key(if (chinese) "。" else ".", 1f) { onText(".") }
            Key("⏎", 1.4f, function = true, onClick = onEnter)
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** "中/En" key: the active language is drawn brighter. */
@Composable
private fun RowScope.LanguageKey(chinese: Boolean, onClick: () -> Unit) {
    val kb = LocalKb.current
    val view = LocalView.current
    Box(
        Modifier
            .weight(1f)
            .height(KEY_HEIGHT)
            .clip(KEY_SHAPE)
            .background(kb.functionKey)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
    ) {
        val on = kb.label
        val off = kb.label.copy(alpha = 0.4f)
        // "中" top-left, "/" in the middle, "En" bottom-right, each small enough not to overlap.
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Text("中", fontSize = 11.sp, color = if (chinese) on else off,
                modifier = Modifier.offset(y = (-5).dp))
            Text("/", fontSize = 14.sp, color = off)
            Text("En", fontSize = 12.sp, color = if (chinese) off else on,
                modifier = Modifier.offset(y = 5.dp))
        }
    }
}

private val KEY_HEIGHT = 42.dp
private val NUMBER_KEY_HEIGHT = 36.dp
/** Slightly rounded, mostly square corners, like a standard keyboard. */
private val KEY_SHAPE = RoundedCornerShape(6.dp)

/**
 * One key. [preview] shows a bigger copy of the letter above your finger while pressed;
 * [repeat] keeps firing while held (used for backspace); [function] keys (shift, enter...)
 * are drawn darker. Every press gives a light tap vibration, like other keyboards.
 */
@Composable
private fun RowScope.Key(
    label: String,
    weight: Float = 1f,
    preview: Boolean = false,
    repeat: Boolean = false,
    function: Boolean = false,
    labelColor: Color? = null,
    height: Dp = KEY_HEIGHT,
    fontSize: Int? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKb.current
    // Our own pressed state: the key types on touch-DOWN (like other keyboards), not on
    // release, which is what makes typing feel immediate.
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val latestOnClick by rememberUpdatedState(onClick)
    if (repeat) {
        // Held down: after a short pause, keep repeating until released.
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            // Start repeating sooner and speed up the longer it's held (like other keyboards):
            // 50 ms per step at first, down to 20 ms.
            delay(300)
            var interval = 50L
            while (true) {
                latestOnClick()
                delay(interval)
                if (interval > 20) interval -= 3
            }
        }
    }
    val touch = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            latestOnClick()
            waitForUpOrCancellation()
            pressed = false
        }
    }
    // zIndex: draw a pressed key (and its preview) on top of its neighbours.
    Box(Modifier.weight(weight).height(height).zIndex(if (pressed) 1f else 0f)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(KEY_SHAPE)
                .background(if (pressed) kb.pressed else if (function) kb.functionKey else kb.key)
                .then(touch),
            contentAlignment = Alignment.Center,
        ) {
            // Short labels (letters, symbols) big; words like "ABC" or "1/2" a bit smaller.
            val size = fontSize ?: if (label.length > 1) 17 else 22
            Text(label, fontSize = size.sp, color = labelColor ?: kb.label)
        }
        if (preview && pressed) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-50).dp)
                    .size(width = 46.dp, height = 48.dp)
                    .clip(KEY_SHAPE)
                    .background(kb.pressed),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 26.sp, color = kb.label)
            }
        }
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
    detailsStates: Map<Sentence, Load<SentenceDetails>>,
    onExpand: (Sentence) -> Unit,
    whyStates: Map<Sentence, Load<String>>,
    onWhy: (Sentence) -> Unit,
    savedStates: Map<Sentence, Load<Unit>>,
    onSave: (Sentence) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state is AssistState.Error) Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        if (state is AssistState.Result) {
            // Cap the height so the keys stay on screen; scroll if there are many sentences.
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.result.sentences.forEachIndexed { i, sentence ->
                    // key(): remember each card's expanded state separately, reset on a new translation.
                    androidx.compose.runtime.key(state.result, i) {
                        SentenceCard(sentence, detailsStates[sentence], whyStates[sentence], savedStates[sentence], { onExpand(sentence) }, { onWhy(sentence) }, { onSave(sentence) })
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
    saved: Load<Unit>?,
    onExpand: () -> Unit,
    onWhy: () -> Unit,
    onSave: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalKb.current.key)
            .clickable {
                expanded = !expanded
                if (expanded) onExpand()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sentence.hanzi, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(sentence.pinyin, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            // Bookmark: ☆ not saved, ★ saved. Its own tap target, so it doesn't open the card.
            when (saved) {
                Load.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> TextButton(onClick = onSave, enabled = saved !is Load.Loaded) {
                    Text(if (saved is Load.Loaded) "★" else "☆", fontSize = 22.sp)
                }
            }
        }
        if (saved is Load.Error) Text(saved.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
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
private fun PracticePanel(state: PracticeState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state is PracticeState.Error) Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        if (state is PracticeState.Active) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(LocalKb.current.key)
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
