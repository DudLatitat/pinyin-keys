package com.pinyinkeys.ime

import android.content.Context

/**
 * Offline emoji search by name and keyword ("laugh" -> 😂 🤣 😆...).
 * Data: assets/emoji_names.tsv, from emojibase-data (MIT licence, based on Unicode CLDR).
 */
class EmojiSearch private constructor(private val entries: List<Entry>) {
    private class Entry(val emoji: String, val nameWords: List<String>, val tags: List<String>)

    // Word -> the first (most common) emoji named or tagged with it, for suggestions.
    private val byWord: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (e in entries) for (w in e.nameWords + e.tags) {
            if (w.length >= 3 && w !in NOT_HINTS && w.all { it.isLetter() }) m.putIfAbsent(w, e.emoji)
        }
        m
    }

    /** An emoji for a typed word ("love" -> 😍), or null. */
    fun forWord(word: String): String? {
        val w = word.lowercase()
        return byWord[w] ?: if (w.length > 4 && w.endsWith("s")) byWord[w.dropLast(1)] else null
    }

    /** Emoji matching every word of [query] (as a word start), best first. */
    fun search(query: String, limit: Int = 40): List<String> {
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.mapIndexedNotNull { i, e ->
            var score = 0
            for (q in words) {
                score += when {
                    e.tags.any { it == q } || e.nameWords.any { it == q } -> 3
                    e.nameWords.any { it.startsWith(q) } -> 2
                    e.tags.any { it.startsWith(q) } -> 1
                    else -> return@mapIndexedNotNull null
                }
            }
            // Ties keep the usual emoji order (common faces first).
            Triple(e.emoji, score, i)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            .take(limit).map { it.first }
    }

    companion object {
        /** Words in emoji names too general to suggest an emoji for. */
        private val NOT_HINTS = setOf(
            "face", "with", "and", "the", "person", "man", "woman", "people", "sign", "symbol", "mark",
            "button", "flag", "hand", "light", "medium", "dark", "skin", "tone", "open", "small", "large",
            "white", "black", "red", "blue", "green", "yellow", "orange", "purple", "brown", "grey", "gray",
            "square", "circle", "arrow", "left", "right", "down", "for", "you", "not", "new", "old", "top",
            "back", "end", "off", "free", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "time", "line", "letter", "first", "high", "low",
        )
        @Volatile var loaded: EmojiSearch? = null
            private set

        /** Loads once; call off the main thread. */
        fun get(context: Context): EmojiSearch = loaded ?: synchronized(this) {
            loaded ?: context.assets.open("emoji_names.tsv").bufferedReader().useLines { fromLines(it) }
                .also { loaded = it }
        }

        /** "emoji<TAB>name<TAB>tags" lines (also used by unit tests). */
        internal fun fromLines(lines: Sequence<String>): EmojiSearch = EmojiSearch(
            lines.filter { !it.startsWith("#") }.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null
                else Entry(p[0], p[1].lowercase().split(' ', '-', ':', ',').filter { it.isNotEmpty() },
                    p.getOrElse(2) { "" }.lowercase().split(' ').filter { it.isNotEmpty() })
            }.toList()
        )
    }
}
