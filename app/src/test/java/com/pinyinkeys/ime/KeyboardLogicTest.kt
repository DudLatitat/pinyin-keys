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

    /** A QWERTY layout with 100-pixel keys, and a finger path through the given letters. */
    private val keys: Map<Char, androidx.compose.ui.geometry.Rect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f).forEachIndexed { row, (letters, indent) ->
            letters.forEachIndexed { i, c ->
                put(c, androidx.compose.ui.geometry.Rect(indent + i * 100f, row * 100f, indent + i * 100f + 100f, row * 100f + 100f))
            }
        }
    }
    private fun pathThrough(word: String) = word.map { keys.getValue(it).center }
        .zipWithNext { a, b -> (0 until 8).map { t -> a + (b - a) * (t / 8f) } }.flatten() + keys.getValue(word.last()).center

    @Test fun glideTyping() {
        assertEquals("the", GlideDecoder.decode(pathThrough("the"), keys, english).first().lowercase())
        assertTrue(GlideDecoder.decode(pathThrough("helo"), keys, english).any { it.lowercase() == "hello" })
        assertTrue(GlideDecoder.decode(pathThrough("good"), keys, english).any { it.lowercase() == "good" })
    }

    @Test fun emojiSearch() {
        val search = EmojiSearch.fromLines(File("src/main/assets/emoji_names.tsv").readLines().asSequence())
        assertTrue("😂" in search.search("laugh"))
        assertTrue("❤️" in search.search("red heart") || "❤" in search.search("red heart"))
        assertTrue(search.search("zzzqqq").isEmpty())
    }

    @Test fun numberRowSlips() {
        // One digit hit by mistake inside a word is fixed; real numbers are left alone.
        assertEquals("hello", english.autocorrect("h3llo"))
        assertNull(english.autocorrect("2nd"))
        assertNull(english.autocorrect("covid19"))
        assertTrue(english.suggest("h3llo").any { it == "hello" })
    }

    @Test fun preferredPickComesFirst() {
        english.preferred = mapOf("teh" to "tea")
        try {
            assertEquals("tea", english.suggest("teh").first())
            assertEquals("tea", english.autocorrect("teh"))
        } finally { english.preferred = emptyMap() }
    }

    @Test fun learnedFixesNeedTwoUses() {
        val book = LearnedFixes()
        book.record("drram", "dream", 100)
        assertTrue(book.active().isEmpty())          // once: not yet
        book.record("drram", "dream", 101)
        assertEquals("dream", book.active()["drram"]) // twice: used
        book.remove("drram")
        assertTrue(book.active().isEmpty())
    }

    @Test fun learnedFixesFade() {
        val book = LearnedFixes()
        repeat(2) { book.record("tetx", "text", 100) }
        book.fade(100 + LearnedFixes.FADE_DAYS)       // count 2 -> 1: no longer sure
        assertTrue(book.active().isEmpty())
        book.fade(100 + 2 * LearnedFixes.FADE_DAYS)   // 1 -> 0: gone
        assertEquals(0, book.size)
    }

    @Test fun oldListIsCleanedUp() {
        val book = LearnedFixes.fromLines(listOf("v\tso", "drram\tdream", "xq\tbanana"), 100) { t, f ->
            english.isPlausibleFix(t, f)
        }
        assertEquals(setOf("drram"), book.active().keys)
    }

    @Test fun plausibleFixes() {
        assertTrue(english.isPlausibleFix("drram", "dream"))
        assertTrue(english.isPlausibleFix("someth", "something"))
        assertTrue(!english.isPlausibleFix("v", "so"))
        assertTrue(!english.isPlausibleFix("cat", "elephant"))
    }

    @Test fun chineseForWords() {
        val zh = EnZh.fromLines(File("src/main/assets/en_zh.tsv").readLines().asSequence())
        assertEquals("吃", zh.lookup("eat")?.first)
        assertEquals("猫", zh.lookup("cats")?.first)
        assertNull(zh.lookup("the"))
    }

    @Test fun emojiForWords() {
        val search = EmojiSearch.fromLines(File("src/main/assets/emoji_names.tsv").readLines().asSequence())
        assertTrue(search.forWord("pizza") == "🍕")
        assertNull(search.forWord("face"))
    }

    @Test fun learnedCapitals() {
        english.capWords = setOf("lin")
        try {
            assertEquals("Lin", english.autocorrect("lin"))
            assertNull(english.autocorrect("Lin"))
        } finally { english.capWords = emptySet() }
    }

    @Test fun capitalI() {
        assertEquals("I'm", english.autocorrect("im"))
    }

    @Test fun editDistance() {
        assertEquals(2, TextUtil.editDistance("teh", "the"))
        assertEquals(1, TextUtil.editDistance("helo", "hello"))
        assertEquals(0, TextUtil.editDistance("same", "same"))
        assertEquals(3, TextUtil.editDistance("", "abc"))
    }
}
