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

// What kind of text field the keyboard is in (password, terminal, Send button...).
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

/** The action the app shows on its enter key ("Done", "Next"...), if any. */
internal fun PinyinKeysService.enterLabelFor(info: EditorInfo): String? {
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
internal fun PinyinKeysService.isTerminal(info: EditorInfo): Boolean =
    info.inputType == InputType.TYPE_NULL || info.packageName in PinyinKeysService.TERMINAL_APPS

internal fun PinyinKeysService.isSensitive(info: EditorInfo): Boolean {
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
