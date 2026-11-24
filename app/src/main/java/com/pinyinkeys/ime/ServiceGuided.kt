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

// "Type it yourself": after translating, tap 中 and type the Chinese in pinyin.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

internal fun PinyinKeysService.onlyHan(s: String) = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

/** Compares what's typed so far (the field was cleared at the start) with the translation. */
internal fun PinyinKeysService.refreshGuided() {
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

internal fun PinyinKeysService.endGuided() {
    guidedTarget = null
    guidedRemaining = ""
    guidedDone.value = 0
}
