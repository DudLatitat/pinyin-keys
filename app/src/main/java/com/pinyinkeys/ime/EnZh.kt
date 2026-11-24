package com.pinyinkeys.ime

import android.content.Context

/**
 * The everyday Chinese for common English words ("eat" -> 吃 chī), shown next to the
 * suggestions while you type, so you pick up vocabulary as you chat. Offline:
 * assets/en_zh.tsv, built from CC-CEDICT (CC BY-SA 4.0) by tools/build_en_zh.py.
 */
class EnZh private constructor(private val map: Map<String, Pair<String, String>>) {
    /** (characters, pinyin) for [word], also trying simple endings ("cats" -> cat). */
    fun lookup(word: String): Pair<String, String>? {
        val w = word.lowercase()
        map[w]?.let { return it }
        for (end in listOf("s", "es", "ed", "d", "ing")) {
            if (w.length > end.length + 2 && w.endsWith(end)) map[w.dropLast(end.length)]?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile private var loaded: EnZh? = null

        /** Loads once; call off the main thread. */
        fun get(context: Context): EnZh = loaded ?: synchronized(this) {
            loaded ?: context.assets.open("en_zh.tsv").bufferedReader().useLines { fromLines(it) }.also { loaded = it }
        }

        internal fun fromLines(lines: Sequence<String>) = EnZh(
            lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { it[0] to (it[1] to it[2]) } }.toMap()
        )
    }
}

/** What the suggestion bar shows next to the words: Chinese for the word and an emoji. */
data class WordHint(val hanzi: String?, val pinyin: String?, val emoji: String?)
