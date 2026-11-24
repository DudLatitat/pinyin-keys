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

// Shift / caps and Chinese (pinyin) typing.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

/** Shift: tap = one capital, double-tap = caps lock, tap again = off. */
internal fun PinyinKeysService.cycleShift() {
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
internal fun PinyinKeysService.updateAutoCaps() {
    if (shift.value == Shift.Lock || chinese.value) return
    val ic = currentInputConnection ?: return
    val type = currentInputEditorInfo?.inputType ?: return
    shift.value = if (ic.getCursorCapsMode(type) != 0) Shift.On else Shift.Off
}

internal fun PinyinKeysService.toggleChinese() {
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
            dictionary = withContext(Dispatchers.IO) { PinyinDictionary.get(this@toggleChinese) }
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
internal fun PinyinKeysService.setComposing(letters: String) {
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
internal fun PinyinKeysService.pickCandidate(c: Candidate) {
    val rest = composing.value.drop(c.consumed)
    val ic = currentInputConnection ?: return
    ic.commitText(c.word, 1)  // replaces the underlined pinyin
    composing.value = ""
    refreshGuided()
    setComposing(rest)
}

/** Space or punctuation while typing pinyin: take the top suggestion (or the letters). */
internal fun PinyinKeysService.commitFirstCandidate() {
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
