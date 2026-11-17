package com.pinyinkeys.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Colours pinyin by tone so tones are easier to remember at a glance:
 * 1st red, 2nd orange, 3rd green, 4th blue, neutral grey (a common learner scheme).
 * Pure Kotlin (no Android calls) apart from the Compose colour types, so it's unit-tested.
 */
object ToneColors {
    private const val MARKS = "āēīōūǖ" + "áéíóúǘ" + "ǎěǐǒǔǚ" + "àèìòùǜ"

    /** Tone 1-4 of one syllable ("hǎo", "hao3"), or 5 for neutral / unknown. */
    fun toneOf(syllable: String): Int {
        for (c in syllable) {
            val i = MARKS.indexOf(c.lowercaseChar())
            if (i >= 0) return i / 6 + 1
        }
        val digit = syllable.lastOrNull()?.digitToIntOrNull()
        return if (digit != null && digit in 1..4) digit else 5
    }

    fun color(tone: Int, dark: Boolean): Color = if (dark) when (tone) {
        1 -> Color(0xFFFF6B6B); 2 -> Color(0xFFFFA94D); 3 -> Color(0xFF69DB7C); 4 -> Color(0xFF74A9FF)
        else -> Color(0xFFADB5BD)
    } else when (tone) {
        1 -> Color(0xFFD32F2F); 2 -> Color(0xFFE67700); 3 -> Color(0xFF2B8A3E); 4 -> Color(0xFF1C62D6)
        else -> Color(0xFF6C757D)
    }

    /**
     * Splits [pinyin] into words (letters, tone marks, ü and digits) and colours each by its
     * tone; spaces and punctuation keep the default colour. Words written together
     * ("nǐhǎo") are coloured by their first tone mark, which is good enough for chips.
     */
    fun colorize(pinyin: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < pinyin.length) {
            if (isPinyinChar(pinyin[i])) {
                val start = i
                while (i < pinyin.length && isPinyinChar(pinyin[i])) i++
                val word = pinyin.substring(start, i)
                withStyle(SpanStyle(color = color(toneOf(word), dark))) { append(word) }
            } else {
                append(pinyin[i]); i++
            }
        }
    }

    private fun isPinyinChar(c: Char) = c.isLetterOrDigit()
}
