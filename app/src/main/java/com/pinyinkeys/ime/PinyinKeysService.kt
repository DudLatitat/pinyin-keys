package com.pinyinkeys.ime

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

    /** What the Assist panel shows. Compose redraws whenever this changes. */
    private val assistState = mutableStateOf<AssistState>(AssistState.Idle)
    private var translateJob: Job? = null

    private val mode = mutableStateOf(Mode.Assist)

    // Typing state.
    private val shift = mutableStateOf(Shift.Off)
    /** Chinese mode: letters build pinyin, and the candidate bar suggests characters. */
    private val chinese = mutableStateOf(false)
    private val composing = mutableStateOf("")
    private val candidates = mutableStateOf<List<Candidate>>(emptyList())
    private var dictionary: PinyinDictionary? = null
    private val practiceState = mutableStateOf<PracticeState>(PracticeState.Idle)
    private var strictTones = false

    /** Per-card breakdown and "Why?"; state maps so Compose redraws each card on change. */
    private val detailsStates = mutableStateMapOf<Sentence, Load<SentenceDetails>>()
    private val whyStates = mutableStateMapOf<Sentence, Load<String>>()
    /** Sentences saved (or being saved) during this translation, so the star fills in. */
    private val savedStates = mutableStateMapOf<Sentence, Load<Unit>>()

    override fun onCreate() {
        super.onCreate()
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
                )
            }
        }
    }

    /** Called each time the keyboard appears for a (possibly new) text field. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sensitiveField.value = info != null && isSensitive(info)
        // New text field: forget the previous translation.
        if (!restarting) {
            translateJob?.cancel()
            assistState.value = AssistState.Idle
            detailsStates.clear(); whyStates.clear(); savedStates.clear()
            practiceState.value = PracticeState.Idle
        }
        setComposing("")
        shift.value = Shift.Off
        updateAutoCaps()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Keyboard hidden: keep the typed letters as they are (they're already in the field).
        currentInputConnection?.finishComposingText()
        setComposing("")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDestroy() {
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
        if (assistState.value == AssistState.Idle) return
        translateJob?.cancel()
        assistState.value = AssistState.Idle
        detailsStates.clear(); whyStates.clear(); savedStates.clear()
    }

    private fun typeText(text: String) {
        if (practiceTyped(text)) return
        clearAssist()
        val ic = currentInputConnection ?: return
        val isChinese = chinese.value && !sensitiveField.value

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
            return
        }

        // English: two spaces after a word become ". " (like other keyboards).
        if (text == " ") {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                updateAutoCaps()
                return
            }
        }
        ic.commitText(text, 1)
        if (shift.value == Shift.On) shift.value = Shift.Off
        updateAutoCaps()
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
        clearAssist()
        val ic = currentInputConnection ?: return
        // If text is selected, delete the selection; otherwise delete one character.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
        updateAutoCaps()
    }

    // --- Shift and Chinese mode ---

    /** Shift key: off -> one capital -> caps lock -> off. */
    private fun cycleShift() {
        shift.value = when (shift.value) { Shift.Off -> Shift.On; Shift.On -> Shift.Lock; Shift.Lock -> Shift.Off }
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
        candidates.value = dictionary?.candidates(letters) ?: emptyList()
        val ic = currentInputConnection ?: return
        if (letters.isEmpty()) ic.commitText("", 1) else ic.setComposingText(letters, 1)
    }

    /** Suggestion tapped: put it in, and keep any letters it didn't use for the next pick. */
    private fun pickCandidate(c: Candidate) {
        val rest = composing.value.drop(c.consumed)
        val ic = currentInputConnection ?: return
        ic.commitText(c.word, 1)  // replaces the underlined pinyin
        composing.value = ""
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
            assistState.value = try {
                // Dispatchers.IO: network must not run on the main (UI) thread.
                AssistState.Result(withContext(Dispatchers.IO) { service.translate(text) })
            } catch (e: TranslationException) {
                AssistState.Error(e.userMessage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Translate failed: ${e.javaClass.simpleName}")
                AssistState.Error("Couldn't translate. Try again.")
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
                cache.getDetails(sentence) ?: service().details(sentence).also { cache.putDetails(sentence, it) }
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
                val sentences = svc.translate(text).sentences
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
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(before, after)
        if (text.isNotEmpty()) ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    /** Replaces everything in the text field with the Chinese. */
    private fun insertChinese() {
        val result = (assistState.value as? AssistState.Result)?.result ?: return
        replaceField(result.sentences.joinToString("") { it.hanzi })
        assistState.value = AssistState.Idle
    }

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
        return password || noLearning
    }

    companion object {
        private const val TAG = "PinyinKeys"
        private const val MAX_FIELD_CHARS = 10_000
        /** In Chinese mode these keys type the full-width Chinese punctuation instead. */
        private val CHINESE_PUNCTUATION = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；",
            "(" to "（", ")" to "）",
        )
    }
}
