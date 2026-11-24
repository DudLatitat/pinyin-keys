package com.pinyinkeys.ime

import android.content.Context
import android.util.Log

/**
 * Offline English word list for suggestions and autocorrect.
 * Data: assets/en_words.txt (words from AOSP LatinIME, Apache 2.0; frequencies from
 * wordfreq, CC BY-SA 4.0; see DICTIONARY_NOTICE.txt), "word<TAB>frequency" per line, most common first. Everything stays in memory.
 */
class EnglishDictionary private constructor(
    /** lowercase word -> (word as written, frequency 0-255) */
    private val byLower: HashMap<String, Pair<String, Int>>,
    /** all lowercase words, sorted, for prefix search */
    private val sorted: Array<String>,
) {
    /** Words you always write with a capital (names...), lowercase; learned on the phone. */
    @Volatile var capWords: Set<String> = emptySet()

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

    /*
     * How words are scored ("noisy channel", like other keyboards): every candidate gets
     *   log-likelihood of the word itself (how common it is, [logP])
     * + how well it fits the sentence so far ([boost], from NextWords; in the same units)
     * - how unlikely your taps are if you meant it ([COST] x the edit cost below).
     * Units are "nats" (natural log); +1 nat means about 2.7x more likely.
     */

    /** How common [w] is, in nats. The file's 0-255 column is (zipf - 1) x 40. */
    private fun logP(lower: String): Double =
        (byLower[lower]?.second ?: if (lower in userWords) USER_WORD_FREQ else 0).toDouble() / FREQ_PER_NAT

    private fun written(lower: String) =
        if (lower in capWords) lower.replaceFirstChar { it.uppercaseChar() } else byLower[lower]?.first ?: lower

    /**
     * Up to [n] suggestions for a partly typed [typed] word, best first: your own past
     * fixes and picks, likely corrections (using where you actually tapped, [touches]),
     * completions and word splits ("ofthe" -> "of the"), ranked as described above.
     */
    fun suggest(
        typed: String,
        n: Int = 3,
        boost: Map<String, Double> = emptyMap(),
        touches: List<Touch?> = emptyList(),
        lm: ((String) -> Double)? = null,
    ): List<String> {
        if (typed.isEmpty() || !isWordWithSlip(typed)) return emptyList()
        val lower = typed.lowercase()
        val scores = HashMap<String, Double>()
        fun ctx(w: String) = (boost[w] ?: 0.0) + (lm?.invoke(w) ?: 0.0) * LM_WEIGHT
        fun consider(w: String, score: Double) {
            if (score > (scores[w] ?: Double.NEGATIVE_INFINITY)) scores[w] = score
        }
        preferred[lower]?.let { consider(it, logP(it) + 8.0 + ctx(it)) }     // the word you picked last time
        learnedFixes[lower]?.let { consider(it, logP(it) + 6.0 + ctx(it)) }  // your own past fix
        if (lower in byLower || lower in userWords) consider(lower, logP(lower) + 2.5 + ctx(lower))
        if (lower in userWords) consider(lower, 20.0)
        apostropheFix(lower)?.let { consider(it, logP(it) + 3.0) }  // "arent" -> "aren't"
        // Completions: words starting with what's typed (a small cost per missing letter).
        var i = lowerBound(lower)
        var seen = 0
        while (i < sorted.size && sorted[i].startsWith(lower) && seen < 3000) {
            val w = sorted[i]
            if (w != lower) consider(w, logP(w) - COMPLETION_COST * (w.length - lower.length) + ctx(w))
            i++; seen++
        }
        // Corrections: one or two slips away, judged by where you tapped.
        if (lower.length >= 2) {
            for ((w, cost) in corrections(lower, touches)) {
                if (w in byLower) consider(w, logP(w) - cost * COST + ctx(w))
            }
            if (lower !in byLower) bestSplit(lower, boost)?.let { (w, s) -> consider(w, s) }
            if (lower !in byLower && lower.length >= 5) spatialMatch(lower, touches)?.let { (w, cost) ->
                consider(w, logP(w) - cost * COST + ctx(w))
            }
        }
        // Words that fit the sentence (or that you use) but weren't reached by the scan.
        for ((w, b) in boost) {
            if (w.startsWith(lower) && w != lower && w !in scores) {
                consider(w, logP(w).coerceAtLeast(USER_WORD_FREQ / FREQ_PER_NAT) - COMPLETION_COST * (w.length - lower.length) + b)
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(n)
            .map { matchCase(written(it.key), typed) }
    }

    /**
     * The word to autocorrect [typed] to when space is pressed, or null to leave it.
     * Order: your own past fix or pick for this typo; a missing apostrophe; then the best
     * candidate one or two slips away (or two words run together), if it's clearly more
     * likely. A real word you typed is only replaced on very strong evidence.
     */
    fun autocorrect(
        typed: String,
        touches: List<Touch?> = emptyList(),
        boost: Map<String, Double> = emptyMap(),
        useLearned: Boolean = true,
        lm: ((String) -> Double)? = null,
    ): String? {
        val lower = typed.lowercase()
        // A name you always capitalise, typed in lowercase: capitalise it.
        if (typed == lower && lower in capWords) return written(lower)
        if (lower in userWords) return null
        if (useLearned) {
            // A misspelling you corrected by picking a word before: use that word.
            if (lower !in byLower) preferred[lower]?.let { return matchCase(written(it), typed) }
            learnedFixes[lower]?.let { return matchCase(written(it), typed) }
        }
        // Keep the dictionary's capitals: "im" -> "I'm".
        if (lower !in byLower) apostropheFix(lower)?.let { return matchCase(written(it), typed) }
        if (typed == "i") return "I"
        // A single letter other than "a"/"I", tapped right next to I or A: you meant that.
        if (lower.length == 1 && lower[0] !in "ai") {
            val t = touches.firstOrNull() ?: return null
            return when {
                subCost(lower[0], 'i', t) <= 0.35 -> "I"
                subCost(lower[0], 'a', t) <= 0.35 -> matchCase("a", typed)
                else -> null
            }
        }
        // "i'm", "i'll", "i've", "i'd" typed with the apostrophe: capital I.
        if (lower.startsWith("i'") && typed.startsWith("i") && lower in byLower) return "I" + typed.substring(1)
        if (lower.length < 2 || !isWordWithSlip(typed)) return null
        fun ctx(w: String) = (boost[w] ?: 0.0) + (lm?.invoke(w) ?: 0.0) * LM_WEIGHT
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCost = 0.0
        for ((w, cost) in corrections(lower, touches)) {
            val f = byLower[w]?.second ?: continue
            if (f < MIN_FIX_FREQ) continue  // never "fix" to a very rare word
            val score = logP(w) - cost * COST + ctx(w)
            if (score > bestScore) { best = w; bestScore = score; bestCost = cost }
        }
        val known = lower in byLower
        // A word you stopped typing early ("someth" -> "something"): up to 4 letters more.
        if (!known && lower.length >= 3) {
            var i = lowerBound(lower)
            var seen = 0
            while (i < sorted.size && sorted[i].startsWith(lower) && seen < 3000) {
                val w = sorted[i]; i++; seen++
                val missing = w.length - lower.length
                if (missing > 4 || (byLower[w]?.second ?: 0) < MIN_FIX_FREQ) continue
                val cost = 0.25 * missing
                val score = logP(w) - cost * COST + ctx(w)
                if (score > bestScore) { best = w; bestScore = score; bestCost = cost }
            }
        }
        // Several slips onto neighbouring keys ("domerhinf" -> "something"): compare with
        // words of the same length letter by letter.
        if (!known && lower.length >= 5) spatialMatch(lower, touches)?.let { (w, cost) ->
            val score = logP(w) - cost * COST + ctx(w)
            if (score > bestScore) { best = w; bestScore = score; bestCost = minOf(cost, MAX_FIX_COST) }
        }
        if (!known) bestSplit(lower, boost)?.let { (w, s) -> if (s > bestScore) { best = w; bestScore = s; bestCost = 1.0 } }
        val pick = best ?: return null
        return if (known) {
            // You typed a real word: replace it only for a single cheap slip that's far
            // more likely here ("teh" -> "the"), never for a word that's merely common.
            val keep = logP(lower) + ctx(lower) + KEEP_BONUS
            if (bestCost <= 0.6 && bestScore - keep >= REAL_WORD_MARGIN) matchCase(written(pick), typed) else null
        } else {
            if (bestCost > MAX_FIX_COST) null else matchCase(if (' ' in pick) pick else written(pick), typed)
        }
    }

    /**
     * The best same-length word where every typed letter is the intended key or one next
     * to it, with the summed slip cost (at most a quarter per letter plus a half), or null.
     */
    private fun spatialMatch(lower: String, touches: List<Touch?>): Pair<String, Double>? {
        val limit = 0.5 + 0.25 * lower.length
        var best: Pair<String, Double>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((key, entry) in byLower) {
            if (key.length != lower.length || entry.second < MIN_FIX_FREQ) continue
            var cost = 0.0
            for (i in key.indices) {
                val c = key[i]; val t = lower[i]
                if (c == t) continue
                if (c !in (NEIGHBOURS[t] ?: "")) { cost = Double.MAX_VALUE; break }
                cost += subCost(t, c, touches.getOrNull(i))
                if (cost > limit) break
            }
            if (cost > limit) continue
            val score = logP(key) - cost * COST
            if (score > bestScore) { bestScore = score; best = key to cost }
        }
        return best
    }

    /**
     * Invisible key resizing: a tap near the edge between two keys becomes the letter that
     * makes a more likely word. [prefix] is the word typed so far (lowercase letters),
     * [typed] the key that was hit and [touch] where on it. A tap near the key's centre
     * always stays that key; elsewhere the spot (how far from each key) and the words the
     * letter would lead to are weighed together.
     */
    fun resolveTap(prefix: String, typed: Char, touch: Touch): Char {
        val pos = KEY_POS[typed] ?: return typed
        if (typed !in 'a'..'z' || prefix.any { it !in 'a'..'z' && it != '\'' }) return typed
        val bias = touchBias[typed]
        val x = pos.first + touch.dx - (bias?.dx ?: 0f)
        val y = pos.second + touch.dy - (bias?.dy ?: 0f)
        fun dist2(c: Char): Double {
            val p = KEY_POS.getValue(c)
            val dx = (x - p.first).toDouble(); val dy = ((y - p.second) * 0.85f).toDouble()
            return dx * dx + dy * dy
        }
        if (dist2(typed) < CENTRE * CENTRE) return typed
        fun score(c: Char) = -dist2(c) / (2 * TAP_SIGMA * TAP_SIGMA) + TAP_PRIOR * kotlin.math.ln(prefixMass(prefix + c) + 1e-3)
        var best = typed
        var bestScore = score(typed)
        for (c in NEIGHBOURS[typed] ?: "") {
            if (c !in 'a'..'z' || dist2(c) > 1.0) continue
            val sc = score(c)
            if (sc > bestScore + TAP_MARGIN) { best = c; bestScore = sc }
        }
        return best
    }

    // Cached "how much of the language starts with this?" (sum of word probabilities).
    private val massCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private fun prefixMass(p: String): Double {
        massCache[p]?.let { return it }
        var sum = 0.0
        var i = lowerBound(p)
        var seen = 0
        while (i < sorted.size && sorted[i].startsWith(p) && seen < 20000) {
            sum += kotlin.math.exp(logP(sorted[i]) - 14.0); i++; seen++
        }
        if (massCache.size > 20000) massCache.clear()
        massCache[p] = sum
        return sum
    }

    /**
     * Is [fix] a believable correction of [typo] (worth learning)? It must be a real word
     * reachable by a couple of likely slips, or a completion of a few letters. Rules out
     * one-letter "typos" and unrelated swaps like "v" -> "so".
     */
    fun isPlausibleFix(typo: String, fix: String): Boolean {
        if (typo.length < 2 || typo == fix || !isWord(fix)) return false
        if (fix.startsWith(typo) && fix.length - typo.length <= 4) return true
        val cost = corrections(typo, emptyList()).firstOrNull { it.first == fix }?.second ?: return false
        return cost <= MAX_LEARN_COST
    }

    /** True if autocorrect already turns [typo] into [fix] without being taught. */
    fun fixesOnItsOwn(typo: String, fix: String): Boolean =
        autocorrect(typo, useLearned = false)?.equals(fix, ignoreCase = true) == true

    /**
     * Two words run together or with a slip instead of space: "ofthe" -> "of the",
     * "thebcat" -> "the cat" (b/n/v/c/m are next to the space bar). Returns the best
     * split and its score, or null.
     */
    private fun bestSplit(lower: String, boost: Map<String, Double>): Pair<String, Double>? {
        if (lower.length < 4 || '\'' in lower) return null
        // Both halves must be common words (short ones very common), so rare fragments
        // like "ion" or "sailing" + "a" don't win over a real correction.
        fun ok(w: String): Boolean {
            val f = byLower[w]?.second ?: return false
            return when {
                w.length == 1 -> w == "a" || w == "i"
                w.length <= 3 -> f >= 170
                else -> f >= 100
            }
        }
        var best: Pair<String, Double>? = null
        fun offer(l: String, r: String, penalty: Double) {
            if (!ok(l) || !ok(r)) return
            // Two words are less likely than one: a word's real probability is
            // logP - SECOND_WORD (zipf counts per billion words), so the pair scores this.
            val s = logP(l) + logP(r) - SECOND_WORD - penalty + (boost[l] ?: 0.0)
            val words = written(l) + " " + written(r)
            if (best == null || s > best!!.second) best = words to s
        }
        for (i in 1 until lower.length) {
            offer(lower.substring(0, i), lower.substring(i), SPLIT_COST)                  // missed space
            if (i < lower.length - 1 && lower[i] in "cvbnm")
                offer(lower.substring(0, i), lower.substring(i + 1), SPLIT_COST + 1.0)   // hit b/n/... for space
        }
        return best
    }

    /**
     * Candidate words and how costly the slips are that turn them into what was typed:
     * one slip, plus two slips (only cheap ones for the second) for words of 3+ letters.
     */
    private fun corrections(w: String, touches: List<Touch?>): List<Pair<String, Double>> {
        val best = HashMap<String, Double>(w.length * 120)
        fun add(c: String, cost: Double) { if (cost < (best[c] ?: Double.MAX_VALUE)) best[c] = cost }
        val one = edits(w, touches, cheapOnly = false)
        for ((c, cost) in one) add(c, cost)
        if (w.length >= 3) {
            for ((c1, cost1) in one) {
                if (cost1 > 0.9) continue
                // Touch positions only line up for edits that keep the length.
                val t = if (c1.length == w.length) touches else emptyList()
                for ((c2, cost2) in edits(c1, t, cheapOnly = true)) add(c2, cost1 + cost2 + 0.3)
            }
        }
        return best.entries.map { it.key to it.value }
    }

    /**
     * Words one edit away from [w], with the edit's cost (lower = a more likely slip):
     * missed letter (typed "helo") 0.3 if it's a double letter, else 0.6; extra letter
     * 0.35 if doubled ("helllo"), 0.6 next to a neighbour key, else 0.9; swapped letters
     * 0.5; wrong key by [subCost]. [cheapOnly] keeps just the likely slips.
     */
    private fun edits(w: String, touches: List<Touch?> = emptyList(), cheapOnly: Boolean): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(w.length * 60)
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        for (i in w.indices) {
            // An extra letter typed: drop it.
            val extra = when {
                i > 0 && w[i] == w[i - 1] -> 0.35
                (i > 0 && w[i] in (NEIGHBOURS[w[i - 1]] ?: "")) || (i + 1 < w.length && w[i] in (NEIGHBOURS[w[i + 1]] ?: "")) -> 0.6
                else -> 0.9
            }
            if (!cheapOnly || extra <= 0.6) out += (w.removeRange(i, i + 1) to extra)
            if (i + 1 < w.length && w[i] != w[i + 1]) out += (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) to 0.5)
            // A wrong key.
            val subs = if (cheapOnly) (NEIGHBOURS[w[i]] ?: "") else letters
            for (c in subs) if (c != w[i]) {
                out += (w.substring(0, i) + c + w.substring(i + 1) to subCost(w[i], c, touches.getOrNull(i)))
            }
        }
        // A missed letter: put it back.
        for (i in 0..w.length) {
            if (cheapOnly) {
                if (i > 0) out += (w.substring(0, i) + w[i - 1] + w.substring(i) to 0.3)  // double letter
                continue
            }
            for (c in letters) {
                val cost = when {
                    c == '\'' -> 0.2
                    (i > 0 && c == w[i - 1]) || (i < w.length && c == w[i]) -> 0.3
                    else -> 0.6
                }
                out += (w.substring(0, i) + c + w.substring(i) to cost)
            }
        }
        return out
    }

    /**
     * How unlikely it is that you meant [meant] when the keyboard got [typed]. With the
     * tap position we measure how far your finger was from [meant]'s key (smoothly: the
     * farther, the less likely); without it, neighbouring keys count as likely slips.
     */
    private fun subCost(typed: Char, meant: Char, touch: Touch?): Double {
        val a = KEY_POS[typed] ?: return 1.0
        val b = KEY_POS[meant] ?: return 1.0
        if (touch == null) return if (meant in (NEIGHBOURS[typed] ?: "")) 0.6 else 1.1
        val bias = touchBias[typed]
        val x = a.first + touch.dx - (bias?.dx ?: 0f)
        // Keys are taller than wide, so a vertical miss of one row counts a bit less.
        val y = (a.second + touch.dy - (bias?.dy ?: 0f)) * 0.85f
        val d = kotlin.math.hypot((x - b.first).toDouble(), (y - b.second * 0.85f).toDouble())
        return (0.15 + (d - 0.5).coerceAtLeast(0.0) * 0.9).coerceAtMost(1.3)
    }

    private fun lowerBound(key: String): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < key) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        // Scoring constants (see the comment above suggest()); tuned with KeyboardLogicTest.
        /** The frequency column is (zipf - 1) x 40, and one zipf step is ln(10) = 2.3 nats. */
        private const val FREQ_PER_NAT = 40.0 / 2.302585
        /** Nats per unit of edit cost (cost 1.0 = about 1 in 90). */
        private const val COST = 4.5
        /** Per letter still to type in a completion. */
        private const val COMPLETION_COST = 0.12
        /** Your own words count as moderately common (zipf 4). */
        private const val USER_WORD_FREQ = 120
        /** Fix only to words at least this common (zipf 1.75), and up to this total edit cost. */
        private const val MIN_FIX_FREQ = 30
        private const val MAX_FIX_COST = 1.6
        /** Fixes costlier than this aren't learned (too unlike a slip). */
        private const val MAX_LEARN_COST = 2.0
        /** How much the word-sequence model's context counts (see NgramModel). */
        private const val LM_WEIGHT = 1.0
        /** Key resizing: taps this close to a key's centre (in key widths) always count. */
        private const val CENTRE = 0.3
        /** Key resizing: how far taps scatter (key widths), how much words count, and how
         *  much better the other letter must be. */
        private const val TAP_SIGMA = 0.35
        private const val TAP_PRIOR = 0.5
        private const val TAP_MARGIN = 1.5
        /** A real word you typed is kept unless a slip-away word is this much more likely. */
        private const val KEEP_BONUS = 1.0
        private const val REAL_WORD_MARGIN = 3.5
        /** Splitting one typed word into two. */
        private const val SPLIT_COST = 2.5
        /** ln(10^8): converts the second word's frequency into a real probability. */
        private const val SECOND_WORD = 8 * 2.302585

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
