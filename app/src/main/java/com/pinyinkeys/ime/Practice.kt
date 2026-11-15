package com.pinyinkeys.ime

import com.pinyinkeys.ime.translate.Sentence
import com.pinyinkeys.ime.translate.SentenceDetails

/** One thing to commit: a character to earn by typing [syllable], or punctuation (syllable = null). */
data class PracticeUnit(val text: String, val syllable: String?)

/** Assist (show the Chinese) or Practice (type it yourself in pinyin). */
enum class Mode { Assist, Practice }

/** The Practice panel's state. Plain data: every change makes a new copy, and Compose redraws. */
sealed interface PracticeState {
    data object Idle : PracticeState
    data object Loading : PracticeState
    data class Error(val message: String) : PracticeState
    data class Active(
        val sentences: List<Sentence>,
        /** units[i] are the characters/punctuation of sentence i. */
        val units: List<List<PracticeUnit>>,
        val sentenceIndex: Int = 0,
        val unitIndex: Int = 0,
        /** Pinyin typed so far for the current character. */
        val buffer: String = "",
        val hintShown: Boolean = false,
    ) : PracticeState {
        val current: PracticeUnit? get() = units.getOrNull(sentenceIndex)?.getOrNull(unitIndex)
        val done: Boolean get() = current == null
        val doneInSentence: Int get() = units.getOrNull(sentenceIndex)?.take(unitIndex)?.count { it.syllable != null } ?: 0
        val totalInSentence: Int get() = units.getOrNull(sentenceIndex)?.count { it.syllable != null } ?: 0
    }
}

object Practice {
    /**
     * Pairs each Chinese character with its syllable. Everything that isn't a Chinese
     * character (punctuation, emoji, digits) becomes an automatic unit.
     * Returns null if the counts don't line up, so we never ask for the wrong syllable.
     */
    fun buildUnits(sentence: Sentence, details: SentenceDetails): List<PracticeUnit>? {
        val units = mutableListOf<PracticeUnit>()
        var s = 0
        var i = 0
        val text = sentence.hanzi
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                val syl = details.syllables.getOrNull(s++) ?: return null
                units += PracticeUnit(ch, syl.lowercase().trim())
            } else {
                units += PracticeUnit(ch, null)
            }
            i += Character.charCount(cp)
        }
        return if (s == details.syllables.size) units else null
    }

    /** "ni3" -> "ni" etc. Also accepts u for ü, since there's no ü key. */
    private fun variants(syllable: String, strictTones: Boolean): Set<String> {
        val base = syllable.replace("ü", "v").replace("u:", "v")
        val noTone = base.trimEnd { it.isDigit() }
        val tone = base.removePrefix(noTone)
        // Neutral tone (5) may be typed as 5 or with no number at all.
        val forms = if (!strictTones) setOf(noTone)
        else if (tone == "5" || tone.isEmpty()) setOf(noTone + "5", noTone) else setOf(base)
        return forms.flatMap { listOf(it, it.replace('v', 'u')) }.toSet()
    }

    fun matches(buffer: String, syllable: String, strictTones: Boolean) =
        buffer.lowercase() in variants(syllable, strictTones)

    /** True while [buffer] could still become a match (so we don't show it as wrong). */
    fun isPrefix(buffer: String, syllable: String, strictTones: Boolean) =
        variants(syllable, strictTones).any { it.startsWith(buffer.lowercase()) }

    /** "nv3" -> "nǚ", "xiang3" -> "xiǎng", "le5" -> "le". For the hint. */
    fun toToneMarks(syllable: String): String {
        val s = syllable.lowercase().replace("v", "ü").replace("u:", "ü")
        val tone = s.lastOrNull()?.digitToIntOrNull() ?: return s
        val letters = s.dropLast(1)
        if (tone !in 1..4) return letters
        val marks = mapOf(
            'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì",
            'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        )
        // Standard rule: a or e takes the mark; in "ou" the o does; otherwise the last vowel.
        val idx = when {
            'a' in letters -> letters.indexOf('a')
            'e' in letters -> letters.indexOf('e')
            "ou" in letters -> letters.indexOf('o')
            else -> letters.indexOfLast { it in marks }
        }
        if (idx < 0) return letters
        return letters.substring(0, idx) + marks.getValue(letters[idx])[tone - 1] + letters.substring(idx + 1)
    }
}
