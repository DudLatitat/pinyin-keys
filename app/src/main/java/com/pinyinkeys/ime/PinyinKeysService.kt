package com.pinyinkeys.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pinyinkeys.ime.translate.Sentence
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

    /** "Why?" state per sentence card; a state map so Compose redraws each card on change. */
    private val whyStates = mutableStateMapOf<Sentence, WhyState>()

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
                    onTranslate = ::translateField,
                    onInsertChinese = ::insertChinese,
                    whyStates = whyStates,
                    onWhy = ::explain,
                    onText = ::typeText,
                    onBackspace = ::backspace,
                    onEnter = ::enter,
                    onSwitchKeyboard = { switchToPreviousInputMethod() },
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
            whyStates.clear()
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    // --- Key actions. `currentInputConnection` is our link to the app's text field. ---

    private fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        // If text is selected, delete the selection; otherwise delete one character.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun enter() {
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
        whyStates.clear()
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

    /** "Why?" tapped: use the saved answer if there is one, otherwise ask the API once. */
    private fun explain(sentence: Sentence) {
        if (sensitiveField.value) return
        if (whyStates[sentence] is WhyState.Loading || whyStates[sentence] is WhyState.Loaded) return
        whyStates[sentence] = WhyState.Loading
        lifecycleScope.launch {
            whyStates[sentence] = try {
                withContext(Dispatchers.IO) {
                    val cache = WhyCache(this@PinyinKeysService)
                    cache.get(sentence)?.let { return@withContext WhyState.Loaded(it) }
                    val service = Settings(this@PinyinKeysService).translationService()
                        ?: return@withContext WhyState.Error("Add your API key in the Pinyin Keys app.")
                    val why = service.explain(sentence)
                    cache.put(sentence, why)
                    WhyState.Loaded(why)
                }
            } catch (e: TranslationException) {
                WhyState.Error(e.userMessage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Why failed: ${e.javaClass.simpleName}")
                WhyState.Error("Couldn't load explanation. Try again.")
            }
        }
    }

    /** Replaces everything in the text field with the Chinese. */
    private fun insertChinese() {
        val result = (assistState.value as? AssistState.Result)?.result ?: return
        val ic = currentInputConnection ?: return
        val hanzi = result.sentences.joinToString("") { it.hanzi }
        // Batch edit: the app sees one change instead of several flickering ones.
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(before, after)
        ic.commitText(hanzi, 1)
        ic.endBatchEdit()
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
    }
}
