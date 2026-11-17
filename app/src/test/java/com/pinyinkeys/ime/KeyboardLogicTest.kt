package com.pinyinkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the typing logic with the real word lists from the app's assets.
 * Run with: gradle :app:testDebugUnitTest (CI runs it before every build).
 */
class KeyboardLogicTest {
    companion object {
        // Unit tests run from the app/ folder, so the assets are at this relative path.
        val english by lazy { EnglishDictionary.fromLines(File("src/main/assets/en_words.txt").readLines().asSequence()) }
        val pinyin by lazy { PinyinDictionary.fromLines(File("src/main/assets/pinyin_dict.txt").readLines().asSequence()) }
    }

    @Test fun fixesCommonTypos() {
        assertEquals("the", english.autocorrect("teh"))
        assertEquals("aren't", english.autocorrect("arent"))
        assertEquals("I", english.autocorrect("i"))
    }

    @Test fun leavesRealWordsAlone() {
        assertNull(english.autocorrect("hello"))
        assertNull(english.autocorrect("you"))
    }

    @Test fun userWordsAreNeverCorrected() {
        english.userWords = setOf("teh")
        try { assertNull(english.autocorrect("teh")) } finally { english.userWords = emptySet() }
    }

    @Test fun keepsCapitals() {
        assertEquals("The", english.autocorrect("Teh"))
    }

    @Test fun suggestsCompletions() {
        assertTrue(english.suggest("tomo", 3).any { it.equals("tomorrow", ignoreCase = true) })
    }

    @Test fun pinyinWholePhraseFirst() {
        assertEquals("谢谢", pinyin.candidates("xiexie").first().word)
        assertEquals("谢谢哈哈哈哈", pinyin.candidates("xiexiehahahaha").first().word)
    }

    @Test fun practiceMatching() {
        assertTrue(Practice.matches("ni", "ni3", strictTones = false))
        assertTrue(Practice.matches("ni3", "ni3", strictTones = true))
        assertTrue(!Practice.matches("ni", "ni3", strictTones = true))
        assertTrue(Practice.matches("nu", "nü3", strictTones = false))
        assertEquals("xiǎng", Practice.toToneMarks("xiang3"))
        assertEquals("nǚ", Practice.toToneMarks("nv3"))
    }

    @Test fun toneDetection() {
        assertEquals(1, ToneColors.toneOf("mā"))
        assertEquals(2, ToneColors.toneOf("má"))
        assertEquals(3, ToneColors.toneOf("hǎo"))
        assertEquals(4, ToneColors.toneOf("shì"))
        assertEquals(5, ToneColors.toneOf("le"))
        assertEquals(3, ToneColors.toneOf("nǚ"))
        assertEquals(3, ToneColors.toneOf("ni3"))
        assertEquals("nǐ hǎo!", ToneColors.colorize("nǐ hǎo!", dark = false).text)
    }

    @Test fun editDistance() {
        assertEquals(2, TextUtil.editDistance("teh", "the"))
        assertEquals(1, TextUtil.editDistance("helo", "hello"))
        assertEquals(0, TextUtil.editDistance("same", "same"))
        assertEquals(3, TextUtil.editDistance("", "abc"))
    }
}
