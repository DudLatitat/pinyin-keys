package com.pinyinkeys.ime

import android.content.Context

/**
 * Learns which word you usually type after another (e.g. "see" -> "you"), to suggest
 * the next word after a space. Kept only on this phone, in the app's private, encrypted
 * settings; never learned in password or "incognito" fields.
 */
class NextWords(private val context: Context) {
    // previous word -> (next word -> count)
    private val counts = HashMap<String, HashMap<String, Int>>()
    private var dirty = false

    fun load() {
        counts.clear()
        Settings(context).nextWordData.split('\n').forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) counts.getOrPut(parts[0]) { HashMap() }[parts[1]] = parts[2].toIntOrNull() ?: 1
        }
    }

    fun learn(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        val m = counts.getOrPut(prev.lowercase()) { HashMap() }
        m[next] = (m[next] ?: 0) + 1
        dirty = true
    }

    /** Most likely next words after [prev]; common starters when nothing's learned yet. */
    fun predict(prev: String, n: Int = 3): List<String> {
        val learned = counts[prev.lowercase()]?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()
        return (learned + DEFAULTS).distinct().take(n)
    }

    /** Saves at most a few thousand pairs, keeping the most used. */
    fun save() {
        if (!dirty) return
        dirty = false
        val lines = counts.flatMap { (p, m) -> m.map { (n, c) -> Triple(p, n, c) } }
            .sortedByDescending { it.third }.take(5000)
            .joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
        Settings(context).nextWordData = lines
    }

    companion object {
        private val DEFAULTS = listOf("I", "the", "you", "to", "and", "a")
    }
}
