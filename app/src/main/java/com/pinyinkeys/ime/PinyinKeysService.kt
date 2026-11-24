package com.pinyinkeys.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pinyinkeys.ime.translate.Sentence
import com.pinyinkeys.ime.translate.Chunk
import com.pinyinkeys.ime.saved.AppDatabase
import com.pinyinkeys.ime.saved.SavedSentence
import com.pinyinkeys.ime.translate.SentenceDetails
import com.pinyinkeys.ime.translate.TranslationService
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import androidx.lifecycle.lifecycleScope
import com.pinyinkeys.ime.translate.TranslationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The keyboard. Android calls this service whenever a text field wants a keyboard
 * and the user has picked "Pinyin Keys" as their input method.
 *
 * Compose normally runs inside an Activity, which provides a Lifecycle, a
 * SavedStateRegistry and a ViewModelStore. An InputMethodService has none of these,
 * so we implement the three "owner" interfaces ourselves and attach them to the
 * keyboard window. Without that, ComposeView crashes on first draw.
 */
class PinyinKeysService : InputMethodService(),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    internal val lifecycleRegistry = LifecycleRegistry(this)
    internal val savedStateController = SavedStateRegistryController.create(this)
    internal val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /** True in password / "incognito" fields. Later milestones hide all translation UI then. */
    internal val sensitiveField = mutableStateOf(false)

    /** Number pad layout for number/phone/date fields, or null for the normal keyboard. */
    internal val numberPad = mutableStateOf<NumberPadKind?>(null)
    /** Label for the enter key, from what the app asks for (Done, Next, Go, Send...). */
    internal val enterLabel = mutableStateOf<String?>(null)
    /** Polite tone for translations (toolbar 随/敬). */
    internal val tone = mutableStateOf(com.pinyinkeys.ime.translate.Tone.Casual)
    internal val theme = mutableStateOf("System")
    internal val numberRow = mutableStateOf(true)
    internal val keyScale = mutableStateOf(1f)
    internal val toneColors = mutableStateOf(true)
    internal val feedback = mutableStateOf(KeyFeedback())

    /** What the Assist panel shows. Compose redraws whenever this changes. */
    internal val assistState = mutableStateOf<AssistState>(AssistState.Idle)
    internal var translateJob: Job? = null
    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal val mode = mutableStateOf(Mode.Assist)

    // Typing state.
    internal val shift = mutableStateOf(Shift.Off)
    /** Chinese mode: letters build pinyin, and the candidate bar suggests characters. */
    internal val chinese = mutableStateOf(false)
    internal val composing = mutableStateOf("")
    internal val candidates = mutableStateOf<List<Candidate>>(emptyList())
    internal var dictionary: PinyinDictionary? = null

    internal val recentEmoji = mutableStateOf<List<String>>(emptyList())
    internal val pinnedClips = mutableStateOf<List<String>>(emptyList())
    /** Things pasted with this keyboard, newest first (only text the user chose to paste). */
    internal val clipHistory = mutableStateOf<List<String>>(emptyList())
    internal val showPasteChip = mutableStateOf(false)

    // --- Undo / redo (see Undo.kt) ---
    internal val undoHistory = UndoHistory()
    internal val canUndo = mutableStateOf(false)
    /** A terminal app (see isTerminal): plain keyboard, but the clipboard is allowed. */
    internal val terminalField = mutableStateOf(false)
    /** No clipboard in password fields (terminals excepted). */
    internal val clipBlocked get() = sensitiveField.value && !terminalField.value
    internal var hasSelection = false
    internal var rawConnection: android.view.inputmethod.InputConnection? = null
    internal var recording: RecordingConnection? = null

    /**
     * Every edit goes through this connection to the app. We hand out a wrapper that
     * notes each change, so ↶ can reverse it (memory only; nothing in password fields).
     */
    override fun getCurrentInputConnection(): android.view.inputmethod.InputConnection? {
        val target = super.getCurrentInputConnection() ?: return null
        if (target !== rawConnection) {
            rawConnection = target
            recording = RecordingConnection(target, undoHistory, { !sensitiveField.value }, { hasSelection })
            undoHistory.clear()
        }
        return recording
    }

    internal fun undoRedo(redo: Boolean) {
        if (composing.value.isNotEmpty()) { setComposing(""); return }
        val ic = rawConnection ?: return
        markOwnEdit()
        val done = if (redo) undoHistory.redo(ic) else undoHistory.undo(ic)
        currentWord = ""; wordTouches.clear(); glided = null; lastAutocorrect = null
        correctionNotice.value = null; autoSpaced = false
        suggestions.value = emptyList()
        if (!done) toast(if (redo) "Nothing to redo" else "Nothing to undo")
    }

    // --- Glide typing (see Glide.kt) ---
    internal val glide = GlideState()
    /** The word just entered by gliding (tap a suggestion to swap it; ⌫ removes it). */
    internal var glided: String? = null
    internal var glideAlternatives: List<String> = emptyList()
    internal val glideOn = mutableStateOf(true)
    internal val oneHanded = mutableStateOf(0)

    internal var shiftAtLastLetter = Shift.Off

    /** A glide began on a letter key, which typed its letter on touch-down: take it back. */
    internal fun glideStarted() {
        val ic = currentInputConnection ?: return
        markOwnEdit()
        shift.value = shiftAtLastLetter  // typing that letter may have turned shift off
        ic.deleteSurroundingText(1, 0)
        if (currentWord.isNotEmpty()) currentWord = currentWord.dropLast(1)
        if (wordTouches.isNotEmpty()) wordTouches.removeAt(wordTouches.lastIndex)
    }

    internal fun glideEnded(path: List<androidx.compose.ui.geometry.Offset>, keys: Map<Char, androidx.compose.ui.geometry.Rect>) {
        val dict = english ?: return
        val boost = nextWords?.boosts(sentence.toList()) ?: emptyMap()
        lifecycleScope.launch {
            val words = withContext(Dispatchers.Default) { GlideDecoder.decode(path, keys, dict, boost) }
            if (words.isEmpty()) { toast("No word matched that glide"); return@launch }
            commitGlide(words)
        }
    }

    internal fun glideCase(w: String) = when (shift.value) {
        Shift.Lock -> w.uppercase()
        Shift.On -> w.replaceFirstChar { it.uppercase() }
        Shift.Off -> w
    }

    internal fun commitGlide(words: List<String>) {
        val ic = currentInputConnection ?: return
        clearAssist()
        showPasteChip.value = false
        // Letters tapped just before the glide finish as their own word.
        if (currentWord.isNotEmpty()) finishWord(ic, autocorrectOnly = false)
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val space = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"“") " " else ""
        val cased = words.map(::glideCase)
        val word = cased[0]
        markOwnEdit()
        ic.commitText(space + word, 1)
        glided = word
        glideAlternatives = cased.drop(1).take(3)
        nextWords?.learn(sentence, word)
        addToSentence(word)
        if (shift.value == Shift.On) shift.value = Shift.Off
        autoSpaced = false
        lastAutocorrect = null
        toolsForced.value = false
        suggestions.value = glideAlternatives
    }

    /** Hold space: the system's keyboard picker. */
    internal fun spaceHeld() {
        undoSpace()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    }

    internal fun setOneHanded(side: Int) {
        oneHanded.value = side
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).oneHanded = side }
    }

    // --- English suggestions / autocorrect ---
    internal var english: EnglishDictionary? = null
    internal var nextWords: NextWords? = null
    /** The word being typed (tracked here, so we don't ask the app on every key). */
    internal var currentWord = ""
    /** Where each letter of [currentWord] was tapped (for touch-aware autocorrect). */
    internal val wordTouches = ArrayList<Touch?>()

    /** Tap positions for the current word, or empty if they're out of step with it. */
    internal fun touchesForWord(): List<Touch?> =
        if (wordTouches.size == currentWord.length) wordTouches.toList() else emptyList()
    /** The last few finished words of this sentence, for next-word suggestions and learning. */
    internal val sentence = ArrayList<String>()

    internal fun addToSentence(word: String) {
        sentence += word
        if (sentence.size > 2) sentence.removeAt(0)  // only the last two matter
    }
    /** Set right after an autocorrect, so an immediate backspace can undo it. */
    internal var lastAutocorrect: Pair<String, String>? = null
    internal val suggestions = mutableStateOf<List<String>>(emptyList())
    /** User tapped ‹ to see the tools instead of suggestions; cleared on the next letter. */
    internal val toolsForced = mutableStateOf(false)
    /** Shown briefly in the bar after an autocorrect: (what you typed, what it became). */
    internal val correctionNotice = mutableStateOf<Pair<String, String>?>(null)
    internal val hideCorrection = Runnable { correctionNotice.value = null }

    internal var speech: Speech? = null

    // --- "Type it yourself": after translating, tap 中 to type the Chinese in pinyin ---
    /** The translated sentences you're typing, or null when not in this mode. */
    internal var guidedTarget: List<String>? = null
    /** How many sentences you've typed correctly so far (cards show ✓). */
    internal val guidedDone = mutableStateOf(0)
    /** The part of the translation still to type (Chinese characters only). */
    internal var guidedRemaining = ""
    /** A new screenshot waiting to be offered; it's only copied if Paste is tapped. */
    internal var pendingScreenshot: android.net.Uri? = null
    internal var offeredScreenshot: android.net.Uri? = null
    /** Notices new photos while the keyboard is open (only if screenshots are enabled). */
    internal val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = checkRecentScreenshot()
    }
    internal var mediaObserverOn = false
    /** The newest copy, offered by the Paste button. */
    internal var latestClip: String? = null
    internal var lastSeenClipTime = 0L
    internal val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    internal val practiceState = mutableStateOf<PracticeState>(PracticeState.Idle)
    internal var strictTones = false

    /** Per-card breakdown and "Why?"; state maps so Compose redraws each card on change. */
    internal val detailsStates = mutableStateMapOf<Sentence, Load<SentenceDetails>>()
    internal val whyStates = mutableStateMapOf<Sentence, Load<String>>()
    /** Sentences saved (or being saved) during this translation, so the star fills in. */
    internal val savedStates = mutableStateMapOf<Sentence, Load<Unit>>()

    override fun onCreate() {
        super.onCreate()
        glide.onStart = ::glideStarted
        glide.onEnd = ::glideEnded
        undoHistory.onChange = { canUndo.value = undoHistory.canUndo }
        // Load saved emoji/clipboard lists in the background (encrypted prefs are slow to open).
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { Settings(this@PinyinKeysService) }
            recentEmoji.value = s.recentEmoji
            pinnedClips.value = s.pinnedClips
            tone.value = s.tone
            clipHistory.value = s.clipHistory
        }
        // Load dictionaries and learned data now, so the first words already get suggestions.
        loadLearning()
        lifecycleScope.launch(Dispatchers.IO) { PinyinDictionary.get(this@PinyinKeysService) }
        // Get told about each new copy (Android allows this for the keyboard in use).
        getSystemService(android.content.ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    /** Called when the keyboard's view is first needed. Returns what's drawn on screen. */
    override fun onCreateInputView(): View {
        // The owners must be on the window's root (decor) view, because that's where
        // Compose looks for them when it walks up the view tree.
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        return ComposeView(this).apply {
            setContent {
                KeyboardUi(
                    sensitive = sensitiveField.value,
                    clipboardAllowed = !clipBlocked,
                    assist = assistState.value,
                    mode = mode.value,
                    onMode = this::setMode,
                    practice = practiceState.value,
                    onStartPractice = this::startPractice,
                    onHint = this::showHint,
                    onTranslate = this::translateField,
                    onInsertChinese = this::insertChinese,
                    detailsStates = detailsStates,
                    onExpand = this::loadDetails,
                    whyStates = whyStates,
                    onWhy = this::explain,
                    savedStates = savedStates,
                    onSave = this::saveSentence,
                    onText = ::typeText,
                    onBackspace = ::backspace,
                    onEnter = this::enter,
                    onSwitchKeyboard = { switchToPreviousInputMethod() },
                    shift = shift.value,
                    onShift = this::cycleShift,
                    // Never Chinese/suggestions in password fields: plain keyboard only.
                    chinese = chinese.value && !sensitiveField.value,
                    onToggleChinese = this::toggleChinese,
                    composing = composing.value,
                    candidates = candidates.value,
                    onCandidate = this::pickCandidate,
                    recentEmoji = recentEmoji.value,
                    onEmoji = this::typeEmoji,
                    readClipboard = this::readClipboard,
                    pinnedClips = pinnedClips.value,
                    clipHistory = clipHistory.value,
                    onPaste = this::pasteEntry,
                    onPin = { t -> updatePinned(listOf(t) + pinnedClips.value) },
                    onUnpin = { t -> updatePinned(pinnedClips.value - t) },
                    onDeleteClip = { t -> updateHistory(clipHistory.value - t) },
                    onClearClipHistory = { updateHistory(emptyList()) },
                    showPasteChip = showPasteChip.value,
                    pastePreview = pastePreview(),
                    suggestions = suggestions.value,
                    wordHint = wordHint.value?.takeIf { it.first === suggestions.value }?.second,
                    onHintEmoji = ::pickHintEmoji,
                    onHintChinese = ::replaceWordWithChinese,
                    onSuggestion = ::pickSuggestion,
                    onForgetSuggestion = ::forgetWord,
                    toolsForced = toolsForced.value,
                    onShowTools = { toolsForced.value = true },
                    correction = correctionNotice.value,
                    onUndoCorrection = ::undoAutocorrect,
                    onSpeak = ::speak,
                    guidedDone = guidedDone.value,
                    onPasteChip = this::pasteFromChip,
                    onEdit = this::edit,
                    onOpenSettings = this::openSettings,
                    numberPad = numberPad.value,
                    enterLabel = enterLabel.value,
                    onCursorMove = this::moveCursor,
                    onReplaceLast = this::replaceLastChar,
                    onUndoSpace = this::undoSpace,
                    showNumberRow = numberRow.value,
                    keyScale = keyScale.value,
                    toneColors = toneColors.value,
                    feedback = feedback.value,
                    tone = tone.value,
                    onTogglePolite = this::togglePolite,
                    onExplain = this::explainCopied,
                    theme = theme.value,
                    glide = if (glideOn.value) glide else null,
                    canUndo = canUndo.value,
                    onUndo = { undoRedo(redo = false) },
                    onRedo = { undoRedo(redo = true) },
                    onSpaceHold = ::spaceHeld,
                    oneHanded = oneHanded.value,
                    onOneHanded = ::setOneHanded,
                )
            }
        }
    }

    /** Called each time the keyboard appears for a (possibly new) text field. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sensitiveField.value = info != null && isSensitive(info)
        terminalField.value = info != null && isTerminal(info)
        numberPad.value = info?.let { NumberPadKind.forInputType(it.inputType) }
        enterLabel.value = info?.let { enterLabelFor(it) }
        // New text field: forget the previous translation.
        if (!restarting) {
            messageNewWords.clear()
            undoHistory.clear()
            glided = null
            translateJob?.cancel()
            assistState.value = AssistState.Idle
            detailsStates.clear(); whyStates.clear(); savedStates.clear()
            practiceState.value = PracticeState.Idle
            endGuided()
        }
        setComposing("")
        shift.value = Shift.Off
        updateAutoCaps()
        checkRecentCopy()
        checkRecentScreenshot()
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { Settings(this@PinyinKeysService) }
            theme.value = s.theme
            numberRow.value = s.numberRow
            keyScale.value = s.keyScale
            toneColors.value = s.toneColors
            glideOn.value = s.glideTyping
            oneHanded.value = s.oneHanded
            feedback.value = KeyFeedback(s.vibrateKeys, s.soundKeys)
        }
        warmUpConnection()
        resetWord()
        loadLearning()
        if (!mediaObserverOn && Settings(this).suggestScreenshots && Screenshots.hasPermission(this)) {
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
            )
            mediaObserverOn = true
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        saveLearning()
        if (mediaObserverOn) {
            contentResolver.unregisterContentObserver(mediaObserver)
            mediaObserverOn = false
        }
        // Keyboard hidden: keep the typed letters as they are (they're already in the field).
        currentInputConnection?.finishComposingText()
        setComposing("")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    internal fun speak(text: String) {
        val s = speech ?: Speech(this).also { speech = it }
        if (!s.speak(text)) toast("No Chinese voice installed. Add one in Android's text-to-speech settings.")
    }

    /** Saves learned word pairs and touch habits (on the phone, encrypted). */
    internal fun saveLearning() {
        wordsSinceSave = 0
        nextWords?.let { nw -> lifecycleScope.launch(Dispatchers.IO) { nw.save() } }
        if (touchBiasDirty) {
            touchBiasDirty = false
            val lines = english?.touchBias?.map { (c, t) -> "$c\t${t.dx}\t${t.dy}" } ?: emptyList()
            lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).touchBias = lines }
        }
    }

    // Android can stop the keyboard's process at any time, so also save every 30 words
    // instead of only when the keyboard is hidden.
    internal var wordsSinceSave = 0

    override fun onDestroy() {
        speech?.shutdown()
        getSystemService(android.content.ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    // --- Key actions. `currentInputConnection` is our link to the app's text field. ---

    /**
     * Any edit makes the shown translation stale (Insert Chinese would insert the wrong
     * text), so typing clears it. Also cancels a translation still in progress.
     */
    internal fun clearAssist() {
        endGuided()
        if (assistState.value == AssistState.Idle) return
        translateJob?.cancel()
        assistState.value = AssistState.Idle
        detailsStates.clear(); whyStates.clear(); savedStates.clear()
    }

    /** True while held backspace should keep sending Delete key presses (see backspace()). */
    internal var deleteWithKeyEvent = false

    /**
     * Terminal-style fields (input type "none", e.g. SSH apps) take key presses, not text.
     * There we send each character as a key press and never read anything back.
     */
    internal val rawKeyField: Boolean
        get() = currentInputEditorInfo?.inputType == InputType.TYPE_NULL

    internal val keyMap by lazy { android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) }

    internal fun sendRaw(text: String) {
        val ic = currentInputConnection ?: return
        // The phone's built-in key map turns characters into key presses (with shift where
        // needed); characters it can't (e.g. emoji) are sent as plain text.
        val events = keyMap.getEvents(text.toCharArray())
        if (events != null) events.forEach { ic.sendKeyEvent(it) } else ic.commitText(text, 1)
        if (shift.value == Shift.On) shift.value = Shift.Off
    }

    internal fun typeText(text: String) {
        showPasteChip.value = false
        if (rawKeyField) { sendRaw(text); return }
        if (practiceTyped(text)) return
        val ic = currentInputConnection ?: return
        val isChinese = chinese.value && !sensitiveField.value
        // Typing English makes a shown translation stale, so clear it. In Chinese mode keep
        // it, so you can type the characters yourself while reading the cards.
        if (!isChinese) clearAssist()

        if (isChinese) {
            val c = text.singleOrNull()
            // Lowercase letters build pinyin. (Capitals go straight in, e.g. for names.)
            if (c != null && c in 'a'..'z') {
                setComposing(composing.value + c)
                return
            }
            // Anything else: first commit the best suggestion for what's been typed.
            if (composing.value.isNotEmpty()) {
                if (text == " ") { commitFirstCandidate(); return }
                commitFirstCandidate()
            }
            ic.commitText(CHINESE_PUNCTUATION[text] ?: text, 1)
            refreshGuided()
            return
        }

        val c = text.singleOrNull()
        // Right after a glided word: a letter starts a new word, so add the space first
        // (punctuation and space just follow the word).
        if (glided != null) {
            glided = null
            if (c != null && c.isLetterOrDigit()) ic.commitText(" ", 1)
        }
        // After a picked suggestion (which added a space), punctuation replaces that space:
        // "word ." becomes "word. "
        val wasAutoSpaced = autoSpaced
        autoSpaced = false
        if (wasAutoSpaced && c != null && c in ".,?!:;") {
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$c ", 1)
            if (c in ".?!") sentence.clear()
            suggestions.value = if (suggestionsAllowed()) nextWords?.predict(sentence) ?: emptyList() else emptyList()
            capsAfter("x$c ")
            return
        }
        lastAutocorrect = null
        // A digit right after letters stays part of the word, so a slip onto the number row
        // ("h5llo") can still be corrected instead of splitting the word.
        if (c != null && (c.isLetter() || (c == '\'' && currentWord.isNotEmpty()) ||
                (c.isDigit() && currentWord.any { it.isLetter() } && currentWord.count { it.isDigit() } == 0))
        ) {
            // A letter: part of the current word. Update suggestions for it.
            // Invisible key resizing: a tap near the edge of a key may become the neighbour
            // that makes a far more likely word (see EnglishDictionary.resolveTap).
            var touch: Touch? = Touch(TouchTracker.dx, TouchTracker.dy)
            var letter = c
            val dict = english
            if (dict != null && c.isLetter() && suggestionsAllowed() && Settings(this).autocorrect) {
                val r = dict.resolveTap(currentWord.lowercase(), c.lowercaseChar(), touch!!)
                if (r != c.lowercaseChar()) {
                    letter = if (c.isUpperCase()) r.uppercaseChar() else r
                    touch = null  // the offset was measured from the other key
                }
            }
            ic.commitText(letter.toString(), 1)
            if (wordTouches.size != currentWord.length) {
                wordTouches.clear(); repeat(currentWord.length) { wordTouches += null }
            }
            wordTouches += touch
            if (erasedWord == null) eraseStart = null
            currentWord += letter
            correctionNotice.value = null
            shiftAtLastLetter = shift.value
            if (shift.value == Shift.On) shift.value = Shift.Off
            toolsForced.value = false
            updateSuggestions()
            return
        }
        suggestJob?.cancel()
        // Anything else ends the word: maybe autocorrect it, then learn it.
        finishWord(ic, autocorrectOnly = c != null && (c == ' ' || c in ".,?!"))
        if (c != ' ') prevNonWord = null  // only "word space word" can be joined
        if (c != null && c in ".?!") sentence.clear()  // new sentence
        suggestions.value = if (c == ' ' && suggestionsAllowed()) predictions() else emptyList()

        // English: two spaces after a word become ". " (like other keyboards).
        if (text == " ") {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                capsAfter(". ")
                return
            }
        }
        // The last few characters before the cursor (a tiny read) decide capitals below.
        val prev = if (c == ' ') ic.getTextBeforeCursor(4, 0)?.toString() else null
        // A space typed right after a word: punctuation next replaces it ("hi ," -> "hi, ").
        val afterWord = c == ' ' && (prev?.lastOrNull()?.isLetterOrDigit() == true)
        ic.commitText(text, 1)
        if (afterWord) autoSpaced = true
        if (shift.value == Shift.On) shift.value = Shift.Off
        when {
            // Space: a capital only if it follows . ? ! (or starts the text). Decided from
            // the text itself, so an earlier "3.5" or "lol.ok" can't cause a stray capital.
            c == ' ' -> if (prev != null) capsAfter(prev + " ")
            text.length != 1 || !text[0].isLetterOrDigit() -> updateAutoCaps()
        }
    }

    /**
     * Turns on a capital for the next letter if [before] (the text just before the cursor)
     * ends a sentence ("Hi. ") or is the start of the text; otherwise off. Caps lock stays.
     */
    internal fun capsAfter(before: String) {
        if (shift.value == Shift.Lock || chinese.value || !capsField) return
        val t = before.trimEnd(' ')
        shift.value = if (t.isEmpty() || t.last() in ".?!\n") Shift.On else Shift.Off
    }

    internal fun backspace() {
        val p = practiceState.value
        if (mode.value == Mode.Practice && p is PracticeState.Active && !p.done) {
            // Only edits the pinyin being typed; committed characters stay put.
            practiceState.value = p.copy(buffer = p.buffer.dropLast(1))
            return
        }
        if (composing.value.isNotEmpty()) {
            setComposing(composing.value.dropLast(1))
            return
        }
        if (!chinese.value) clearAssist()
        val ic = currentInputConnection ?: return
        // Terminals and similar apps: a real Delete key press.
        if (rawKeyField) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        markOwnEdit()  // our own deletion moves the cursor back; don't treat it as a jump
        if (guidedTarget != null) mainHandler.post { refreshGuided() }
        if (undoAutocorrect()) return
        glided?.let { g ->
            // Backspace right after a glide removes the whole glided word (like other keyboards).
            glided = null
            if (ic.getTextBeforeCursor(g.length, 0)?.toString() == g) {
                ic.deleteSurroundingText(g.length, 0)
                if (sentence.isNotEmpty()) sentence.removeAt(sentence.lastIndex)
                suggestions.value = emptyList()
                return
            }
        }
        if (currentWord.isNotEmpty()) {
            // Remember the word you started erasing; if you retype it differently, that's a typo fix.
            if (eraseStart == null) eraseStart = currentWord
            currentWord = currentWord.dropLast(1)
            if (wordTouches.isNotEmpty()) wordTouches.removeAt(wordTouches.lastIndex)
            if (currentWord.isEmpty()) erasedWord = eraseStart
        }
        if (currentWord.isEmpty()) suggestions.value = emptyList() else updateSuggestions()
        val now = android.os.SystemClock.uptimeMillis()
        val repeating = now - lastBackspaceAt < 150
        lastBackspaceAt = now
        if (!repeating) backspaceHoldStart = now
        if (repeating) {
            // Held backspace: just delete. Each question to the other app (selection,
            // capitals) is a round trip, and skipping them keeps the repeat fast.
            // After 1.5 s of holding, delete whole words instead of letters.
            if (now - backspaceHoldStart > 1500) {
                val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
                val trimmed = before.trimEnd()
                val wordStart = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
                ic.deleteSurroundingText(maxOf(1, before.length - wordStart), 0)
            } else if (deleteWithKeyEvent) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            else ic.deleteSurroundingText(1, 0)
            pendingCapsCheck = true
            mainHandler.removeCallbacks(capsAfterRepeat)
            mainHandler.postDelayed(capsAfterRepeat, 200)
            return
        }
        // If text is selected, delete the selection; otherwise delete one character.
        val selected = ic.getSelectedText(0)
        // Some apps (terminals, some games) don't show their text to keyboards, so there's
        // nothing "before the cursor" to delete: send a Delete key press instead.
        deleteWithKeyEvent = selected.isNullOrEmpty() && ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else if (deleteWithKeyEvent) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingText(1, 0)
        updateAutoCaps()
        // Backspaced into the end of an earlier word: pick it back up.
        if (currentWord.isEmpty()) recoverWord()
    }

    internal var lastBackspaceAt = 0L
    internal var backspaceHoldStart = 0L

    /**
     * Puts back what was typed before the last autocorrect (backspace right after it, or
     * tapping the "teh → the" notice), and stops correcting that word. True if undone.
     */
    internal fun undoAutocorrect(): Boolean {
        val (original, corrected) = lastAutocorrect ?: correctionNotice.value ?: return false
        lastAutocorrect = null
        correctionNotice.value = null
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(corrected.length + 1, 0)?.toString()
        if (before != "$corrected ") return false
        markOwnEdit()
        ic.deleteSurroundingText(corrected.length + 1, 0)
        ic.commitText(original, 1)
        keepWord(original)  // you undid it, so don't correct this word again
        // A wrong learned fix or pick: forget it.
        fixBook.remove(original.lowercase())
        pickBook.remove(original.lowercase())
        saveFixes()
        currentWord = original
        updateSuggestions()
        return true
    }

    // --- Suggestions and autocorrect (English) ---

    /** No suggestions, autocorrect or learning in passwords, emails, web addresses or numbers. */
    internal fun suggestionsAllowed(): Boolean {
        if (sensitiveField.value || chinese.value) return false
        val type = currentInputEditorInfo?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val v = type and InputType.TYPE_MASK_VARIATION
        return v != InputType.TYPE_TEXT_VARIATION_URI && v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    internal var suggestJob: Job? = null

    /**
     * Works out suggestions for the current word on a background thread, so typing never
     * waits for it; a newer keystroke cancels an older, unfinished lookup.
     */
    internal fun updateSuggestions() {
        suggestJob?.cancel()
        val dict = english
        val word = currentWord
        if (!suggestionsAllowed() || dict == null || word.isEmpty()) { suggestions.value = emptyList(); return }
        // Boosts from the sentence so far and your own word habits (copied for the background thread).
        val boosts = nextWords?.boosts(sentence.toList()) ?: emptyMap()
        val touches = touchesForWord()
        val autoOn = Settings(this).autocorrect
        val lm = contextModel()
        suggestJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) {
                val s = dict.suggest(word, boost = boosts, touches = touches, lm = lm)
                // Like Gboard: the middle slot (first in the list) is exactly what space will
                // type, marked when it will replace your word; what you typed is on the left
                // in quotes (tap it to keep it), and another option on the right.
                val auto = if (autoOn) dict.autocorrect(word, touches, boosts, lm = lm) else null
                if (auto != null && auto != word) {
                    val rest = s.filter { !it.equals(auto, ignoreCase = true) && !it.equals(word, ignoreCase = true) }
                    listOfNotNull(AUTO + auto, LITERAL + word, rest.getOrNull(0))
                } else if (word.length >= 2 && !dict.isWord(word)) {
                    val rest = s.filter { !it.equals(word, ignoreCase = true) }
                    listOfNotNull(rest.getOrNull(0), LITERAL + word, rest.getOrNull(1))
                } else s
            }
            // Chinese and an emoji for the word you mean (the middle suggestion).
            val target = (list.firstOrNull() ?: word).removePrefix(AUTO).removePrefix(LITERAL)
            val hint = withContext(Dispatchers.Default) {
                val zh = enZh?.lookup(target)
                val emoji = EmojiSearch.loaded?.forWord(target)
                if (zh == null && emoji == null) null else WordHint(zh?.first, zh?.second, emoji)
            }
            if (currentWord == word) {
                suggestions.value = list
                // Tied to this exact list: it disappears as soon as the suggestions change.
                wordHint.value = hint?.let { list to it }
            }
        }
    }

    /** English -> Chinese words for the suggestion bar (see EnZh.kt). */
    @Volatile internal var enZh: EnZh? = null
    /** The hint for the current suggestions (shown only while those suggestions are). */
    internal val wordHint = mutableStateOf<Pair<List<String>, WordHint>?>(null)

    /** Emoji chip tapped: finish the word, then add the emoji after it. */
    internal fun pickHintEmoji(e: String) {
        typeText(" ")
        typeEmoji(e)
    }

    /** Chinese chip held: type the Chinese instead of the English word. */
    internal fun replaceWordWithChinese(hanzi: String) {
        val ic = currentInputConnection ?: return
        if (currentWord.isEmpty()) return
        markOwnEdit()
        ic.deleteSurroundingText(currentWord.length, 0)
        ic.commitText(hanzi, 1)
        currentWord = ""; wordTouches.clear()
        suggestions.value = emptyList()
    }

    /** Word-sequence model (see NgramModel.kt); null until loaded or if it can't be read. */
    @Volatile internal var ngrams: NgramModel? = null

    /** "How well does this word fit after the last two words?" for autocorrect, or null. */
    internal fun contextModel(): ((String) -> Double)? {
        val m = ngrams ?: return null
        val before = sentence.toList()
        return { w -> m.contextBoost(w, before) }
    }

    internal var loadedLearnVersion = -1
    internal var learningLoading = false

    /**
     * Loads the dictionaries and everything learned from you, in the background. Runs at
     * start-up, and again when the app's "learned" screen changed something.
     */
    internal fun loadLearning() {
        if (learningLoading) return
        learningLoading = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val st = Settings(this@PinyinKeysService)
                val version = st.learnVersion
                if (english != null && nextWords != null && version == loadedLearnVersion) return@withContext
                val dict = EnglishDictionary.get(this@PinyinKeysService)
                dict.userWords = st.userWords.toSet()
                // Learned fixes and picks. Lists saved by older versions are cleaned up once:
                // only believable fixes that autocorrect doesn't already make are kept.
                val today = LearnedFixes.today()
                fun worthKeeping(typo: String, fix: String) =
                    dict.isPlausibleFix(typo, fix) && !dict.fixesOnItsOwn(typo, fix)
                val fixes = LearnedFixes.fromLines(st.learnedFixes, today, ::worthKeeping)
                val picks = LearnedFixes.fromLines(st.preferredPicks, today) { t, f -> dict.isPlausibleFix(t, f) }
                val faded = fixes.fade(today) or picks.fade(today)
                if (faded || fixes.lines() != st.learnedFixes) st.learnedFixes = fixes.lines()
                if (faded || picks.lines() != st.preferredPicks) st.preferredPicks = picks.lines()
                fixBook = fixes; pickBook = picks
                dict.learnedFixes = fixes.active()
                dict.preferred = picks.active()
                capCounts.clear()
                st.capCounts.forEach { l -> l.split('\t').takeIf { it.size == 2 }?.let { capCounts[it[0]] = it[1].toIntOrNull() ?: 0 } }
                dict.capWords = capCounts.filterValues { it >= CAP_AFTER }.keys
                dict.touchBias = st.touchBias.mapNotNull { l ->
                    val p = l.split('\t')
                    if (p.size == 3 && p[0].length == 1) p[0][0] to Touch(p[1].toFloat(), p[2].toFloat()) else null
                }.toMap()
                val nw = nextWords ?: NextWords(this@PinyinKeysService)
                if (nextWords == null) nw.load() else nw.loadLearned()
                english = dict
                ngrams = NgramModel.get(this@PinyinKeysService)
                enZh = runCatching { EnZh.get(this@PinyinKeysService) }.getOrNull()
                runCatching { EmojiSearch.get(this@PinyinKeysService) }
                nextWords = nw
                loadedLearnVersion = version
            }
            learningLoading = false
        }
    }

    /**
     * 1. Personal touch model: after a word you didn't correct, nudge each letter's average
     * tap offset towards where you actually tapped it (a slow moving average).
     */
    internal fun learnTouches(word: String, touches: List<Touch?>) {
        val dict = english ?: return
        if (touches.size != word.length) return
        val bias = dict.touchBias.toMutableMap()
        word.lowercase().forEachIndexed { i, c ->
            val t = touches[i] ?: return@forEachIndexed
            val old = bias[c] ?: Touch(0f, 0f)
            bias[c] = Touch(old.dx * 0.95f + t.dx * 0.05f, old.dy * 0.95f + t.dy * 0.05f)
        }
        dict.touchBias = bias
        touchBiasDirty = true
    }
    internal var touchBiasDirty = false

    /** Remember (or with null, forget) that you prefer [picked] when you type [typed]. */
    internal fun setPreferred(typed: String, picked: String?) {
        val d = english ?: return
        if (picked == null) { pickBook.remove(typed); saveFixes(); return }
        if (!d.isPlausibleFix(typed, picked)) return
        pickBook.record(typed, picked, LearnedFixes.today())
        saveFixes()
    }

    /** Words in the current message that aren't in the dictionary and weren't corrected. */
    internal val messageNewWords = LinkedHashSet<String>()

    /** 9. Long-press a suggestion: forget everything learned about that word. */
    internal fun forgetWord(word: String) {
        val w = word.removePrefix(LITERAL).removePrefix(AUTO).lowercase()
        english?.let { d -> d.userWords = d.userWords - w }
        fixBook.forget(w); pickBook.forget(w)
        saveFixes()
        nextWords?.forget(w)
        val dict = english
        lifecycleScope.launch(Dispatchers.IO) {
            val st = Settings(this@PinyinKeysService)
            if (dict != null) {
                st.userWords = dict.userWords.toList()
            }
            nextWords?.save()
        }
        suggestions.value = suggestions.value.filter { !it.removePrefix(LITERAL).equals(w, ignoreCase = true) }
        toast("Forgot \"$w\"")
    }

    /** Next-word guesses, with one of your frequent two-word phrases in the third slot. */
    internal fun predictions(): List<String> {
        val nw = nextWords ?: return emptyList()
        val words = nw.predict(sentence)
        val phrase = nw.phrase(sentence) ?: return words
        return words.take(2) + phrase
    }

    /** Remember that you fix the typo [typo] to [fix]. Kept encrypted on the phone. */
    internal fun learnFix(typo: String, fix: String) {
        val dict = english ?: return
        // Only believable slips are learned, and only once you've made the same fix twice
        // (see LearnedFixes); fixes autocorrect already makes aren't stored at all.
        if (!dict.isPlausibleFix(typo, fix) || dict.fixesOnItsOwn(typo, fix)) return
        fixBook.record(typo, fix, LearnedFixes.today())
        saveFixes()
    }

    // Learned fixes and picks (see LearnedFixes.kt); loaded in loadLearning().
    @Volatile internal var fixBook = LearnedFixes()
    @Volatile internal var pickBook = LearnedFixes()

    /** Pushes the books to autocorrect and saves them (encrypted, on the phone). */
    internal fun saveFixes() {
        english?.let { d -> d.learnedFixes = fixBook.active(); d.preferred = pickBook.active() }
        val fixes = fixBook.lines()
        val picks = pickBook.lines()
        lifecycleScope.launch(Dispatchers.IO) {
            val st = Settings(this@PinyinKeysService)
            st.learnedFixes = fixes
            st.preferredPicks = picks
        }
    }

    /** Remember [word] as a real word: suggested, and never autocorrected. */
    internal fun keepWord(word: String) {
        val dict = english ?: return
        val lower = word.lowercase()
        if (lower in dict.userWords) return
        dict.userWords = dict.userWords + lower
        val all = dict.userWords.toList().takeLast(2000)
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).userWords = all }
    }

    internal fun resetWord() {
        currentWord = ""
        sentence.clear()
        lastAutocorrect = null
        suggestions.value = emptyList()
        toolsForced.value = false
    }

    /**
     * The current word is finished (space or punctuation). With [autocorrectOnly] and
     * autocorrect on, replace an obvious typo; then remember the word for predictions.
     */
    internal fun finishWord(ic: android.view.inputmethod.InputConnection, autocorrectOnly: Boolean) {
        val word = currentWord
        val touches = touchesForWord()
        wordTouches.clear()
        currentWord = ""
        val prev = prevNonWord
        prevNonWord = null
        if (word.isEmpty() || !suggestionsAllowed()) return
        var final = word
        val dict = english
        // A space typed inside a word ("th e", "beca use"): the last word wasn't a word,
        // and joined with this one it is. Put them back together.
        if (autocorrectOnly && prev != null && dict != null && Settings(this).autocorrect) {
            val joined = prev + word
            val expect = "$prev $word"
            if (dict.isWord(joined) && (!dict.isWord(word) || word.length <= 2) &&
                ic.getTextBeforeCursor(expect.length, 0)?.toString() == expect
            ) {
                markOwnEdit()
                ic.deleteSurroundingText(expect.length, 0)
                ic.commitText(joined, 1)
                if (sentence.isNotEmpty()) sentence.removeAt(sentence.lastIndex)
                lastAutocorrect = expect to joined
                correctionNotice.value = expect to joined
                mainHandler.removeCallbacks(hideCorrection)
                mainHandler.postDelayed(hideCorrection, 2500)
                nextWords?.learn(sentence, joined)
                addToSentence(joined)
                return
            }
        }
        if (autocorrectOnly && Settings(this).autocorrect) {
            val fix = english?.autocorrect(word, touches, nextWords?.boosts(sentence.toList()) ?: emptyMap(), lm = contextModel())
            if (fix != null && fix != word) {
                markOwnEdit()
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(fix, 1)
                lastAutocorrect = word to fix
                correctionNotice.value = word to fix
                mainHandler.removeCallbacks(hideCorrection)
                mainHandler.postDelayed(hideCorrection, 2500)
                final = fix
            }
        }
        // 5. You erased a non-word and typed a real word in its place: learn that typo.
        val erased = erasedWord
        erasedWord = null
        eraseStart = null
        if (erased != null && english?.isWord(erased) == false && english?.isWord(final) == true &&
            editDistance(erased.lowercase(), final.lowercase()) in 1..2
        ) learnFix(erased.lowercase(), final.lowercase())
        if (final == word) learnTouches(word, touches)
        // A split ("ofthe" -> "of the") is learned word by word.
        for (part in final.split(' ')) {
            nextWords?.learn(sentence, part)
            addToSentence(part)
        }
        if (++wordsSinceSave >= 30) saveLearning()
        if (' ' in final) return
        learnCapital(final, startOfSentence = sentence.size <= 1)
        // A word you've typed a few times that isn't in the dictionary (slang, names) is
        // yours: stop "correcting" it. (3 times, so a habitual typo isn't locked in.)
        if (english?.isWord(final) == false && (nextWords?.count(final) ?: 0) >= 3) keepWord(final)
        if (english?.isWord(final) == false && final == word && final.all { it.isLetter() || it == '\'' }) {
            messageNewWords += final
            prevNonWord = final  // may be joined with the next word (see above)
        }
    }

    // Learned capitals: how often each word was written capitalised mid-sentence.
    internal val capCounts = HashMap<String, Int>()

    /**
     * Names and other words you capitalise in the middle of a sentence ("I met Lin"):
     * after [CAP_AFTER] times they're capitalised for you. Writing it in lowercase
     * mid-sentence (and leaving it) counts against it.
     */
    internal fun learnCapital(word: String, startOfSentence: Boolean) {
        val dict = english ?: return
        if (startOfSentence || word.length < 2 || !word.all { it.isLetter() || it == '\'' }) return
        val lower = word.lowercase()
        if (lower == "i" || lower.startsWith("i'")) return
        val capitalised = word[0].isUpperCase() && word.drop(1) == word.drop(1).lowercase()
        if (!capitalised && word != lower) return  // ALL CAPS etc.: no signal
        val before = capCounts[lower] ?: 0
        val now = if (capitalised) before + 1 else maxOf(0, before - 2)
        if (now == before) return
        if (now == 0) capCounts.remove(lower) else capCounts[lower] = now
        if (capCounts.size > 2000) capCounts.entries.sortedBy { it.value }.take(200).forEach { capCounts.remove(it.key) }
        dict.capWords = capCounts.filterValues { it >= CAP_AFTER }.keys
        val lines = capCounts.map { "${it.key}\t${it.value}" }
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).capCounts = lines }
    }

    /** The last finished word, if it wasn't a word and was left as typed. */
    internal var prevNonWord: String? = null

    /** Does this text box want automatic capitals? (decided once per box) */
    internal val capsField: Boolean
        get() = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0

    /** True right after a picked suggestion added a space (see smart punctuation). */
    internal var autoSpaced = false

    /** The word you started erasing with backspace, and the one fully erased (see learning). */
    internal var eraseStart: String? = null
    internal var erasedWord: String? = null

    /** Number of single-letter edits between two words (small words only). */
    internal fun editDistance(a: String, b: String) = TextUtil.editDistance(a, b)

    /** When this keyboard last changed the text in a way that moves the cursor a lot. */
    internal var lastOwnEditAt = 0L
    internal fun markOwnEdit() { lastOwnEditAt = android.os.SystemClock.uptimeMillis() }

    internal fun pickSuggestion(pickedRaw: String) {
        val picked = pickedRaw.removePrefix(AUTO)
        val ic = currentInputConnection ?: return
        markOwnEdit()
        val g = glided
        if (g != null && picked in glideAlternatives) {
            // Swap the glided word for the one you meant.
            ic.deleteSurroundingText(g.length, 0)
            ic.commitText(picked, 1)
            if (sentence.isNotEmpty()) sentence[sentence.lastIndex] = picked
            glided = picked
            glideAlternatives = glideAlternatives.map { if (it == picked) g else it }
            suggestions.value = glideAlternatives
            return
        }
        if (g != null) { glided = null; ic.commitText(" ", 1) }
        val word = if (picked.startsWith(LITERAL)) picked.removePrefix(LITERAL).also(::keepWord) else picked
        // You fixed a typo by picking a word: remember it, so next time it's fixed for you.
        if (!picked.startsWith(LITERAL) && currentWord.length >= 2 && !currentWord.equals(word, ignoreCase = true) &&
            english?.isWord(currentWord) == false && !word.lowercase().startsWith(currentWord.lowercase())
        ) learnFix(currentWord.lowercase(), word.lowercase())
        // You chose a different word than you typed (not just finishing it): show that word
        // first whenever you type this again.
        if (!picked.startsWith(LITERAL) && currentWord.length >= 2 && !word.contains(' ') &&
            !word.lowercase().startsWith(currentWord.lowercase())
        ) setPreferred(currentWord.lowercase(), word.lowercase())
        if (currentWord.isNotEmpty()) ic.deleteSurroundingText(currentWord.length, 0)
        ic.commitText("$word ", 1)
        autoSpaced = true
        currentWord = ""
        lastAutocorrect = null
        // A phrase ("my way") is learned word by word.
        for (part in word.split(' ').filter { it.isNotEmpty() }) {
            nextWords?.learn(sentence, part)
            addToSentence(part)
        }
        if (shift.value == Shift.On) shift.value = Shift.Off
        suggestions.value = predictions()
        capsAfter("x$word ")  // a picked word never starts a sentence (unless it ends with . ? !)
    }

    /** The cursor moved somewhere we didn't put it (tap in the text, etc.): start fresh. */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        hasSelection = newSelStart != newSelEnd
        // Our own edits (a picked suggestion, an autocorrect, a paste) move the cursor too;
        // the app reports that a moment later, so ignore moves right after we edited.
        if (android.os.SystemClock.uptimeMillis() - lastOwnEditAt < 400) return
        // Typing moves the cursor forward, and fast typing reports it in jumps. Only a move
        // backwards, a selection, or a big jump means you moved the cursor yourself.
        val delta = newSelStart - oldSelStart
        if (newSelStart != newSelEnd || delta < 0 || delta > 40) {
            // You moved the cursor: earlier changes can't be undone safely any more.
            undoHistory.clear()
            glided = null
            if (composing.value.isEmpty()) {
                currentWord = ""
                lastAutocorrect = null
                suggestions.value = emptyList()
                // Tapped back onto the end of a word: pick that word up again.
                if (newSelStart == newSelEnd) recoverWord()
            }
        }
    }

    /**
     * If the cursor sits right at the end of a word (and not in the middle of one), read
     * that word and the two before it back from the text box, so suggestions and
     * autocorrect continue on it. Reads a little text before the cursor; called only after
     * your own backspace or cursor move.
     */
    internal fun recoverWord() {
        if (!suggestionsAllowed() || chinese.value) return
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.firstOrNull()?.let { it.isLetter() || it == '\'' } == true) return  // mid-word
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: return
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty() || word.length > 30) return
        currentWord = word
        // Context: the last two words before it in the same sentence.
        val rest = before.dropLast(word.length)
        val clause = rest.substringAfterLast('.').substringAfterLast('?').substringAfterLast('!')
        sentence.clear()
        Regex("[A-Za-z']+").findAll(clause).map { it.value }.toList().takeLast(2).forEach { sentence += it }
        updateSuggestions()
    }

    /** Short preview for the Paste button: start of the text, or "Image". */
    internal fun pastePreview(): String? = when {
        pendingScreenshot != null -> "Screenshot"
        latestClip == null -> null
        Clips.isImage(latestClip!!) -> "Image"
        else -> latestClip!!.replace('\n', ' ').take(28)
    }
    internal var pendingCapsCheck = false
    /** After a held backspace is released, update auto-capitals once. */
    internal val capsAfterRepeat = Runnable {
        if (pendingCapsCheck) { pendingCapsCheck = false; updateAutoCaps() }
    }

    // --- Emoji, clipboard, editing ---










    /** The copy / screenshot last pasted with the Paste button (not offered again). */
    internal var pastedClipTime = 0L
    internal var pastedScreenshot: android.net.Uri? = null










    // --- Shift and Chinese mode ---

    /** Shift key: off -> one capital -> caps lock -> off. */
    internal var lastShiftTap = 0L








    // --- Assist mode ---







    /** Runs [block] off the main thread and turns the outcome (or any error) into a [Load]. */
    internal suspend fun <T> onDemand(
        fallbackError: String,
        block: (SentenceCache, () -> TranslationService) -> T,
    ): Load<T> = try {
        withContext(Dispatchers.IO) {
            val cache = SentenceCache(this@PinyinKeysService)
            val service = {
                Settings(this@PinyinKeysService).translationService()
                    ?: throw TranslationException("Add your API key in the Pinyin Keys app.")
            }
            Load.Loaded(block(cache, service))
        }
    } catch (e: TranslationException) {
        Load.Error(e.userMessage)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "On-demand load failed: ${e.javaClass.simpleName}")
        Load.Error(fallbackError)
    }

    internal var lastWarmUpAt = 0L







    // --- Practice mode ---











    companion object {
        /** Times a word must be written capitalised mid-sentence before it's learned. */
        internal const val CAP_AFTER = 2

        /** Well-known terminal / SSH apps (public package names). */
        internal val TERMINAL_APPS = setOf(
            "com.server.auditor.ssh.client",  // Termius
            "com.termux",
            "com.sonelli.juicessh",
            "org.connectbot",
        )
        internal const val TAG = "PinyinKeys"
        internal const val MAX_FIELD_CHARS = 10_000
        /** Marks the "keep exactly what I typed" suggestion. */
        const val LITERAL = "\u0001"
        /** Marks the suggestion that space will put in place of your word (autocorrect). */
        const val AUTO = "\u0002"
        /** In Chinese mode these keys type the full-width Chinese punctuation instead. */
        internal val CHINESE_PUNCTUATION = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；",
            "(" to "（", ")" to "）",
        )
    }
}
