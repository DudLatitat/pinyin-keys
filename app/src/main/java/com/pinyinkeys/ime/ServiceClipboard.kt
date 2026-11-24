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

// Emoji panel, clipboard history, Paste button and screenshots.
// Part of PinyinKeysService, split out to keep files short: these are "extension
// functions", written outside the class but called as if they were inside it
// (they use the service's state, which lives in PinyinKeysService.kt).

internal fun PinyinKeysService.typeEmoji(e: String) {
    typeText(e)
    val updated = (listOf(e) + (recentEmoji.value - e)).take(32)
    recentEmoji.value = updated
    lifecycleScope.launch(Dispatchers.IO) { Settings(this@typeEmoji).recentEmoji = updated }
}

/**
 * Reads the current clipboard text. Only called when the user opens the clipboard
 * panel (an explicit action), never in the background. Android lets the keyboard
 * that's currently in use read the clipboard.
 */
internal fun PinyinKeysService.readClipboard(): String? {
    if (clipBlocked) return null
    val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
}

internal fun PinyinKeysService.pasteText(text: String) {
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
internal fun PinyinKeysService.pasteEntry(entry: String) {
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

/** Is the current clip marked sensitive (passwords etc.)? Android 13+ sets this flag. */
internal fun PinyinKeysService.isSensitiveClip(desc: android.content.ClipDescription): Boolean =
    desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true

/**
 * A new copy happened: save it to the history (text, or a private copy of an image)
 * and offer it with the Paste button. Skips sensitive clips and password fields.
 */
internal fun PinyinKeysService.onClipChanged() {
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
                item.coerceToText(this@onClipChanged)?.toString()
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
internal fun PinyinKeysService.saveImage(uri: android.net.Uri): String? = runCatching {
    val name = "clip_${System.currentTimeMillis()}.png"
    val out = java.io.File(Clips.dir(this), name)
    val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        ?: return null
    out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    Clips.imageEntry(name)
}.getOrNull()

/** On keyboard open: catch a copy made while the keyboard wasn't running. */
internal fun PinyinKeysService.checkRecentCopy() {
    showPasteChip.value = false
    if (clipBlocked) return
    val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
    val desc = cm.primaryClipDescription ?: return
    if (isSensitiveClip(desc) || System.currentTimeMillis() - desc.timestamp >= 60_000) return
    when {
        // A copy made while the keyboard wasn't running: save it and offer it.
        desc.timestamp != lastSeenClipTime -> onClipChanged()
        // Seen before but not pasted yet (the keyboard closed and reopened, e.g. after
        // switching apps): offer it again.
        desc.timestamp != pastedClipTime && latestClip != null -> showPasteChip.value = true
    }
}

internal fun PinyinKeysService.pasteFromChip() {
    showPasteChip.value = false
    val shot = pendingScreenshot
    pastedClipTime = lastSeenClipTime
    if (shot != null) {
        pendingScreenshot = null
        pastedScreenshot = shot
        // Copy the screenshot now (the user asked to paste it), keep it in the history.
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) { saveImage(shot) }
            if (entry == null) { toast("Couldn't read that screenshot."); return@launch }
            updateHistory(listOf(entry) + clipHistory.value)
            pasteEntry(entry)
        }
        return
    }
    latestClip?.let(this::pasteEntry)
}

/** If enabled, offer a screenshot taken in the last minute (each one only once). */
internal fun PinyinKeysService.checkRecentScreenshot() {
    if (sensitiveField.value) return
    lifecycleScope.launch {
        val uri = withContext(Dispatchers.IO) {
            if (!Settings(this@checkRecentScreenshot).suggestScreenshots) null
            else Screenshots.recent(this@checkRecentScreenshot)
        } ?: return@launch
        // Offered until pasted (it's only ever a minute or two old).
        if (uri == pastedScreenshot) return@launch
        offeredScreenshot = uri
        pendingScreenshot = uri
        showPasteChip.value = true
    }
}

internal fun PinyinKeysService.updateHistory(list: List<String>) {
    val updated = list.distinct().take(Clips.MAX_HISTORY)
    // Delete image files that dropped out of the history and aren't pinned.
    (clipHistory.value - updated.toSet() - pinnedClips.value.toSet())
        .mapNotNull { Clips.imageFile(this, it) }.forEach { it.delete() }
    clipHistory.value = updated
    lifecycleScope.launch(Dispatchers.IO) { Settings(this@updateHistory).clipHistory = updated }
}

internal fun PinyinKeysService.updatePinned(list: List<String>) {
    val updated = list.distinct().take(50)
    pinnedClips.value = updated
    lifecycleScope.launch(Dispatchers.IO) { Settings(this@updatePinned).pinnedClips = updated }
}
