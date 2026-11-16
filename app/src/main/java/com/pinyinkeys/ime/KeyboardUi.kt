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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
internal val LocalKb = staticCompositionLocalOf { DarkKb }

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
    recentEmoji: List<String>,
    onEmoji: (String) -> Unit,
    readClipboard: () -> String?,
    pinnedClips: List<String>,
    clipHistory: List<String>,
    onPaste: (String) -> Unit,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onDeleteClip: (String) -> Unit,
    onClearClipHistory: () -> Unit,
    showPasteChip: Boolean,
    onPasteChip: () -> Unit,
    pastePreview: String?,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    toolsForced: Boolean,
    onShowTools: () -> Unit,
    correction: Pair<String, String>?,
    onUndoCorrection: () -> Unit,
    onSpeak: (String) -> Unit,
    guidedDone: Int,
    onEdit: (EditAction) -> Unit,
    onOpenSettings: () -> Unit,
    numberPad: NumberPadKind?,
    enterLabel: String?,
) {
    // Which panel shows instead of the letter keys (emoji, clipboard, editing).
    var panelShown by remember { mutableStateOf(Panel.Keys) }
    val togglePanel: (Panel) -> Unit = { p -> panelShown = if (panelShown == p) Panel.Keys else p }
    var emojiTab by remember { mutableStateOf(1) }

    // 0 = letters, 1 and 2 = symbol pages.
    var page by remember { mutableStateOf(0) }
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val kb = if (dark) DarkKb else LightKb

    MaterialTheme(colorScheme = colors) {
      CompositionLocalProvider(LocalKb provides kb, LocalSpeak provides onSpeak) {
        Surface(color = kb.background) {
            // While typing pinyin, the suggestion bar takes the toolbar's place (like other keyboards).
            val toolbar: @Composable () -> Unit = {
                val close = { panelShown = Panel.Keys }
                if (composing.isNotEmpty()) CandidateBar(composing, candidates, onCandidate)
                else if (panelShown == Panel.Emoji) EmojiHeader(emojiTab, { emojiTab = it }, close, onBackspace)
                else if (panelShown == Panel.Clipboard && !sensitive) ClipboardHeader(close, onClearClipHistory)
                // Like a standard keyboard, the bar changes with what you're doing: word
                // suggestions while typing, a Paste button after a copy, otherwise the tools.
                // (Translate results and Practice keep the tools so their buttons stay visible.)
                // Right after an autocorrect: "teh → the", tap to undo.
                else if (!toolsForced && !sensitive && correction != null)
                    CorrectionBar(correction, onUndoCorrection)
                // A fresh copy or screenshot comes first (it disappears once you type).
                else if (!toolsForced && !sensitive && showPasteChip && pastePreview != null &&
                    assist is AssistState.Idle
                ) PasteBar(pastePreview, onShowTools, onPasteChip)
                else if (!toolsForced && !sensitive && assist is AssistState.Idle &&
                    (mode == Mode.Assist || practice is PracticeState.Idle) && suggestions.isNotEmpty()
                ) SuggestionBar(suggestions, onShowTools, onSuggestion)
                else Toolbar(sensitive || numberPad != null, mode, onMode, assist, onTranslate, onInsertChinese,
                    practice, onStartPractice, onHint, panelShown, togglePanel, onOpenSettings,
                    showPasteChip && !sensitive, onPasteChip)
            }
            val panel: @Composable () -> Unit = {
                if (!sensitive) when (mode) {
                    Mode.Assist -> AssistPanel(assist, detailsStates, onExpand, whyStates, onWhy, savedStates, onSave, guidedDone)
                    Mode.Practice -> PracticePanel(practice)
                }
            }
            val keys: @Composable () -> Unit = {
                val close = { panelShown = Panel.Keys }
                when (panelShown) {
                    Panel.Keys -> if (numberPad != null)
                        NumberPad(numberPad, enterLabel, onText, onBackspace, onEnter)
                    else Keys(page, { page = it }, shift, onShift, chinese, onToggleChinese, onText, onBackspace, onEnter, onSwitchKeyboard)
                    Panel.Emoji -> EmojiGrid(emojiTab, recentEmoji, onEmoji)
                    // No clipboard or editing panel in password fields.
                    Panel.Clipboard -> if (sensitive) Keys(page, { page = it }, shift, onShift, chinese, onToggleChinese, onText, onBackspace, onEnter, onSwitchKeyboard)
                        else {
                            // Newest copy first (marked "Latest"), then pinned items, then older copies.
                            val latest = clipHistory.firstOrNull()
                            val items = buildList {
                                if (latest != null) add(ClipItem(latest, latest in pinnedClips, current = true))
                                pinnedClips.filter { it != latest }.forEach { add(ClipItem(it, pinned = true, current = false)) }
                                clipHistory.drop(1).filter { it !in pinnedClips }.forEach { add(ClipItem(it, pinned = false, current = false)) }
                            }
                            ClipboardGrid(
                                items,
                                onPaste = { onPaste(it); close() },
                                onTogglePin = { if (it.pinned) onUnpin(it.text) else onPin(it.text) },
                                onDelete = { onDeleteClip(it.text) },
                            )
                        }
                    Panel.Edit -> EditPanel(onEdit, onBackspace, close)
                }
            }
            // BoxWithConstraints tells us how wide the keyboard window is.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Wide screen (foldable's inner screen): like a standard keyboard, keep the
                // keys centred at about 88% of the width and make them a little taller.
                val wide = maxWidth >= 600.dp
                val contentWidth = if (wide) maxWidth * 0.88f else minOf(maxWidth, 720.dp)
                CompositionLocalProvider(LocalKeyHeights provides if (wide) WideKeys else NormalKeys) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        toolbar()
                        Column(
                            Modifier.width(contentWidth).padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
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
    panel: Panel,
    onPanel: (Panel) -> Unit,
    onOpenSettings: () -> Unit,
    showPasteChip: Boolean,
    onPasteChip: () -> Unit,
) {
    val kb = LocalKb.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Left part scrolls sideways if the screen is too narrow for everything.
      Row(
          Modifier.weight(1f).horizontalScroll(rememberScrollState()),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Something was just copied: offer it (the text is read only when tapped).
        if (showPasteChip) IconPill("📋", true, onClick = onPasteChip)
        if (!sensitive) {
            // Tap to switch between Assist and Practice.
            // 助 = Assist (help), 练 = Practice. Tap to switch.
            IconPill(if (mode == Mode.Assist) "助" else "练", true) {
                onMode(if (mode == Mode.Assist) Mode.Practice else Mode.Assist)
            }
            val loading = assist is AssistState.Loading || practice is PracticeState.Loading ||
                (assist is AssistState.Result && !assist.complete)
            when (mode) {
                // 译 = translate.
                Mode.Assist -> IconPill("译", false, enabled = !loading, onClick = onTranslate)
                Mode.Practice -> IconPill(if (practice is PracticeState.Active) "↻" else "译", false,
                    enabled = !loading, onClick = onStartPractice)
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = kb.accent)
            if (mode == Mode.Assist && assist is AssistState.Result && assist.complete) Pill("Insert", accent = true, onClick = onInsertChinese)
            if (mode == Mode.Practice && practice is PracticeState.Active && !practice.done) Pill("Hint", onClick = onHint)
        }
      }
        // Icon buttons, like a standard keyboard's tool row.
        IconPill("😊", panel == Panel.Emoji) { onPanel(Panel.Emoji) }
        if (!sensitive) IconPill("📋", panel == Panel.Clipboard) { onPanel(Panel.Clipboard) }
        IconPill("✎", panel == Panel.Edit) { onPanel(Panel.Edit) }
        IconPill("⚙", false, onClick = onOpenSettings)
    }
}

/** A round icon button in the toolbar; [active] when its panel is open. */
@Composable
private fun IconPill(icon: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (active) kb.key else kb.functionKey)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(icon, fontSize = 16.sp, color = kb.label.copy(alpha = if (enabled) 1f else 0.4f)) }
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
        KeyRow { NUMBER_ROW.forEach { c -> Key(c.toString(), preview = true, height = LocalKeyHeights.current.number, fontSize = 19) { onText(c.toString()) } } }
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
            .height(LocalKeyHeights.current.key)
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

/** Key heights; a bit taller on the wide inner screen of a foldable. */
data class KeyHeights(val key: Dp, val number: Dp)
internal val NormalKeys = KeyHeights(key = 42.dp, number = 36.dp)
private val WideKeys = KeyHeights(key = 48.dp, number = 40.dp)
internal val LocalKeyHeights = staticCompositionLocalOf { NormalKeys }
/** Slightly rounded, mostly square corners, like a standard keyboard. */
private val KEY_SHAPE = RoundedCornerShape(6.dp)

/**
 * One key. [preview] shows a bigger copy of the letter above your finger while pressed;
 * [repeat] keeps firing while held (used for backspace); [function] keys (shift, enter...)
 * are drawn darker. Every press gives a light tap vibration, like other keyboards.
 */
@Composable
internal fun RowScope.Key(
    label: String,
    weight: Float = 1f,
    preview: Boolean = false,
    repeat: Boolean = false,
    function: Boolean = false,
    labelColor: Color? = null,
    height: Dp = LocalKeyHeights.current.key,
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
    guidedDone: Int,
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
                        SentenceCard(sentence, detailsStates[sentence], whyStates[sentence], savedStates[sentence], { onExpand(sentence) }, { onWhy(sentence) }, { onSave(sentence) }, done = i < guidedDone)
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
    done: Boolean = false,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sentence.hanzi, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                    // ✓ once you've typed this sentence yourself in 中 mode.
                    if (done) Text("  ✓", fontSize = 20.sp, color = androidx.compose.ui.graphics.Color(0xFF34C759))
                }
                Text(sentence.pinyin, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            // Small icon buttons so the sentence gets most of the width.
            // 🔊 reads the Chinese aloud; ☆/★ saves the sentence.
            val speak = LocalSpeak.current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallIcon("🔊") { speak(sentence.hanzi) }
                when (saved) {
                    Load.Loading -> CircularProgressIndicator(Modifier.padding(6.dp).size(16.dp), strokeWidth = 2.dp)
                    else -> SmallIcon(if (saved is Load.Loaded) "★" else "☆", enabled = saved !is Load.Loaded, onClick = onSave)
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
                    val speakWord = LocalSpeak.current
                    details.value.chunks.forEach { chunk ->
                        // Each word: characters, pinyin, English meaning, grammar role.
                        // Tap it to hear just that word.
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { speakWord(chunk.hanzi) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(chunk.hanzi, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(chunk.pinyin, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            if (chunk.meaning.isNotEmpty()) Text(
                                chunk.meaning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(chunk.role, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
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

/** "‹" button at the left of the ribbon: shows the tools instead. */
@Composable
private fun ToolsButton(onClick: () -> Unit) {
    val kb = LocalKb.current
    Box(
        Modifier.size(width = 40.dp, height = 44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("‹", fontSize = 26.sp, color = kb.label.copy(alpha = 0.8f)) }
}

/** Three word suggestions, best in the middle (like other keyboards). Tap to use one. */
@Composable
private fun SuggestionBar(words: List<String>, onTools: () -> Unit, onPick: (String) -> Unit) {
    val kb = LocalKb.current
    // Order on screen: 2nd, 1st (middle, bold), 3rd.
    val shown = when (words.size) {
        1 -> listOf(null, words[0], null)
        2 -> listOf(words[1], words[0], null)
        else -> listOf(words[1], words[0], words[2])
    }
    Row(Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar), verticalAlignment = Alignment.CenterVertically) {
        ToolsButton(onTools)
        shown.forEachIndexed { i, w ->
            if (i > 0) Box(Modifier.size(width = 1.dp, height = 20.dp).background(kb.label.copy(alpha = 0.2f)))
            Box(
                Modifier.weight(1f).height(44.dp).clickable(enabled = w != null) { w?.let(onPick) },
                contentAlignment = Alignment.Center,
            ) {
                // Each word slides/fades in when it changes, like other keyboards.
                AnimatedContent(
                    targetState = w,
                    transitionSpec = {
                        (fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 }) togetherWith
                            fadeOut(tween(90))
                    },
                    label = "suggestion",
                ) { word ->
                    // The "keep what I typed" option is shown in quotes.
                    if (word != null) Text(
                        if (word.startsWith(PinyinKeysService.LITERAL)) "\"${word.removePrefix(PinyinKeysService.LITERAL)}\"" else word,
                        fontSize = 17.sp, color = kb.label, maxLines = 1,
                        fontWeight = if (i == 1) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Box(Modifier.width(40.dp))
    }
}

/** After a copy: a Paste button with a preview of what was copied. */
@Composable
private fun PasteBar(preview: String, onTools: () -> Unit, onPaste: () -> Unit) {
    val kb = LocalKb.current
    Row(Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar), verticalAlignment = Alignment.CenterVertically) {
        ToolsButton(onTools)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(kb.key)
                    .clickable(onClick = onPaste)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📋 ", fontSize = 14.sp)
                Text(preview, fontSize = 14.sp, color = kb.label, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(40.dp))
    }
}

/** Lets any card read text aloud without passing the callback through every function. */
val LocalSpeak = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Briefly shown after an autocorrect, sliding in: "teh → the". Tap to undo. */
@Composable
private fun CorrectionBar(correction: Pair<String, String>, onUndo: () -> Unit) {
    val kb = LocalKb.current
    var shown by remember(correction) { mutableStateOf(false) }
    LaunchedEffect(correction) { shown = true }
    Box(Modifier.fillMaxWidth().height(44.dp).background(kb.toolbar), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 2 },
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(17.dp)).background(kb.key).clickable(onClick = onUndo)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(correction.first, fontSize = 16.sp, color = kb.label.copy(alpha = 0.6f))
                Text("  →  ", fontSize = 16.sp, color = kb.accent)
                Text(correction.second, fontSize = 16.sp, color = kb.label, fontWeight = FontWeight.SemiBold)
                Text("   tap to undo", fontSize = 12.sp, color = kb.label.copy(alpha = 0.5f))
            }
        }
    }
}

/** A compact tappable icon (smaller than a standard button). */
@Composable
private fun SmallIcon(icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(icon, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary) }
}
