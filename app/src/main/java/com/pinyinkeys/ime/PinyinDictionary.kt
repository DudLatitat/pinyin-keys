package com.pinyinkeys.ime

import android.content.Context
import android.util.Log

/** A suggestion: [word] to commit, which uses up the first [consumed] typed letters. */
data class Candidate(val word: String, val consumed: Int)

/**
 * Offline pinyin -> Chinese lookup for the Chinese typing mode.
 * Data: assets/pinyin_dict.txt (from AOSP PinyinIME, Apache 2.0; see DICTIONARY_NOTICE.txt),
 * one "pinyin<TAB>word<TAB>frequency" per line, sorted by pinyin, then most common first.
 * Everything is in memory (about 55k entries); nothing touches the network.
 */
class PinyinDictionary private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val freqs: IntArray,
) {
    /** pinyin -> index of its most common word (the file lists the most common first). */
    private val bestByKey = HashMap<String, Int>(keys.size).also { m ->
        for (i in keys.indices) m.putIfAbsent(keys[i], i)
    }

    /**
     * The whole input as one phrase: splits the letters into the most likely sequence of
     * dictionary words, e.g. "xiexiehahahaha" -> 谢谢 + 哈哈哈 + 哈 = "谢谢哈哈哈哈".
     * Each word scores log(frequency) minus a fixed cost (16, tuned on common phrases), so
     * fewer, longer, common words win.
     * Unfinished letters at the end are completed with the most common matching word.
     */
    private fun wholePhrase(typed: String): String? {
        val n = typed.length
        if (n < 4 || n > 60) return null
        val score = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val word = arrayOfNulls<String>(n + 1)
        score[0] = 0.0
        for (i in 0 until n) {
            if (score[i] == Double.NEGATIVE_INFINITY) continue
            for (j in i + 1..minOf(n, i + 18)) {
                val idx = bestByKey[typed.substring(i, j)] ?: continue
                val sc = score[i] + kotlin.math.ln(freqs[idx] + 1.0) - 16.0
                if (sc > score[j]) { score[j] = sc; from[j] = i; word[j] = words[idx] }
            }
        }
        // End point: the full input, or the furthest reachable point plus a completed tail.
        var end = n
        var tail = ""
        if (score[n] == Double.NEGATIVE_INFINITY) {
            end = (n downTo 1).firstOrNull { score[it] > Double.NEGATIVE_INFINITY } ?: return null
            tail = completions(typed.substring(end), 1).firstOrNull() ?: return null
        }
        val parts = ArrayList<String>()
        var k = end
        while (k > 0) { parts += word[k]!!; k = from[k] }
        parts.reverse()
        if (tail.isNotEmpty()) parts += tail
        return if (parts.size >= 2) parts.joinToString("") else null
    }

    /**
     * Suggestions for the letters typed so far, best first:
     * 1. words whose pinyin is exactly [typed] ("nihao" -> 你好),
     * 2. words whose pinyin starts with [typed] ("nih" -> 你好, 你会...),
     * 3. words for a leading part of [typed] ("woxiangni" -> 我想, 我...), which leave the
     *    rest of the letters to keep choosing from.
     */
    fun candidates(typed: String, limit: Int = 40): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fun add(c: Candidate) { if (out.size < limit && c.word !in out) out[c.word] = c }

        // Whole input as one phrase first (only when it needs more than one word).
        wholePhrase(typed)?.let { add(Candidate(it, typed.length)) }
        exact(typed).forEach { add(Candidate(it, typed.length)) }
        completions(typed, 12).forEach { add(Candidate(it, typed.length)) }
        for (len in typed.length - 1 downTo 1) {
            exact(typed.substring(0, len)).take(if (len == 1) 3 else 8).forEach { add(Candidate(it, len)) }
        }
        return out.values.toList()
    }

    /** Words with exactly this pinyin, most common first (the file is already in that order). */
    private fun exact(key: String): List<String> {
        var i = lowerBound(key)
        val res = mutableListOf<String>()
        while (i < keys.size && keys[i] == key) res += words[i++]
        return res
    }

    /** The [n] most common words whose pinyin starts with [prefix] but is longer. */
    private fun completions(prefix: String, n: Int): List<String> {
        val start = lowerBound(prefix)
        val end = lowerBound(prefix + '￿')
        val best = (start until end).filter { keys[it].length > prefix.length }
            .sortedByDescending { freqs[it] }.take(n)
        return best.map { words[it] }
    }

    /** First index whose key is >= [key] (binary search on the sorted keys). */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        @Volatile private var loaded: PinyinDictionary? = null

        /** Loads once (about half a second); call off the main thread. */
        fun get(context: Context): PinyinDictionary = loaded ?: synchronized(this) {
            loaded ?: load(context).also { loaded = it }
        }

        private fun load(context: Context): PinyinDictionary {
            val k = ArrayList<String>(56_000)
            val w = ArrayList<String>(56_000)
            val f = ArrayList<Int>(56_000)
            context.assets.open("pinyin_dict.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val a = line.indexOf('\t')
                    val b = line.indexOf('\t', a + 1)
                    if (a > 0 && b > a) {
                        k += line.substring(0, a)
                        w += line.substring(a + 1, b)
                        f += line.substring(b + 1).toIntOrNull() ?: 1
                    }
                }
            }
            Log.d("PinyinDictionary", "Loaded ${k.size} entries")
            return PinyinDictionary(k.toTypedArray(), w.toTypedArray(), f.toIntArray())
        }
    }
}
