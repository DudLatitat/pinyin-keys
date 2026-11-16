package com.pinyinkeys.ime

import android.content.Context

/**
 * Next-word prediction from the sentence so far, using the last one or two words:
 *  1. what YOU usually type after them (learned on this phone), then
 *  2. common English phrases (assets/en_phrases.txt, from Tatoeba, CC BY 2.0 FR),
 *  3. then very common starters.
 * "^" stands for the start of a sentence. What you type is learned only on this phone,
 * in the app's encrypted settings, and never in password/email/web-address fields.
 */
class NextWords(private val context: Context) {
    // context ("w1" or "w1 w2", lowercase) -> (next word -> count), learned from you
    private val learned = HashMap<String, HashMap<String, Int>>()
    // context -> up to 3 common next words, from the bundled phrase table
    private val phrases = HashMap<String, List<String>>(60_000)
    private var dirty = false
    /** How often you've typed each word (lowercase), for ranking your own words higher. */
    private val wordCounts = HashMap<String, Int>()

    /** Loads your learned data and (once) the phrase table. */
    fun load() {
        loadLearned()
        if (phrases.isEmpty()) loadPhrases()
    }

    /** Re-reads only what was learned from you (after the app's "learned" screen changed it). */
    fun loadLearned() {
        learned.clear()
        Settings(context).nextWordData.split('\n').forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) learned.getOrPut(parts[0]) { HashMap() }[parts[1]] = parts[2].toIntOrNull() ?: 1
        }
        wordCounts.clear()
        for ((ctx, m) in learned) if (' ' !in ctx) for ((w, c) in m) {
            wordCounts[w.lowercase()] = (wordCounts[w.lowercase()] ?: 0) + c
        }
    }

    private fun loadPhrases() {
        context.assets.open("en_phrases.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.indexOf('\t')
                if (t > 0) phrases[line.substring(0, t)] = line.substring(t + 1).split(' ')
            }
        }
    }

    /** [before] = the words before [next] in this sentence (empty at the start). */
    fun learn(before: List<String>, next: String) {
        if (next.isEmpty()) return
        for (ctx in contexts(before)) {
            val m = learned.getOrPut(ctx) { HashMap() }
            m[next] = (m[next] ?: 0) + 1
        }
        wordCounts[next.lowercase()] = (wordCounts[next.lowercase()] ?: 0) + 1
        dirty = true
    }

    /** Most likely next words after [before] (the sentence so far), best first. */
    fun predict(before: List<String>, n: Int = 3): List<String> {
        val out = LinkedHashSet<String>()
        val ctx = contexts(before)  // two-word context first, then one-word
        // Your own habits first (two-word context beats one-word), then common phrases.
        for (c in ctx) learned[c]?.entries?.sortedByDescending { it.value }?.forEach { out += it.key }
        for (c in ctx) phrases[c]?.let { out += it }
        out += DEFAULTS
        return out.take(n)
    }

    /**
     * Boosts for completing the word being typed: likely next words after [before] (from
     * your habits and the phrase table) and words you type often.
     */
    fun boosts(before: List<String>): Map<String, Double> {
        val out = HashMap<String, Double>()
        predict(before, 25).forEachIndexed { i, w -> out[w.lowercase()] = 90.0 - i * 3 }
        for ((w, c) in wordCounts) if (c >= 2) {
            out[w] = (out[w] ?: 0.0) + minOf(60.0, c * 6.0)
        }
        return out
    }

    /**
     * A two-word phrase you often type next ("my way" after "on"), or null. Needs each
     * step to have been typed at least 3 times.
     */
    fun phrase(before: List<String>): String? {
        val ctx = contexts(before)
        val w1 = ctx.firstNotNullOfOrNull { c -> learned[c]?.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key }
            ?: return null
        val after = contexts(before + w1)
        val w2 = after.firstNotNullOfOrNull { c -> learned[c]?.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key }
            ?: return null
        return "$w1 $w2"
    }

    /** Forget everything learned about [word]. */
    fun forget(word: String) {
        val w = word.lowercase()
        wordCounts.remove(w)
        learned.values.forEach { m -> m.keys.filter { it.lowercase() == w }.forEach { m.remove(it) } }
        learned.keys.filter { k -> k.split(' ').any { it == w } }.forEach { learned.remove(it) }
        dirty = true
    }

    /** How many times you've typed [word]. */
    fun count(word: String): Int = wordCounts[word.lowercase()] ?: 0

        /** "w1 w2" and "w2" for the last two words ("^" = start of sentence). */
    private fun contexts(before: List<String>): List<String> {
        val words = listOf("^") + before.map { it.lowercase() }
        val last = words.last()
        return if (words.size >= 2) listOf(words[words.size - 2] + " " + last, last) else listOf(last)
    }

    /** Saves the most-used learned pairs (a few thousand at most). */
    fun save() {
        if (!dirty) return
        dirty = false
        val lines = learned.flatMap { (p, m) -> m.map { (n, c) -> Triple(p, n, c) } }
            .sortedByDescending { it.third }.take(8000)
            .joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
        Settings(context).nextWordData = lines
    }

    companion object {
        private val DEFAULTS = listOf("I", "the", "you", "to", "and", "a")
    }
}
