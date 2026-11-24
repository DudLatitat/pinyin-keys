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

// Assist mode: Translate, Explain, sentence cards, Insert.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

/** Reads the whole text field. Only ever called from the Translate button. */
internal fun PinyinKeysService.readFieldText(): String {
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

internal fun PinyinKeysService.translateField() {
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
                Translator(this@translateField, service, tone.value).translate(text) { i, sentence ->
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
internal fun PinyinKeysService.loadDetails(sentence: Sentence) {
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
internal fun PinyinKeysService.explain(sentence: Sentence) {
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
internal fun PinyinKeysService.saveSentence(sentence: Sentence) {
    if (sensitiveField.value) return
    val current = savedStates[sentence]
    if (current is Load.Loading || current is Load.Loaded) return
    savedStates[sentence] = Load.Loading
    lifecycleScope.launch {
        savedStates[sentence] = onDemand("Couldn't save. Try again.") { cache, service ->
            val details = cache.getDetails(sentence)
                ?: service().details(sentence).also { cache.putDetails(sentence, it) }
            kotlinx.coroutines.runBlocking {
                val dao = AppDatabase.get(this@saveSentence).saved()
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
internal fun PinyinKeysService.errorKind(e: Throwable): String = when (e) {
    is java.io.IOException -> "network"
    is SecurityException -> "permission"
    is IllegalStateException -> "state"
    is IllegalArgumentException -> "argument"
    is NullPointerException -> "null"
    else -> e.javaClass.simpleName.take(20)
}

/**
 * When the keyboard opens, set up the API connection ahead of time so Translate is
 * faster. Sends no message text (owner's decision; see CLAUDE.md). At most once a minute.
 */
internal fun PinyinKeysService.warmUpConnection() {
    if (sensitiveField.value) return
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastWarmUpAt < 60_000) return
    lastWarmUpAt = now
    lifecycleScope.launch(Dispatchers.IO) {
        Settings(this@warmUpConnection).translationService()?.warmUp()
    }
}

internal fun PinyinKeysService.togglePolite() {
    // Cycles 随 casual → 俏 slang → 敬 polite.
    tone.value = tone.value.next()
    val v = tone.value
    lifecycleScope.launch(Dispatchers.IO) { Settings(this@togglePolite).tone = v }
    toast("${v.label} tone (${v.icon}) for translations")
}

/**
 * 解: explain a Chinese message you copied (from a chat). Reads the clipboard because
 * you tapped the button, sends that text to the API, and shows sentence cards with
 * pinyin and English plus a suggested reply that Insert can type for you.
 */
internal fun PinyinKeysService.explainCopied() {
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

/** Replaces everything in the text field with [text]. */
internal fun PinyinKeysService.replaceField(text: String) {
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
internal fun PinyinKeysService.insertChinese() {
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
