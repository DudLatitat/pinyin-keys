package com.pinyinkeys.ime

import android.content.Context
import android.util.Log

/**
 * Offline English word list for suggestions and autocorrect.
 * Data: assets/en_words.txt (from AOSP LatinIME, Apache 2.0; see DICTIONARY_NOTICE.txt),
 * "word<TAB>frequency" per line, most common first. Everything stays in memory.
 */
class EnglishDictionary private constructor(
    /** lowercase word -> (word as written, frequency 0-255) */
    private val byLower: HashMap<String, Pair<String, Int>>,
    /** all lowercase words, sorted, for prefix search */
    private val sorted: Array<String>,
) {
    /** Words the user chose to keep (e.g. by undoing an autocorrect); never "corrected". */
    @Volatile var userWords: Set<String> = emptySet()

    fun frequency(word: String): Int = byLower[word.lowercase()]?.second ?: -1
    fun isWord(word: String) = word.lowercase().let { it in byLower || it in userWords }

    /**
     * Contractions typed without the apostrophe ("arent" -> "aren't", "im" -> "I'm").
     * These are by far the most common "typo", so they're checked first and win.
     */
    private fun apostropheFix(lower: String): String? {
        if (lower.length < 2) return null
        var best: Pair<String, Int>? = null
        for (i in 1 until lower.length) {
            val w = lower.substring(0, i) + "'" + lower.substring(i)
            val f = byLower[w]?.second ?: continue
            if (best == null || f > best.second) best = w to f
        }
        return best?.first
    }

    /**
     * Up to [n] suggestions for a partly typed [typed] word, best first: likely
     * corrections (one or two typos away) and completions, ranked by frequency and
     * closeness. The typed word's capitalization is kept.
     */
    fun suggest(typed: String, n: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val scores = HashMap<String, Double>()
        fun consider(w: String, score: Double) {
            if (score > (scores[w] ?: Double.NEGATIVE_INFINITY)) scores[w] = score
        }
        byLower[lower]?.let { consider(lower, it.second + 40.0) }  // exact word: strong
        if (lower in userWords) consider(lower, 300.0)
        apostropheFix(lower)?.let { consider(it, 400.0) }  // "arent" -> "aren't" first
        // Completions: words starting with what's typed.
        val start = lowerBound(lower)
        var i = start
        var seen = 0
        while (i < sorted.size && sorted[i].startsWith(lower) && seen < 1200) {
            val w = sorted[i]
            if (w != lower) consider(w, byLower.getValue(w).second - 4.0 * (w.length - lower.length))
            i++; seen++
        }
        // Corrections: one edit away (two for longer words), using the keyboard layout.
        if (lower.length >= 2) {
            for ((w, cost) in edits(lower)) {
                byLower[w]?.let { consider(w, it.second - cost * 25.0) }
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(n)
            .map { matchCase(byLower[it.key]?.first ?: it.key, typed) }
    }

    /**
     * The word to autocorrect [typed] to when space is pressed, or null to leave it.
     * Only changes words that aren't in the dictionary, to a common word one typo away.
     */
    fun autocorrect(typed: String): String? {
        val lower = typed.lowercase()
        if (lower in userWords) return null
        // Only when what was typed isn't a word itself ("ill", "were" and "its" are words).
        if (lower !in byLower) apostropheFix(lower)?.let { return matchCase(it, typed) }
        if (lower.length < 2 || lower in byLower || typed.any { it.isDigit() }) {
            // Fix a lone lowercase "i" and missing apostrophes like "dont".
            return if (typed == "i") "I" else null
        }
        val best = edits(lower).mapNotNull { (w, cost) -> byLower[w]?.let { Triple(w, cost, it.second) } }
            .filter { it.second <= 1.0 }
            .maxByOrNull { it.third - it.second * 20 } ?: return null
        if (best.third < 60) return null  // too rare to be confident
        return matchCase(byLower.getValue(best.first).first, typed)
    }

    /** Words one edit away (delete, swap, replace, insert), plus apostrophe fixes. */
    private fun edits(w: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(w.length * 60)
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        for (i in w.indices) {
            out += (w.removeRange(i, i + 1) to 1.0)
            if (i + 1 < w.length) out += (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) to 0.8)
            for (c in letters) if (c != w[i]) {
                // A neighbouring key is a more likely slip than a far-away one.
                val cost = if (c in (NEIGHBOURS[w[i]] ?: "")) 0.6 else 1.0
                out += (w.substring(0, i) + c + w.substring(i + 1) to cost)
            }
        }
        for (i in 0..w.length) for (c in letters) out += (w.substring(0, i) + c + w.substring(i) to if (c == '\'') 0.3 else 1.0)
        return out
    }

    private fun lowerBound(key: String): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < key) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        /** Keys next to each other on QWERTY. */
        private val NEIGHBOURS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg", 'y' to "tugh",
            'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol", 'a' to "qwsz", 's' to "awedxz",
            'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv", 'h' to "gyujnb", 'j' to "huikmn",
            'k' to "jiolm", 'l' to "kop", 'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb",
            'b' to "vghn", 'n' to "bhjm", 'm' to "njk",
        )

        /** Keep a capital first letter (or all caps) the user typed. */
        fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        @Volatile private var loaded: EnglishDictionary? = null

        /** Loads once; call off the main thread. */
        fun get(context: Context): EnglishDictionary = loaded ?: synchronized(this) {
            loaded ?: load(context).also { loaded = it }
        }

        private fun load(context: Context): EnglishDictionary {
            val map = HashMap<String, Pair<String, Int>>(100_000)
            context.assets.open("en_words.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val t = line.indexOf('\t')
                    if (t > 0) {
                        val w = line.substring(0, t)
                        val key = w.lowercase()
                        if (key !in map) map[key] = w to (line.substring(t + 1).toIntOrNull() ?: 0)
                    }
                }
            }
            Log.d("EnglishDictionary", "Loaded ${map.size} words")
            return EnglishDictionary(map, map.keys.sorted().toTypedArray())
        }
    }
}
