package com.pinyinkeys.ime

import android.content.Context

/**
 * Offline emoji search by name and keyword ("laugh" -> 😂 🤣 😆...).
 * Data: assets/emoji_names.tsv, from emojibase-data (MIT licence, based on Unicode CLDR).
 */
class EmojiSearch private constructor(private val entries: List<Entry>) {
    private class Entry(val emoji: String, val nameWords: List<String>, val tags: List<String>)

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
