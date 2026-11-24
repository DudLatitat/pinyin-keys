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

// Key actions that edit the text: cursor moves, the editing panel, enter, alternates.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

internal fun PinyinKeysService.enter() {
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

internal fun PinyinKeysService.toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

internal fun PinyinKeysService.edit(action: EditAction) {
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

internal fun PinyinKeysService.openSettings() {
    startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Long-press alternate chosen: swap the character just typed for [alt]. */
internal fun PinyinKeysService.replaceLastChar(alt: String) {
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
internal fun PinyinKeysService.undoSpace() {
    val ic = currentInputConnection ?: return
    if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") { markOwnEdit(); ic.deleteSurroundingText(1, 0) }
}

/** Space-bar slide: move the cursor [steps] characters (negative = left). */
internal fun PinyinKeysService.moveCursor(steps: Int) {
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
