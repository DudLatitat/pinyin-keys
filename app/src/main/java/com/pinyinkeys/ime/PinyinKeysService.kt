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

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /** True in password / "incognito" fields. Later milestones hide all translation UI then. */
    private val sensitiveField = mutableStateOf(false)

    /** Number pad layout for number/phone/date fields, or null for the normal keyboard. */
    private val numberPad = mutableStateOf<NumberPadKind?>(null)
    /** Label for the enter key, from what the app asks for (Done, Next, Go, Send...). */
    private val enterLabel = mutableStateOf<String?>(null)
    /** Polite tone for translations (toolbar 随/敬). */
    private val tone = mutableStateOf(com.pinyinkeys.ime.translate.Tone.Casual)
    private val theme = mutableStateOf("System")
    private val numberRow = mutableStateOf(true)
    private val keyScale = mutableStateOf(1f)
    private val toneColors = mutableStateOf(true)
    private val feedback = mutableStateOf(KeyFeedback())

    /** What the Assist panel shows. Compose redraws whenever this changes. */
    private val assistState = mutableStateOf<AssistState>(AssistState.Idle)
    private var translateJob: Job? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val mode = mutableStateOf(Mode.Assist)

    // Typing state.
    private val shift = mutableStateOf(Shift.Off)
    /** Chinese mode: letters build pinyin, and the candidate bar suggests characters. */
    private val chinese = mutableStateOf(false)
    private val composing = mutableStateOf("")
    private val candidates = mutableStateOf<List<Candidate>>(emptyList())
    private var dictionary: PinyinDictionary? = null

    private val recentEmoji = mutableStateOf<List<String>>(emptyList())
    private val pinnedClips = mutableStateOf<List<String>>(emptyList())
    /** Things pasted with this keyboard, newest first (only text the user chose to paste). */
    private val clipHistory = mutableStateOf<List<String>>(emptyList())
    private val showPasteChip = mutableStateOf(false)

    // --- Undo / redo (see Undo.kt) ---
    private val undoHistory = UndoHistory()
    private val canUndo = mutableStateOf(false)
    /** A terminal app (see isTerminal): plain keyboard, but the clipboard is allowed. */
    private val terminalField = mutableStateOf(false)
    /** No clipboard in password fields (terminals excepted). */
    private val clipBlocked get() = sensitiveField.value && !terminalField.value
    private var hasSelection = false
    private var rawConnection: android.view.inputmethod.InputConnection? = null
    private var recording: RecordingConnection? = null

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

    private fun undoRedo(redo: Boolean) {
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
    private val glide = GlideState()
    /** The word just entered by gliding (tap a suggestion to swap it; ⌫ removes it). */
    private var glided: String? = null
    private var glideAlternatives: List<String> = emptyList()
    private val glideOn = mutableStateOf(true)
    private val oneHanded = mutableStateOf(0)

    private var shiftAtLastLetter = Shift.Off

    /** A glide began on a letter key, which typed its letter on touch-down: take it back. */
    private fun glideStarted() {
        val ic = currentInputConnection ?: return
        markOwnEdit()
        shift.value = shiftAtLastLetter  // typing that letter may have turned shift off
        ic.deleteSurroundingText(1, 0)
        if (currentWord.isNotEmpty()) currentWord = currentWord.dropLast(1)
        if (wordTouches.isNotEmpty()) wordTouches.removeAt(wordTouches.lastIndex)
    }

    private fun glideEnded(path: List<androidx.compose.ui.geometry.Offset>, keys: Map<Char, androidx.compose.ui.geometry.Rect>) {
        val dict = english ?: return
        val boost = nextWords?.boosts(sentence.toList()) ?: emptyMap()
        lifecycleScope.launch {
            val words = withContext(Dispatchers.Default) { GlideDecoder.decode(path, keys, dict, boost) }
            if (words.isEmpty()) { toast("No word matched that glide"); return@launch }
            commitGlide(words)
        }
    }

    private fun glideCase(w: String) = when (shift.value) {
        Shift.Lock -> w.uppercase()
        Shift.On -> w.replaceFirstChar { it.uppercase() }
        Shift.Off -> w
    }

    private fun commitGlide(words: List<String>) {
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
    private fun spaceHeld() {
        undoSpace()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    }

    private fun setOneHanded(side: Int) {
        oneHanded.value = side
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).oneHanded = side }
    }

    // --- English suggestions / autocorrect ---
    private var english: EnglishDictionary? = null
    private var nextWords: NextWords? = null
    /** The word being typed (tracked here, so we don't ask the app on every key). */
    private var currentWord = ""
    /** Where each letter of [currentWord] was tapped (for touch-aware autocorrect). */
    private val wordTouches = ArrayList<Touch?>()

    /** Tap positions for the current word, or empty if they're out of step with it. */
    private fun touchesForWord(): List<Touch?> =
        if (wordTouches.size == currentWord.length) wordTouches.toList() else emptyList()
    /** The last few finished words of this sentence, for next-word suggestions and learning. */
    private val sentence = ArrayList<String>()

    private fun addToSentence(word: String) {
        sentence += word
        if (sentence.size > 2) sentence.removeAt(0)  // only the last two matter
    }
    /** Set right after an autocorrect, so an immediate backspace can undo it. */
    private var lastAutocorrect: Pair<String, String>? = null
    private val suggestions = mutableStateOf<List<String>>(emptyList())
    /** User tapped ‹ to see the tools instead of suggestions; cleared on the next letter. */
    private val toolsForced = mutableStateOf(false)
    /** Shown briefly in the bar after an autocorrect: (what you typed, what it became). */
    private val correctionNotice = mutableStateOf<Pair<String, String>?>(null)
    private val hideCorrection = Runnable { correctionNotice.value = null }

    private var speech: Speech? = null

    // --- "Type it yourself": after translating, tap 中 to type the Chinese in pinyin ---
    /** The translated sentences you're typing, or null when not in this mode. */
    private var guidedTarget: List<String>? = null
    /** How many sentences you've typed correctly so far (cards show ✓). */
    private val guidedDone = mutableStateOf(0)
    /** The part of the translation still to type (Chinese characters only). */
    private var guidedRemaining = ""
    /** A new screenshot waiting to be offered; it's only copied if Paste is tapped. */
    private var pendingScreenshot: android.net.Uri? = null
    private var offeredScreenshot: android.net.Uri? = null
    /** Notices new photos while the keyboard is open (only if screenshots are enabled). */
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = checkRecentScreenshot()
    }
    private var mediaObserverOn = false
    /** The newest copy, offered by the Paste button. */
    private var latestClip: String? = null
    private var lastSeenClipTime = 0L
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val practiceState = mutableStateOf<PracticeState>(PracticeState.Idle)
    private var strictTones = false

    /** Per-card breakdown and "Why?"; state maps so Compose redraws each card on change. */
    private val detailsStates = mutableStateMapOf<Sentence, Load<SentenceDetails>>()
    private val whyStates = mutableStateMapOf<Sentence, Load<String>>()
    /** Sentences saved (or being saved) during this translation, so the star fills in. */
    private val savedStates = mutableStateMapOf<Sentence, Load<Unit>>()

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
                    onMode = ::setMode,
                    practice = practiceState.value,
                    onStartPractice = ::startPractice,
                    onHint = ::showHint,
                    onTranslate = ::translateField,
                    onInsertChinese = ::insertChinese,
                    detailsStates = detailsStates,
                    onExpand = ::loadDetails,
                    whyStates = whyStates,
                    onWhy = ::explain,
                    savedStates = savedStates,
                    onSave = ::saveSentence,
                    onText = ::typeText,
                    onBackspace = ::backspace,
                    onEnter = ::enter,
                    onSwitchKeyboard = { switchToPreviousInputMethod() },
                    shift = shift.value,
                    onShift = ::cycleShift,
                    // Never Chinese/suggestions in password fields: plain keyboard only.
                    chinese = chinese.value && !sensitiveField.value,
                    onToggleChinese = ::toggleChinese,
                    composing = composing.value,
                    candidates = candidates.value,
                    onCandidate = ::pickCandidate,
                    recentEmoji = recentEmoji.value,
                    onEmoji = ::typeEmoji,
                    readClipboard = ::readClipboard,
                    pinnedClips = pinnedClips.value,
                    clipHistory = clipHistory.value,
                    onPaste = ::pasteEntry,
                    onPin = { t -> updatePinned(listOf(t) + pinnedClips.value) },
                    onUnpin = { t -> updatePinned(pinnedClips.value - t) },
                    onDeleteClip = { t -> updateHistory(clipHistory.value - t) },
                    onClearClipHistory = { updateHistory(emptyList()) },
                    showPasteChip = showPasteChip.value,
                    pastePreview = pastePreview(),
                    suggestions = suggestions.value,
                    onSuggestion = ::pickSuggestion,
                    onForgetSuggestion = ::forgetWord,
                    toolsForced = toolsForced.value,
                    onShowTools = { toolsForced.value = true },
                    correction = correctionNotice.value,
                    onUndoCorrection = ::undoAutocorrect,
                    onSpeak = ::speak,
                    guidedDone = guidedDone.value,
                    onPasteChip = ::pasteFromChip,
                    onEdit = ::edit,
                    onOpenSettings = ::openSettings,
                    numberPad = numberPad.value,
                    enterLabel = enterLabel.value,
                    onCursorMove = ::moveCursor,
                    onReplaceLast = ::replaceLastChar,
                    onUndoSpace = ::undoSpace,
                    showNumberRow = numberRow.value,
                    keyScale = keyScale.value,
                    toneColors = toneColors.value,
                    feedback = feedback.value,
                    tone = tone.value,
                    onTogglePolite = ::togglePolite,
                    onExplain = ::explainCopied,
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

    private fun speak(text: String) {
        val s = speech ?: Speech(this).also { speech = it }
        if (!s.speak(text)) toast("No Chinese voice installed. Add one in Android's text-to-speech settings.")
    }

    /** Saves learned word pairs and touch habits (on the phone, encrypted). */
    private fun saveLearning() {
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
    private var wordsSinceSave = 0

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
    private fun clearAssist() {
        endGuided()
        if (assistState.value == AssistState.Idle) return
        translateJob?.cancel()
        assistState.value = AssistState.Idle
        detailsStates.clear(); whyStates.clear(); savedStates.clear()
    }

    /** True while held backspace should keep sending Delete key presses (see backspace()). */
    private var deleteWithKeyEvent = false

    /**
     * Terminal-style fields (input type "none", e.g. SSH apps) take key presses, not text.
     * There we send each character as a key press and never read anything back.
     */
    private val rawKeyField: Boolean
        get() = currentInputEditorInfo?.inputType == InputType.TYPE_NULL

    private val keyMap by lazy { android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) }

    private fun sendRaw(text: String) {
        val ic = currentInputConnection ?: return
        // The phone's built-in key map turns characters into key presses (with shift where
        // needed); characters it can't (e.g. emoji) are sent as plain text.
        val events = keyMap.getEvents(text.toCharArray())
        if (events != null) events.forEach { ic.sendKeyEvent(it) } else ic.commitText(text, 1)
        if (shift.value == Shift.On) shift.value = Shift.Off
    }

    private fun typeText(text: String) {
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
            ic.commitText(text, 1)
            if (wordTouches.size != currentWord.length) {
                wordTouches.clear(); repeat(currentWord.length) { wordTouches += null }
            }
            wordTouches += Touch(TouchTracker.dx, TouchTracker.dy)
            if (erasedWord == null) eraseStart = null
            currentWord += c
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
    private fun capsAfter(before: String) {
        if (shift.value == Shift.Lock || chinese.value || !capsField) return
        val t = before.trimEnd(' ')
        shift.value = if (t.isEmpty() || t.last() in ".?!\n") Shift.On else Shift.Off
    }

    private fun backspace() {
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

    private var lastBackspaceAt = 0L
    private var backspaceHoldStart = 0L

    /**
     * Puts back what was typed before the last autocorrect (backspace right after it, or
     * tapping the "teh → the" notice), and stops correcting that word. True if undone.
     */
    private fun undoAutocorrect(): Boolean {
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
        setPreferred(original.lowercase(), null)
        english?.let { d ->
            if (original.lowercase() in d.learnedFixes) {
                d.learnedFixes = d.learnedFixes - original.lowercase()
                val lines = d.learnedFixes.map { "${it.key}\t${it.value}" }
                lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).learnedFixes = lines }
            }
        }
        currentWord = original
        updateSuggestions()
        return true
    }

    // --- Suggestions and autocorrect (English) ---

    /** No suggestions, autocorrect or learning in passwords, emails, web addresses or numbers. */
    private fun suggestionsAllowed(): Boolean {
        if (sensitiveField.value || chinese.value) return false
        val type = currentInputEditorInfo?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val v = type and InputType.TYPE_MASK_VARIATION
        return v != InputType.TYPE_TEXT_VARIATION_URI && v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private var suggestJob: Job? = null

    /**
     * Works out suggestions for the current word on a background thread, so typing never
     * waits for it; a newer keystroke cancels an older, unfinished lookup.
     */
    private fun updateSuggestions() {
        suggestJob?.cancel()
        val dict = english
        val word = currentWord
        if (!suggestionsAllowed() || dict == null || word.isEmpty()) { suggestions.value = emptyList(); return }
        // Boosts from the sentence so far and your own word habits (copied for the background thread).
        val boosts = nextWords?.boosts(sentence.toList()) ?: emptyMap()
        val touches = touchesForWord()
        suggestJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) {
                val s = dict.suggest(word, boost = boosts, touches = touches)
                // Not a known word: also offer exactly what was typed (shown in quotes), so
                // it can be kept as-is instead of being "corrected".
                if (word.length >= 2 && !dict.isWord(word)) {
                    val rest = s.filter { !it.equals(word, ignoreCase = true) }
                    listOfNotNull(rest.getOrNull(0), LITERAL + word, rest.getOrNull(1))
                } else s
            }
            if (currentWord == word) suggestions.value = list
        }
    }

    private var loadedLearnVersion = -1
    private var learningLoading = false

    /**
     * Loads the dictionaries and everything learned from you, in the background. Runs at
     * start-up, and again when the app's "learned" screen changed something.
     */
    private fun loadLearning() {
        if (learningLoading) return
        learningLoading = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val st = Settings(this@PinyinKeysService)
                val version = st.learnVersion
                if (english != null && nextWords != null && version == loadedLearnVersion) return@withContext
                val dict = EnglishDictionary.get(this@PinyinKeysService)
                dict.userWords = st.userWords.toSet()
                dict.learnedFixes = st.learnedFixes.mapNotNull { l ->
                    l.split('\t').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] }
                }.toMap()
                dict.preferred = st.preferredPicks.mapNotNull { l ->
                    l.split('\t').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] }
                }.toMap()
                dict.touchBias = st.touchBias.mapNotNull { l ->
                    val p = l.split('\t')
                    if (p.size == 3 && p[0].length == 1) p[0][0] to Touch(p[1].toFloat(), p[2].toFloat()) else null
                }.toMap()
                val nw = nextWords ?: NextWords(this@PinyinKeysService)
                if (nextWords == null) nw.load() else nw.loadLearned()
                english = dict
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
    private fun learnTouches(word: String, touches: List<Touch?>) {
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
    private var touchBiasDirty = false

    /** Remember (or with null, forget) that you prefer [picked] when you type [typed]. */
    private fun setPreferred(typed: String, picked: String?) {
        val d = english ?: return
        val updated = if (picked == null) d.preferred - typed
            else (d.preferred - typed + (typed to picked)).entries.toList().takeLast(1000).associate { it.toPair() }
        if (updated == d.preferred) return
        d.preferred = updated
        val lines = updated.map { "${it.key}\t${it.value}" }
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).preferredPicks = lines }
    }

    /** Words in the current message that aren't in the dictionary and weren't corrected. */
    private val messageNewWords = LinkedHashSet<String>()

    /** 9. Long-press a suggestion: forget everything learned about that word. */
    private fun forgetWord(word: String) {
        val w = word.removePrefix(LITERAL).lowercase()
        english?.let { d ->
            d.userWords = d.userWords - w
            d.learnedFixes = d.learnedFixes.filter { (k, v) -> k != w && v != w }
            d.preferred = d.preferred.filter { (k, v) -> k != w && v != w }
        }
        nextWords?.forget(w)
        val dict = english
        lifecycleScope.launch(Dispatchers.IO) {
            val st = Settings(this@PinyinKeysService)
            if (dict != null) {
                st.userWords = dict.userWords.toList()
                st.learnedFixes = dict.learnedFixes.map { "${it.key}\t${it.value}" }
                st.preferredPicks = dict.preferred.map { "${it.key}\t${it.value}" }
            }
            nextWords?.save()
        }
        suggestions.value = suggestions.value.filter { !it.removePrefix(LITERAL).equals(w, ignoreCase = true) }
        toast("Forgot \"$w\"")
    }

    /** Next-word guesses, with one of your frequent two-word phrases in the third slot. */
    private fun predictions(): List<String> {
        val nw = nextWords ?: return emptyList()
        val words = nw.predict(sentence)
        val phrase = nw.phrase(sentence) ?: return words
        return words.take(2) + phrase
    }

    /** Remember that you fix the typo [typo] to [fix]. Kept encrypted on the phone. */
    private fun learnFix(typo: String, fix: String) {
        val dict = english ?: return
        dict.learnedFixes = (dict.learnedFixes + (typo to fix)).entries.toList().takeLast(1000).associate { it.toPair() }
        val lines = dict.learnedFixes.map { "${it.key}\t${it.value}" }
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).learnedFixes = lines }
    }

    /** Remember [word] as a real word: suggested, and never autocorrected. */
    private fun keepWord(word: String) {
        val dict = english ?: return
        val lower = word.lowercase()
        if (lower in dict.userWords) return
        dict.userWords = dict.userWords + lower
        val all = dict.userWords.toList().takeLast(2000)
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).userWords = all }
    }

    private fun resetWord() {
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
    private fun finishWord(ic: android.view.inputmethod.InputConnection, autocorrectOnly: Boolean) {
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
            val fix = english?.autocorrect(word, touches, nextWords?.boosts(sentence.toList()) ?: emptyMap())
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
        // A word you've typed a few times that isn't in the dictionary (slang, names) is
        // yours: stop "correcting" it. (3 times, so a habitual typo isn't locked in.)
        if (english?.isWord(final) == false && (nextWords?.count(final) ?: 0) >= 3) keepWord(final)
        if (english?.isWord(final) == false && final == word && final.all { it.isLetter() || it == '\'' }) {
            messageNewWords += final
            prevNonWord = final  // may be joined with the next word (see above)
        }
    }

    /** The last finished word, if it wasn't a word and was left as typed. */
    private var prevNonWord: String? = null

    /** Does this text box want automatic capitals? (decided once per box) */
    private val capsField: Boolean
        get() = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0

    /** True right after a picked suggestion added a space (see smart punctuation). */
    private var autoSpaced = false

    /** The word you started erasing with backspace, and the one fully erased (see learning). */
    private var eraseStart: String? = null
    private var erasedWord: String? = null

    /** Number of single-letter edits between two words (small words only). */
    private fun editDistance(a: String, b: String) = TextUtil.editDistance(a, b)

    /** When this keyboard last changed the text in a way that moves the cursor a lot. */
    private var lastOwnEditAt = 0L
    private fun markOwnEdit() { lastOwnEditAt = android.os.SystemClock.uptimeMillis() }

    private fun pickSuggestion(picked: String) {
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
    private fun recoverWord() {
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
    private fun pastePreview(): String? = when {
        pendingScreenshot != null -> "📷 Screenshot"
        latestClip == null -> null
        Clips.isImage(latestClip!!) -> "🖼 Image"
        else -> latestClip!!.replace('\n', ' ').take(28)
    }
    private var pendingCapsCheck = false
    /** After a held backspace is released, update auto-capitals once. */
    private val capsAfterRepeat = Runnable {
        if (pendingCapsCheck) { pendingCapsCheck = false; updateAutoCaps() }
    }

    // --- Emoji, clipboard, editing ---

    private fun typeEmoji(e: String) {
        typeText(e)
        val updated = (listOf(e) + (recentEmoji.value - e)).take(32)
        recentEmoji.value = updated
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).recentEmoji = updated }
    }

    /**
     * Reads the current clipboard text. Only called when the user opens the clipboard
     * panel (an explicit action), never in the background. Android lets the keyboard
     * that's currently in use read the clipboard.
     */
    private fun readClipboard(): String? {
        if (clipBlocked) return null
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun pasteText(text: String) {
        if (composing.value.isNotEmpty()) commitFirstCandidate()
        clearAssist()
        markOwnEdit()
        if (rawKeyField) sendRaw(text)  // terminals take key presses
        else currentInputConnection?.commitText(text, 1)
        showPasteChip.value = false
        currentWord = ""
        suggestions.value = emptyList()
        updateAutoCaps()
    }

    /** Pastes a history entry: text directly, an image through the app's image support. */
    private fun pasteEntry(entry: String) {
        showPasteChip.value = false
        val file = Clips.imageFile(this, entry)
        if (file == null) { pasteText(entry); return }
        if (!file.exists()) { toast("That image is no longer available."); return }
        val info = currentInputEditorInfo ?: return
        val accepted = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info)
        if (accepted.none { android.content.ClipDescription.compareMimeTypes("image/png", it) }) {
            toast("This app doesn't accept pasted images.")
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.clips", file)
        val content = androidx.core.view.inputmethod.InputContentInfoCompat(
            uri, android.content.ClipDescription("image", arrayOf("image/png")), null,
        )
        androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
            currentInputConnection ?: return, info, content,
            androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Is the current clip marked sensitive (passwords etc.)? Android 13+ sets this flag. */
    private fun isSensitiveClip(desc: android.content.ClipDescription): Boolean =
        desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true

    /**
     * A new copy happened: save it to the history (text, or a private copy of an image)
     * and offer it with the Paste button. Skips sensitive clips and password fields.
     */
    private fun onClipChanged() {
        if (clipBlocked) return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        if (isSensitiveClip(desc)) return
        lastSeenClipTime = desc.timestamp
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                val uri = item.uri
                if (uri != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }) {
                    saveImage(uri)
                } else {
                    item.coerceToText(this@PinyinKeysService)?.toString()
                        ?.takeIf { it.isNotBlank() && it.length <= 5000 }
                }
            } ?: return@launch
            latestClip = entry
            pendingScreenshot = null
            updateHistory(listOf(entry) + clipHistory.value)
            showPasteChip.value = true
        }
    }

    /** Copies a clipboard image into private storage (the original link expires). */
    private fun saveImage(uri: android.net.Uri): String? = runCatching {
        val name = "clip_${System.currentTimeMillis()}.png"
        val out = java.io.File(Clips.dir(this), name)
        val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: return null
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Clips.imageEntry(name)
    }.getOrNull()

    /** On keyboard open: catch a copy made while the keyboard wasn't running. */
    private fun checkRecentCopy() {
        showPasteChip.value = false
        if (clipBlocked) return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        if (desc.timestamp != lastSeenClipTime && System.currentTimeMillis() - desc.timestamp < 60_000) {
            onClipChanged()
        }
    }

    private fun pasteFromChip() {
        showPasteChip.value = false
        val shot = pendingScreenshot
        if (shot != null) {
            pendingScreenshot = null
            // Copy the screenshot now (the user asked to paste it), keep it in the history.
            lifecycleScope.launch {
                val entry = withContext(Dispatchers.IO) { saveImage(shot) }
                if (entry == null) { toast("Couldn't read that screenshot."); return@launch }
                updateHistory(listOf(entry) + clipHistory.value)
                pasteEntry(entry)
            }
            return
        }
        latestClip?.let(::pasteEntry)
    }

    /** If enabled, offer a screenshot taken in the last minute (each one only once). */
    private fun checkRecentScreenshot() {
        if (sensitiveField.value) return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                if (!Settings(this@PinyinKeysService).suggestScreenshots) null
                else Screenshots.recent(this@PinyinKeysService)
            } ?: return@launch
            if (uri == offeredScreenshot) return@launch
            offeredScreenshot = uri
            pendingScreenshot = uri
            showPasteChip.value = true
        }
    }

    private fun updateHistory(list: List<String>) {
        val updated = list.distinct().take(Clips.MAX_HISTORY)
        // Delete image files that dropped out of the history and aren't pinned.
        (clipHistory.value - updated.toSet() - pinnedClips.value.toSet())
            .mapNotNull { Clips.imageFile(this, it) }.forEach { it.delete() }
        clipHistory.value = updated
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).clipHistory = updated }
    }

    private fun updatePinned(list: List<String>) {
        val updated = list.distinct().take(50)
        pinnedClips.value = updated
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).pinnedClips = updated }
    }

    private fun edit(action: EditAction) {
        val ic = currentInputConnection ?: return
        currentWord = ""
        suggestions.value = emptyList()
        if (composing.value.isNotEmpty()) {
            ic.finishComposingText()
            setComposing("")
        }
        fun key(code: Int) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (action) {
            EditAction.Left -> key(KeyEvent.KEYCODE_DPAD_LEFT)
            EditAction.Right -> key(KeyEvent.KEYCODE_DPAD_RIGHT)
            EditAction.Up -> key(KeyEvent.KEYCODE_DPAD_UP)
            EditAction.Down -> key(KeyEvent.KEYCODE_DPAD_DOWN)
            EditAction.Home -> key(KeyEvent.KEYCODE_MOVE_HOME)
            EditAction.End -> key(KeyEvent.KEYCODE_MOVE_END)
            // Context-menu actions are the same ones the app's own long-press menu uses.
            EditAction.SelectAll -> ic.performContextMenuAction(android.R.id.selectAll)
            EditAction.Cut -> ic.performContextMenuAction(android.R.id.cut)
            EditAction.Copy -> ic.performContextMenuAction(android.R.id.copy)
            EditAction.Paste -> ic.performContextMenuAction(android.R.id.paste)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onlyHan(s: String) = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** Compares what's typed so far (the field was cleared at the start) with the translation. */
    private fun refreshGuided() {
        val target = guidedTarget ?: return
        val typed = onlyHan(currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: "")
        val all = onlyHan(target.joinToString(""))
        val same = typed.zip(all).takeWhile { (a, b) -> a == b }.count()
        guidedRemaining = all.drop(same)
        var pos = 0
        var done = 0
        for (t in target) {
            val len = onlyHan(t).length
            if (same >= pos + len) { done++; pos += len } else break
        }
        guidedDone.value = done
    }

    private fun endGuided() {
        guidedTarget = null
        guidedRemaining = ""
        guidedDone.value = 0
    }

    // --- Shift and Chinese mode ---

    /** Shift key: off -> one capital -> caps lock -> off. */
    private var lastShiftTap = 0L

    /** Shift: tap = one capital, double-tap = caps lock, tap again = off. */
    private fun cycleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTap < 350
        lastShiftTap = now
        shift.value = when {
            shift.value == Shift.Off -> Shift.On
            shift.value == Shift.On && doubleTap -> Shift.Lock
            else -> Shift.Off
        }
    }

    /**
     * Capitalize automatically at the start of a sentence. The app decides whether its
     * field wants this (getCursorCapsMode returns 0 for fields like emails or URLs).
     */
    private fun updateAutoCaps() {
        if (shift.value == Shift.Lock || chinese.value) return
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        shift.value = if (ic.getCursorCapsMode(type) != 0) Shift.On else Shift.Off
    }

    private fun toggleChinese() {
        if (composing.value.isNotEmpty()) {
            // Keep what was typed as plain letters.
            currentInputConnection?.finishComposingText()
            setComposing("")
        }
        chinese.value = !chinese.value
        shift.value = Shift.Off
        // With a finished translation on screen, switching to 中 means "let me type it":
        // clear the English and guide the typing. Switching back ends it.
        val shown = assistState.value as? AssistState.Result
        if (chinese.value && shown != null && shown.complete) {
            replaceField("")
            guidedTarget = shown.reply?.let { listOf(it.hanzi) } ?: shown.result.sentences.map { it.hanzi }
            refreshGuided()
        } else if (!chinese.value) {
            endGuided()
        }
        if (chinese.value && dictionary == null) {
            // Load the dictionary in the background the first time Chinese mode is used.
            lifecycleScope.launch {
                dictionary = withContext(Dispatchers.IO) { PinyinDictionary.get(this@PinyinKeysService) }
                setComposing(composing.value)  // refresh suggestions if letters were typed meanwhile
            }
        } else {
            updateAutoCaps()
        }
    }

    /**
     * Shows the typed pinyin underlined in the app's text field ("composing text",
     * the standard way keyboards show unfinished input) and updates suggestions.
     */
    private fun setComposing(letters: String) {
        composing.value = letters
        val found = dictionary?.candidates(letters) ?: emptyList()
        // Guided: put suggestions that continue the translation first (longest first).
        candidates.value = if (guidedTarget != null && guidedRemaining.isNotEmpty()) {
            val (match, rest) = found.partition { guidedRemaining.startsWith(onlyHan(it.word)) && onlyHan(it.word).isNotEmpty() }
            match.sortedByDescending { it.word.length } + rest
        } else found
        val ic = currentInputConnection ?: return
        if (letters.isEmpty()) ic.commitText("", 1) else ic.setComposingText(letters, 1)
    }

    /** Suggestion tapped: put it in, and keep any letters it didn't use for the next pick. */
    private fun pickCandidate(c: Candidate) {
        val rest = composing.value.drop(c.consumed)
        val ic = currentInputConnection ?: return
        ic.commitText(c.word, 1)  // replaces the underlined pinyin
        composing.value = ""
        refreshGuided()
        setComposing(rest)
    }

    /** Space or punctuation while typing pinyin: take the top suggestion (or the letters). */
    private fun commitFirstCandidate() {
        while (composing.value.isNotEmpty()) {
            val first = candidates.value.firstOrNull()
            if (first == null) {
                currentInputConnection?.commitText(composing.value, 1)
                composing.value = ""
                candidates.value = emptyList()
                return
            }
            pickCandidate(first)
            // Space commits one pick; if letters remain, leave them for the user to choose.
            return
        }
    }

    private fun enter() {
        // Enter while typing pinyin: keep the letters as they are, don't send yet.
        if (composing.value.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing.value = ""
            candidates.value = emptyList()
            return
        }
        clearAssist()
        practiceState.value = PracticeState.Idle
        currentInputConnection?.let { finishWord(it, autocorrectOnly = false) }
        // You sent (or finished) the message with these new words as typed: they're yours.
        if (suggestionsAllowed()) messageNewWords.forEach(::keepWord)
        messageNewWords.clear()
        sentence.clear()
        suggestions.value = emptyList()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        // Many chat apps set an "action" (Send, Go, ...) on their field. If there is one,
        // and the field didn't ask us not to, trigger it; otherwise insert a newline.
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // --- Assist mode ---

    /** Reads the whole text field. Only ever called from the Translate button. */
    private fun readFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        val text = extracted ?: buildString {
            append(ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) ?: "")
            append(ic.getSelectedText(0) ?: "")
            append(ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) ?: "")
        }
        // Length only, never content (see CLAUDE.md).
        Log.d(TAG, "Read field: ${text.length} chars (extracted=${extracted != null})")
        return text
    }

    private fun translateField() {
        if (sensitiveField.value) return
        val text = readFieldText().trim()
        if (text.isEmpty()) {
            assistState.value = AssistState.Error("Type something first.")
            return
        }
        val service = Settings(this).translationService()
        if (service == null) {
            assistState.value = AssistState.Error("Add your API key in the Pinyin Keys app.")
            return
        }
        translateJob?.cancel()
        detailsStates.clear(); whyStates.clear(); savedStates.clear()
        assistState.value = AssistState.Loading
        // lifecycleScope is cancelled automatically when the keyboard service is destroyed.
        translateJob = lifecycleScope.launch {
            val job = coroutineContext[Job]
            // Cards by sentence position; cached ones fill in instantly, the rest as they arrive.
            val partial = sortedMapOf<Int, com.pinyinkeys.ime.translate.Sentence>()
            assistState.value = try {
                // Dispatchers.IO: network must not run on the main (UI) thread.
                val result = withContext(Dispatchers.IO) {
                    Translator(this@PinyinKeysService, service, tone.value).translate(text) { i, sentence ->
                        // Called on a background thread as each sentence is ready: show it right away.
                        mainHandler.post {
                            if (translateJob != job || job?.isActive != true) return@post
                            partial[i] = sentence
                            assistState.value = AssistState.Result(
                                com.pinyinkeys.ime.translate.TranslationResult(partial.values.toList()), complete = false,
                            )
                        }
                    }
                }
                AssistState.Result(com.pinyinkeys.ime.translate.TranslationResult(result))
            } catch (e: TranslationException) {
                AssistState.Error(e.userMessage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Translate failed: ${e.javaClass.simpleName}")
                // Release builds rename classes, so also note what kind of error it was.
                AssistState.Error("Couldn't translate (E4: ${errorKind(e)}). Try again.")
            }
        }
    }

    /** Card opened: load its breakdown (saved copy first, otherwise the API, once). */
    private fun loadDetails(sentence: Sentence) {
        if (sensitiveField.value) return
        val current = detailsStates[sentence]
        if (current is Load.Loading || current is Load.Loaded) return
        detailsStates[sentence] = Load.Loading
        lifecycleScope.launch {
            detailsStates[sentence] = onDemand("Couldn't load breakdown. Try again.") { cache, service ->
                // Breakdowns saved before word meanings existed are fetched again once.
                cache.getDetails(sentence)?.takeIf { d -> d.chunks.all { it.meaning.isNotEmpty() } }
                    ?: service().details(sentence).also { cache.putDetails(sentence, it) }
            }
        }
    }

    /** "Why?" tapped: saved answer if there is one, otherwise ask the API once. */
    private fun explain(sentence: Sentence) {
        if (sensitiveField.value) return
        val details = (detailsStates[sentence] as? Load.Loaded)?.value ?: return
        val current = whyStates[sentence]
        if (current is Load.Loading || current is Load.Loaded) return
        whyStates[sentence] = Load.Loading
        lifecycleScope.launch {
            whyStates[sentence] = onDemand("Couldn't load explanation. Try again.") { cache, service ->
                cache.getWhy(sentence) ?: service().explain(sentence, details).also { cache.putWhy(sentence, it) }
            }
        }
    }

    /** Star tapped: save the sentence (with its breakdown) to the on-phone database. */
    private fun saveSentence(sentence: Sentence) {
        if (sensitiveField.value) return
        val current = savedStates[sentence]
        if (current is Load.Loading || current is Load.Loaded) return
        savedStates[sentence] = Load.Loading
        lifecycleScope.launch {
            savedStates[sentence] = onDemand("Couldn't save. Try again.") { cache, service ->
                val details = cache.getDetails(sentence)
                    ?: service().details(sentence).also { cache.putDetails(sentence, it) }
                kotlinx.coroutines.runBlocking {
                    val dao = AppDatabase.get(this@PinyinKeysService).saved()
                    if (!dao.exists(sentence.en, sentence.hanzi)) {
                        dao.insert(
                            SavedSentence(
                                en = sentence.en, hanzi = sentence.hanzi, pinyin = sentence.pinyin,
                                chunksJson = kotlinx.serialization.json.Json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(Chunk.serializer()), details.chunks,
                                ),
                                savedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        }
    }

    /** A short, content-free description of an unexpected error, for the on-screen message. */
    private fun errorKind(e: Throwable): String = when (e) {
        is java.io.IOException -> "network"
        is SecurityException -> "permission"
        is IllegalStateException -> "state"
        is IllegalArgumentException -> "argument"
        is NullPointerException -> "null"
        else -> e.javaClass.simpleName.take(20)
    }

    /** Runs [block] off the main thread and turns the outcome (or any error) into a [Load]. */
    private suspend fun <T> onDemand(
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

    private var lastWarmUpAt = 0L

    /**
     * When the keyboard opens, set up the API connection ahead of time so Translate is
     * faster. Sends no message text (owner's decision; see CLAUDE.md). At most once a minute.
     */
    private fun warmUpConnection() {
        if (sensitiveField.value) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWarmUpAt < 60_000) return
        lastWarmUpAt = now
        lifecycleScope.launch(Dispatchers.IO) {
            Settings(this@PinyinKeysService).translationService()?.warmUp()
        }
    }

    /** Long-press alternate chosen: swap the character just typed for [alt]. */
    private fun replaceLastChar(alt: String) {
        if (composing.value.isNotEmpty()) {
            setComposing(composing.value.dropLast(1))
        } else {
            markOwnEdit()
            currentInputConnection?.deleteSurroundingText(1, 0)
            if (currentWord.isNotEmpty()) currentWord = currentWord.dropLast(1)
        }
        typeText(alt)
    }

    /** A space-bar slide started: take back the space that was typed on touch-down. */
    private fun undoSpace() {
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") { markOwnEdit(); ic.deleteSurroundingText(1, 0) }
    }

    /** Space-bar slide: move the cursor [steps] characters (negative = left). */
    private fun moveCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.value.isNotEmpty()) { ic.finishComposingText(); setComposing("") }
        currentWord = ""
        suggestions.value = emptyList()
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun togglePolite() {
        // Cycles 随 casual → 俏 slang → 敬 polite.
        tone.value = tone.value.next()
        val v = tone.value
        lifecycleScope.launch(Dispatchers.IO) { Settings(this@PinyinKeysService).tone = v }
        toast("${v.label} tone (${v.icon}) for translations")
    }

    /**
     * 解: explain a Chinese message you copied (from a chat). Reads the clipboard because
     * you tapped the button, sends that text to the API, and shows sentence cards with
     * pinyin and English plus a suggested reply that Insert can type for you.
     */
    private fun explainCopied() {
        if (sensitiveField.value) return
        val text = latestClip?.takeIf { !Clips.isImage(it) } ?: readClipboard()
        if (text.isNullOrBlank() || onlyHan(text).isEmpty()) {
            assistState.value = AssistState.Error("Copy a Chinese message first, then tap 解.")
            return
        }
        val service = Settings(this).translationService()
            ?: run { assistState.value = AssistState.Error("Add your API key in the Pinyin Keys app."); return }
        translateJob?.cancel()
        detailsStates.clear(); whyStates.clear(); savedStates.clear()
        assistState.value = AssistState.Loading
        translateJob = lifecycleScope.launch {
            assistState.value = try {
                val ex = withContext(Dispatchers.IO) { service.explainChinese(text.take(2000)) }
                AssistState.Result(
                    com.pinyinkeys.ime.translate.TranslationResult(ex.sentences), complete = true, reply = ex.reply,
                )
            } catch (e: TranslationException) {
                AssistState.Error(e.userMessage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AssistState.Error("Couldn't explain (E4: ${errorKind(e)}). Try again.")
            }
        }
    }

    // --- Practice mode ---

    private fun setMode(m: Mode) {
        mode.value = m
        clearAssist()
        practiceState.value = PracticeState.Idle
    }

    /** Translate the field, load every sentence's syllables, clear the English, start. */
    private fun startPractice() {
        if (sensitiveField.value) return
        // Restart: the field now holds Chinese, so replay the same sentences from the top.
        (practiceState.value as? PracticeState.Active)?.let { p ->
            replaceField("")
            practiceState.value = commitAutoUnits(PracticeState.Active(p.sentences, p.units))
            return
        }
        val text = readFieldText().trim()
        if (text.isEmpty()) {
            practiceState.value = PracticeState.Error("Type the English first.")
            return
        }
        translateJob?.cancel()
        practiceState.value = PracticeState.Loading
        translateJob = lifecycleScope.launch {
            val result = onDemand("Couldn't start practice. Try again.") { cache, service ->
                strictTones = Settings(this@PinyinKeysService).strictTones
                val svc = service()
                val sentences = Translator(this@PinyinKeysService, svc, tone.value).translate(text)
                val units = sentences.map { s ->
                    val d = cache.getDetails(s) ?: svc.details(s).also { cache.putDetails(s, it) }
                    Practice.buildUnits(s, d)
                        ?: throw TranslationException("Couldn't line up the pinyin. Try again.")
                }
                PracticeState.Active(sentences, units)
            }
            practiceState.value = when (result) {
                is Load.Loaded -> {
                    replaceField("")
                    commitAutoUnits(result.value)
                }
                is Load.Error -> PracticeState.Error(result.message)
                Load.Loading -> PracticeState.Loading
            }
        }
    }

    /** Handles a typed key in Practice. Returns true if Practice used it. */
    private fun practiceTyped(text: String): Boolean {
        val p = practiceState.value
        if (mode.value != Mode.Practice || p !is PracticeState.Active || p.done) return false
        val c = text.singleOrNull() ?: return true
        if (!c.isLetterOrDigit()) return true  // ignore space/punctuation: it's filled in for you
        val target = p.current?.syllable ?: return true
        val buffer = p.buffer + c.lowercaseChar()
        practiceState.value = if (Practice.matches(buffer, target, strictTones)) {
            currentInputConnection?.commitText(p.current!!.text, 1)
            commitAutoUnits(p.copy(unitIndex = p.unitIndex + 1, buffer = "", hintShown = false))
        } else {
            p.copy(buffer = buffer)
        }
        return true
    }

    /** Commits punctuation automatically and moves to the next sentence when one is finished. */
    private fun commitAutoUnits(start: PracticeState.Active): PracticeState.Active {
        var p = start
        while (true) {
            val cur = p.current
            if (cur == null) {
                if (p.sentenceIndex + 1 < p.units.size) {
                    p = p.copy(sentenceIndex = p.sentenceIndex + 1, unitIndex = 0)
                    continue
                }
                return p  // all done
            }
            if (cur.syllable != null) return p
            currentInputConnection?.commitText(cur.text, 1)
            p = p.copy(unitIndex = p.unitIndex + 1)
        }
    }

    private fun showHint() {
        val p = practiceState.value as? PracticeState.Active ?: return
        practiceState.value = p.copy(hintShown = true)
    }

    /** Replaces everything in the text field with [text]. */
    private fun replaceField(text: String) {
        val ic = currentInputConnection ?: return
        // Batch edit: the app sees one change instead of several flickering ones.
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        markOwnEdit()
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(before, after)
        if (text.isNotEmpty()) ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    /** Replaces everything in the text field with the Chinese. */
    private fun insertChinese() {
        val state = assistState.value as? AssistState.Result ?: return
        if (!state.complete) return
        // After explaining a received message, Insert types the suggested reply.
        state.reply?.let { replyWith ->
            replaceField(replyWith.hanzi)
            assistState.value = AssistState.Idle
            return
        }
        val result = state.result
        replaceField(result.sentences.joinToString("") { it.hanzi })
        assistState.value = AssistState.Idle
    }

    /** The action the app shows on its enter key ("Done", "Next"...), if any. */
    private fun enterLabelFor(info: EditorInfo): String? {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_PREVIOUS -> "Back"
            else -> null
        }
    }

    /**
     * Terminal / SSH apps: they mark their input as "raw keys" or as a visible password
     * (to turn off autocorrect), but they aren't password fields. They still get the
     * plain keyboard, plus the clipboard (owner's decision; see CLAUDE.md).
     */
    private fun isTerminal(info: EditorInfo): Boolean =
        info.inputType == InputType.TYPE_NULL || info.packageName in TERMINAL_APPS

    private fun isSensitive(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        // Terminals ask for raw key presses: plain keyboard, nothing learned or read.
        val raw = info.inputType == InputType.TYPE_NULL
        return password || noLearning || raw
    }

    companion object {
        /** Well-known terminal / SSH apps (public package names). */
        private val TERMINAL_APPS = setOf(
            "com.server.auditor.ssh.client",  // Termius
            "com.termux",
            "com.sonelli.juicessh",
            "org.connectbot",
        )
        private const val TAG = "PinyinKeys"
        private const val MAX_FIELD_CHARS = 10_000
        /** Marks the "keep exactly what I typed" suggestion. */
        const val LITERAL = "\u0001"
        /** In Chinese mode these keys type the full-width Chinese punctuation instead. */
        private val CHINESE_PUNCTUATION = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；",
            "(" to "（", ")" to "）",
        )
    }
}
