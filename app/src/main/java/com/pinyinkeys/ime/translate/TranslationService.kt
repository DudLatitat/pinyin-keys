package com.pinyinkeys.ime.translate

/**
 * Anything that can turn English into [TranslationResult].
 * [ClaudeTranslationService] is the only implementation for now; a second provider
 * would be another class implementing this, plus a picker in settings.
 */
interface TranslationService {
    /** Blocking network call: run it off the main thread. Throws [TranslationException]. */
    fun translate(english: String): TranslationResult

    /** 1–2 plain-English sentences on why [sentence] is structured that way. Blocking. */
    fun explain(sentence: Sentence): String
}
