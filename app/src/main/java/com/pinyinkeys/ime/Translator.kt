package com.pinyinkeys.ime

import android.content.Context
import com.pinyinkeys.ime.translate.Sentence
import com.pinyinkeys.ime.translate.TranslationException
import com.pinyinkeys.ime.translate.TranslationService

/**
 * Turns the typed English into sentence cards as fast as possible:
 *  1. split it into sentences on the phone,
 *  2. answer each from the built-in phrasebook or the on-phone cache (instant, free),
 *  3. send only the remaining sentences to the API, and cache what comes back.
 */
class Translator(private val context: Context, private val service: TranslationService) {

    /** [onSentence] gets (index, sentence) as each one becomes available, in any order. */
    fun translate(text: String, onSentence: (Int, Sentence) -> Unit = { _, _ -> }): List<Sentence> {
        val lines = splitSentences(text)
        if (lines.isEmpty()) throw TranslationException("Type something first.")
        val cache = SentenceCache(context)
        val out = arrayOfNulls<Sentence>(lines.size)
        val misses = ArrayList<Int>()
        lines.forEachIndexed { i, line ->
            val known = Phrasebook.get(context, line) ?: cache.getTranslation(line)
            if (known != null) {
                out[i] = known
                onSentence(i, known)
            } else misses += i
        }
        if (misses.isNotEmpty()) {
            val fresh = service.translateLines(misses.map { lines[it] }) { j, s -> onSentence(misses[j], s) }
            fresh.forEachIndexed { j, s ->
                out[misses[j]] = s
                cache.putTranslation(lines[misses[j]], s)
            }
        }
        return out.map { it ?: throw TranslationException("Couldn't translate. Try again.") }
    }

    companion object {
        /** Splits after . ! ? (and line breaks), keeping the punctuation with its sentence. */
        fun splitSentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?。！？])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

        /** Lookup key: lowercase, single spaces, no trailing . ! ? */
        fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?', ' ')
    }
}

/** Built-in common chat sentences (assets/phrasebook.tsv), loaded once. */
object Phrasebook {
    @Volatile private var map: Map<String, Sentence>? = null

    fun get(context: Context, english: String): Sentence? =
        load(context)[Translator.normalize(english)]?.copy(en = english)

    private fun load(context: Context): Map<String, Sentence> = map ?: synchronized(this) {
        map ?: HashMap<String, Sentence>().also { m ->
            context.assets.open("phrasebook.tsv").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") }.forEach { line ->
                    val p = line.split('\t')
                    if (p.size == 3) m.putIfAbsent(Translator.normalize(p[0]), Sentence(p[0], p[1], p[2]))
                }
            }
            map = m
        }
    }
}
