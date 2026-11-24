package com.pinyinkeys.ime

/**
 * Typos you fixed yourself (typo -> the word you meant), with how often and when.
 *
 * A fix is only used after you've made it [MIN_COUNT] times, so a one-off slip doesn't
 * become a rule. Unused fixes fade: after [FADE_DAYS] without use their count halves,
 * and they're dropped when it reaches 0. Undoing an applied fix removes it. Only the
 * [MAX_ENTRIES] most useful are kept. Stored in the app's encrypted settings, on the
 * phone only, as "typo<TAB>fix<TAB>count<TAB>day" lines. Pure Kotlin (unit-tested).
 */
class LearnedFixes(private val minCount: Int = MIN_COUNT, private val maxEntries: Int = MAX_ENTRIES) {
    private class Entry(var fix: String, var count: Int, var day: Int)

    private val entries = LinkedHashMap<String, Entry>()

    /** The fixes that are sure enough to use. */
    fun active(): Map<String, String> = entries.filterValues { it.count >= minCount }.mapValues { it.value.fix }

    val size get() = entries.size

    /** You fixed [typo] to [fix] again (today = days since 1970). */
    fun record(typo: String, fix: String, today: Int) {
        val e = entries[typo]
        if (e != null && e.fix == fix) { e.count++; e.day = today }
        else entries[typo] = Entry(fix, 1, today)  // a different fix starts over
        trim()
    }

    /** Applied or shown, so it's still useful: keeps it from fading. */
    fun used(typo: String, today: Int) { entries[typo]?.day = today }

    /** You undid this fix: forget it. */
    fun remove(typo: String) { entries.remove(typo) }

    /** Forget every fix to or from [word]. */
    fun forget(word: String) { entries.entries.removeAll { it.key == word || it.value.fix == word } }

    /** Halves the count of fixes unused for [FADE_DAYS] (once per period). Returns true if anything changed. */
    fun fade(today: Int): Boolean {
        var changed = false
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (today - e.day >= FADE_DAYS) {
                e.count /= 2; e.day = today; changed = true
                if (e.count == 0) it.remove()
            }
        }
        return changed
    }

    /** Keeps the most useful [maxEntries]: sure fixes first, then most used, then newest. */
    private fun trim() {
        if (entries.size <= maxEntries) return
        val keep = entries.entries.sortedWith(
            compareByDescending<Map.Entry<String, Entry>> { it.value.count >= minCount }
                .thenByDescending { it.value.count }.thenByDescending { it.value.day }
        ).take(maxEntries).map { it.key }.toSet()
        entries.keys.retainAll(keep)
    }

    fun lines(): List<String> = entries.map { (t, e) -> "$t\t${e.fix}\t${e.count}\t${e.day}" }

    companion object {
        const val MIN_COUNT = 2
        const val MAX_ENTRIES = 300
        const val FADE_DAYS = 60

        fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

        /**
         * Reads saved lines. Old "typo<TAB>fix" lines (before counts existed) are kept only
         * if [keep] says they're still worth it, and then count as sure fixes.
         */
        fun fromLines(lines: List<String>, today: Int, keep: (String, String) -> Boolean): LearnedFixes {
            val book = LearnedFixes()
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 2 || p[0].isEmpty() || p[1].isEmpty()) continue
                if (p.size >= 4) {
                    book.entries[p[0]] = Entry(p[1], p[2].toIntOrNull() ?: 1, p[3].toIntOrNull() ?: today)
                } else if (keep(p[0], p[1])) {
                    book.entries[p[0]] = Entry(p[1], MIN_COUNT, today)
                }
            }
            book.trim()
            return book
        }
    }
}
