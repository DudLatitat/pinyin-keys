package com.pinyinkeys.ime.translate

/**
 * Anything that can turn English into [TranslationResult].
 * [ClaudeTranslationService] is the only implementation for now; a second provider
 * would be another class implementing this, plus a picker in settings.
 */
interface TranslationService {
    /**
     * Translates each of [lines] (one English sentence each). Blocking network call: run
     * it off the main thread. Throws [TranslationException]. [onLine] is called (on the
     * calling thread) with each line's index as soon as that line's translation arrives.
     */
    fun translateLines(
        lines: List<String>,
        tone: Tone = Tone.Casual,
        onLine: (Int, Sentence) -> Unit = { _, _ -> },
    ): List<Sentence>

    /**
     * For a received Chinese [message]: each sentence with pinyin and English, plus a short
     * casual reply. Blocking. Throws [TranslationException].
     */
    fun explainChinese(message: String): Explained

    /**
     * Opens the connection to the API ahead of time, so the first Translate is faster.
     * Sends no message text. Optional; failures are ignored.
     */
    fun warmUp() {}

    /** Structure chips and per-character syllables for one sentence. Blocking. */
    fun details(sentence: Sentence): SentenceDetails

    /** 1–2 plain-English sentences on why [sentence] is structured that way. Blocking. */
    fun explain(sentence: Sentence, details: SentenceDetails): String
}
