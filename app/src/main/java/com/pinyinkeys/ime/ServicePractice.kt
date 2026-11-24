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

// Practice mode: type each syllable of the translation in pinyin.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

internal fun PinyinKeysService.setMode(m: Mode) {
    mode.value = m
    clearAssist()
    practiceState.value = PracticeState.Idle
}

/** Translate the field, load every sentence's syllables, clear the English, start. */
internal fun PinyinKeysService.startPractice() {
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
            strictTones = Settings(this@startPractice).strictTones
            val svc = service()
            val sentences = Translator(this@startPractice, svc, tone.value).translate(text)
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
internal fun PinyinKeysService.practiceTyped(text: String): Boolean {
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
internal fun PinyinKeysService.commitAutoUnits(start: PracticeState.Active): PracticeState.Active {
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

internal fun PinyinKeysService.showHint() {
    val p = practiceState.value as? PracticeState.Active ?: return
    practiceState.value = p.copy(hintShown = true)
}
