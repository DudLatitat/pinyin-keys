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

    /** Every word (as written) with its frequency, plus your own words; for glide typing. */
    fun forEachWord(action: (String, Int) -> Unit) {
        for ((w, f) in byLower.values) action(w, f)
        for (u in userWords) if (u !in byLower) action(u, 120)
    }

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
     * Your personal touch habits: for each letter, how far off-centre you usually hit it
     * (learned from words you didn't correct). Taps are judged relative to that, not the
     * key's exact centre.
     */
    @Volatile var touchBias: Map<Char, Touch> = emptyMap()

    /**
     * Words with digits are left alone (numbers, "2nd", "covid19"), except one digit in
     * the middle or at the end of a word of letters: that's a slip onto the number row.
     */
    private fun isWordWithSlip(typed: String): Boolean {
        val digits = typed.count { it.isDigit() }
        return digits == 0 || (digits == 1 && !typed[0].isDigit() && typed.count { it.isLetter() } >= 2)
    }

    /** Words you picked over what you typed (typed -> picked): shown first next time. */
    @Volatile var preferred: Map<String, String> = emptyMap()

    /** Typos you've fixed yourself before (typed -> what you picked), learned on the phone. */
    @Volatile var learnedFixes: Map<String, String> = emptyMap()

    /**
     * Up to [n] suggestions for a partly typed [typed] word, best first: your own past fix
     * for this typo, likely corrections (using where you actually tapped, [touches]) and
     * completions, ranked by frequency, closeness and [boost] (sentence context, habits).
     */
    fun suggest(
        typed: String,
        n: Int = 3,
        boost: Map<String, Double> = emptyMap(),
        touches: List<Touch?> = emptyList(),
    ): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val scores = HashMap<String, Double>()
        fun consider(w: String, score: Double) {
            if (score > (scores[w] ?: Double.NEGATIVE_INFINITY)) scores[w] = score
        }
        if (!isWordWithSlip(typed)) return emptyList()
        preferred[lower]?.let { consider(it, 600.0) }           // the word you picked last time
        learnedFixes[lower]?.let { consider(it, 500.0) }        // your own past fix wins
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
        // Corrections: one slip away (two for longer words), judged by where you tapped.
        if (lower.length >= 2) {
            for ((w, cost) in corrections(lower, touches)) {
                byLower[w]?.let { consider(w, it.second - cost * 30.0) }
            }
        }
        // Context and personal boosts: words that fit the sentence so far, or that you type
        // often, move up (and are added even if the dictionary scan didn't reach them).
        for ((w, b) in boost) {
            if (w.startsWith(lower) && w != lower) {
                val base = byLower[w]?.second?.toDouble() ?: 120.0
                val current = scores[w] ?: (base - 4.0 * (w.length - lower.length))
                scores[w] = current + b
            } else if (w in scores) scores[w] = scores.getValue(w) + b / 2
        }
        return scores.entries.sortedByDescending { it.value }.take(n)
            .map { matchCase(byLower[it.key]?.first ?: it.key, typed) }
    }

    /**
     * The word to autocorrect [typed] to when space is pressed, or null to leave it.
     * Order: your own past fix for this typo; a missing apostrophe; then the best word one
     * slip away (two for longer words) given where you tapped and the sentence so far.
     * Words you use (yours or in the dictionary) are left alone, except a very rare word
     * when your taps clearly point at a common one.
     */
    fun autocorrect(typed: String, touches: List<Touch?> = emptyList(), boost: Map<String, Double> = emptyMap()): String? {
        val lower = typed.lowercase()
        if (lower in userWords) return null
        // A misspelling you corrected by picking a word before: use that word.
        if (lower !in byLower) preferred[lower]?.let { return matchCase(byLower[it]?.first ?: it, typed) }
        learnedFixes[lower]?.let { return matchCase(byLower[it]?.first ?: it, typed) }
        // Keep the dictionary's capitals: "im" -> "I'm".
        if (lower !in byLower) apostropheFix(lower)?.let { return matchCase(byLower[it]?.first ?: it, typed) }
        if (typed == "i") return "I"
        // "i'm", "i'll", "i've", "i'd" typed with the apostrophe: capital I.
        if (lower.startsWith("i'") && typed.startsWith("i") && lower in byLower) return "I" + typed.substring(1)
        if (lower.length < 2 || !isWordWithSlip(typed)) return null
        val known = byLower[lower]?.second
        val candidates = corrections(lower, touches).mapNotNull { (w, cost) ->
            byLower[w]?.let { Triple(w, cost, it.second + (boost[w] ?: 0.0) / 3) }
        }
        val best = candidates.maxByOrNull { it.third - it.second * 45 } ?: return null
        return when {
            // A real word was typed: only replace a rare one, and only on strong evidence.
            known != null -> if (known < 25 && best.second <= 0.3 && best.third >= 150) matchCase(byLower.getValue(best.first).first, typed) else null
            best.second > 1.2 || best.third < 50 -> null  // not confident enough
            else -> matchCase(byLower.getValue(best.first).first, typed)
        }
    }

    /** One-slip corrections, plus two neighbour slips for words of 5+ letters. */
    private fun corrections(w: String, touches: List<Touch?>): List<Pair<String, Double>> {
        val one = edits(w, touches)
        if (w.length < 5) return one
        val out = ArrayList(one)
        // Two slips: only cheap substitutions (neighbouring keys), to keep it fast and sane.
        for ((w1, c1) in one) {
            if (c1 > 0.6 || w1.length != w.length) continue
            for (i in w1.indices) {
                if (w1[i] != w[i]) continue  // don't change the same letter twice
                for (c in NEIGHBOURS[w[i]] ?: "") {
                    val cost = subCost(w[i], c, touches.getOrNull(i))
                    if (cost <= 0.6) out += (w1.substring(0, i) + c + w1.substring(i + 1) to c1 + cost + 0.2)
                }
            }
        }
        return out
    }

    /** Words one edit away (delete, swap, replace, insert), plus apostrophe fixes. */
    private fun edits(w: String, touches: List<Touch?> = emptyList()): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(w.length * 60)
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        for (i in w.indices) {
            out += (w.removeRange(i, i + 1) to 1.0)
            if (i + 1 < w.length) out += (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) to 0.8)
            for (c in letters) if (c != w[i]) {
                out += (w.substring(0, i) + c + w.substring(i + 1) to subCost(w[i], c, touches.getOrNull(i)))
            }
        }
        for (i in 0..w.length) for (c in letters) out += (w.substring(0, i) + c + w.substring(i) to if (c == '\'') 0.3 else 1.0)
        return out
    }

    /**
     * How unlikely it is that you meant [meant] when the keyboard got [typed]. With the
     * tap position we measure how far your finger was from [meant]'s key; without it,
     * neighbouring keys count as likely slips.
     */
    private fun subCost(typed: Char, meant: Char, touch: Touch?): Double {
        val a = KEY_POS[typed] ?: return 1.0
        val b = KEY_POS[meant] ?: return 1.0
        if (touch == null) return if (meant in (NEIGHBOURS[typed] ?: "")) 0.6 else 1.0
        val bias = touchBias[typed]
        val x = a.first + touch.dx - (bias?.dx ?: 0f)
        val y = a.second + touch.dy - (bias?.dy ?: 0f)
        val d = kotlin.math.hypot((x - b.first).toDouble(), (y - b.second).toDouble())
        return when {
            d < 0.75 -> 0.25
            d < 1.15 -> 0.55
            d < 1.6 -> 0.85
            else -> 1.1
        }
    }

    private fun lowerBound(key: String): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < key) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        /** Centre of each letter key on the QWERTY grid, in key widths / rows. */
        private val KEY_POS: Map<Char, Pair<Float, Float>> = buildMap {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i + 0.5f to 0f) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, i + 1.0f to 1f) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 2.0f to 2f) }
            // The number row, so a digit hit by mistake mid-word ("h5llo") can be fixed.
            "1234567890".forEachIndexed { i, c -> put(c, i + 0.5f to -1f) }
        }

        /** Keys next to each other on QWERTY. */
        private val NEIGHBOURS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg", 'y' to "tugh",
            'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol", 'a' to "qwsz", 's' to "awedxz",
            'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv", 'h' to "gyujnb", 'j' to "huikmn",
            'k' to "jiolm", 'l' to "kop", 'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb",
            'b' to "vghn", 'n' to "bhjm", 'm' to "njk",
            // Digits sit just above the top letter row.
            '1' to "qw", '2' to "qwe", '3' to "wer", '4' to "ert", '5' to "rty", '6' to "tyu",
            '7' to "yui", '8' to "uio", '9' to "iop", '0' to "op",
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

        private fun load(context: Context): EnglishDictionary =
            context.assets.open("en_words.txt").bufferedReader().useLines { fromLines(it) }

        /** Builds a dictionary from "word<TAB>frequency" lines (also used by unit tests). */
        internal fun fromLines(lines: Sequence<String>): EnglishDictionary {
            val map = HashMap<String, Pair<String, Int>>(100_000)
            lines.forEach { line ->
                val t = line.indexOf('\t')
                if (t > 0) {
                    val w = line.substring(0, t)
                    val key = w.lowercase()
                    if (key !in map) map[key] = w to (line.substring(t + 1).toIntOrNull() ?: 0)
                }
            }
            Log.d("EnglishDictionary", "Loaded ${map.size} words")
            return EnglishDictionary(map, map.keys.sorted().toTypedArray())
        }
    }
}
