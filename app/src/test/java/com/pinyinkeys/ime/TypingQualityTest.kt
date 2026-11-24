package com.pinyinkeys.ime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Measures autocorrect on made-up sentences typed by a simulated "fast, sloppy thumb":
 * each tap lands near the intended key with some scatter (so it sometimes hits a
 * neighbour), and now and then a letter is skipped, doubled or swapped. Reports how many
 * mistyped words get fixed and how many correctly typed words get wrongly changed, and
 * fails if either gets worse than the limits below. Run: gradle :app:testDebugUnitTest
 */
class TypingQualityTest {
    private val english by lazy {
        EnglishDictionary.fromLines(File("src/main/assets/en_words.txt").readLines().asSequence())
    }
    private val lm by lazy {
        NgramModel.fromBuffer(java.nio.ByteBuffer.wrap(File("src/main/assets/en_lm.bin").readBytes()))!!
    }
    /** Set to false to measure without the word-sequence model / key resizing. */
    var useLm = true
    var useTapResolve = System.getenv("NO_TAP") == null

    private val next by lazy {
        NextWords(android.content.ContextWrapper(null)).apply {
            loadPhrases(File("src/main/assets/en_phrases.txt").readLines().asSequence())
        }
    }

    private val sentences = listOf(
        "did you eat yet", "i miss you so much", "what are you doing tonight", "can you send me the file",
        "the weather is really nice today", "i will call you after work", "are you free this weekend",
        "thank you for the dinner", "i am going to the store now", "let me know when you get home",
        "how was your day", "i think we should leave early", "that sounds like a great idea",
        "do you want to watch a movie", "i forgot my phone at home", "see you tomorrow morning",
        "we need to buy some groceries", "my friends are coming over later", "please remember to bring water",
        "i really like this song", "could you help me with something", "it was nice to meet you",
        "the train is late again", "i just finished my homework", "where should we go for lunch",
        "happy birthday to your sister", "i love you more than anything", "sorry i was busy earlier",
        "what time does the store close", "have a good night and sleep well", "maybe we can try again next week",
        "this restaurant has amazing food", "i need to wake up early tomorrow", "tell me about your weekend",
        "i hope you feel better soon", "they said the meeting was cancelled", "we should probably talk about it",
        "my mother made dumplings for everyone", "the children are playing outside", "i can't wait to see you",
    )

    // Key centres in key widths / rows (same layout as EnglishDictionary).
    private val keyPos: Map<Char, Pair<Float, Float>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, i + 0.5f to 0f) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, i + 1.0f to 1f) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, i + 2.0f to 2f) }
    }

    /** Types [word] with scatter; returns what the keyboard got and where each tap was. */
    private fun sloppy(word: String, rnd: Random, scatter: Double): Pair<String, List<Touch?>> {
        val chars = word.toMutableList()
        // Occasional skipped, doubled or swapped letters (3% each per word).
        val r = rnd.nextDouble()
        if (chars.size > 3) when {
            r < 0.03 -> chars.removeAt(rnd.nextInt(1, chars.size))
            r < 0.06 -> { val i = rnd.nextInt(chars.size); chars.add(i, chars[i]) }
            r < 0.09 -> { val i = rnd.nextInt(chars.size - 1); val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t }
        }
        val out = StringBuilder()
        val touches = ArrayList<Touch?>()
        for (c in chars) {
            val p = keyPos[c]
            if (p == null) { out.append(c); touches += null; continue }
            val x = p.first + rnd.nextGaussian() * scatter
            val y = p.second + rnd.nextGaussian() * scatter * 0.8
            val hit = keyPos.minByOrNull { (_, q) -> (q.first - x) * (q.first - x) + (q.second - y) * (q.second - y) }!!
            out.append(hit.key)
            touches += Touch((x - hit.value.first).toFloat(), (y - hit.value.second).toFloat())
        }
        return out.toString() to touches
    }

    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-9); val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    data class Result(val typos: Int, val fixed: Int, val clean: Int, val broken: Int) {
        val fixRate get() = fixed.toDouble() / typos
        val breakRate get() = broken.toDouble() / clean
        /** Share of all words that end up right (what you actually see). */
        val accuracy get() = (clean - broken + fixed).toDouble() / (clean + typos)
    }

    /** Mistyped words that weren't fixed: typed -> result (meant). For tuning. */
    val misses = ArrayList<String>()

    fun measure(scatter: Double, seed: Int = 7, rounds: Int = 6): Result {
        val rnd = Random(seed)
        var typos = 0; var fixed = 0; var clean = 0; var broken = 0
        repeat(rounds) {
            for (s in sentences) {
                val before = ArrayList<String>()
                for (word in s.split(' ')) {
                    val (raw, touches) = sloppy(word, rnd, scatter)
                    // What the keyboard types, letter by letter, with key resizing.
                    val typed = if (!useTapResolve) raw else buildString {
                        raw.forEachIndexed { i, c -> append(touches[i]?.let { t -> english.resolveTap(toString(), c, t) } ?: c) }
                    }
                    val ctx = before.toList()
                    val model = if (useLm) { w: String -> lm.contextBoost(w, ctx) } else null
                    val fix = english.autocorrect(typed, touches, next.boosts(ctx), lm = model) ?: typed
                    val ok = fix.equals(word, ignoreCase = true)
                    if (typed == word) { clean++; if (!ok) { broken++; misses += "BROKE $typed -> $fix" } }
                    else { typos++; if (ok) fixed++ else misses += "$typed -> $fix ($word)" }
                    before += word
                    if (before.size > 2) before.removeAt(0)
                }
            }
        }
        return Result(typos, fixed, clean, broken)
    }

    @Test fun contextModel() {
        // "from" fits after "a letter", "form" after "fill in the".
        assertTrue(lm.contextBoost("from", listOf("letter")) > lm.contextBoost("form", listOf("letter")))
        assertTrue(lm.contextBoost("form", listOf("the")) > 0.0 || lm.contextBoost("form", listOf("this")) > 0.0)
    }

    @Test fun autocorrectQuality() {
        val careful = measure(scatter = 0.28)
        val fast = measure(scatter = 0.4)
        println("careful: $careful fix=${"%.3f".format(careful.fixRate)} break=${"%.3f".format(careful.breakRate)} acc=${"%.4f".format(careful.accuracy)}")
        if (System.getenv("SHOW_MISSES") != null) misses.take(80).forEach(::println)
        println("fast:    $fast fix=${"%.3f".format(fast.fixRate)} break=${"%.3f".format(fast.breakRate)} acc=${"%.4f".format(fast.accuracy)}")
        assertTrue("too few words right when typing fast: ${fast.accuracy}", fast.accuracy >= MIN_ACCURACY)
        assertTrue("changes too many correct words: ${careful.breakRate}", careful.breakRate <= MAX_BREAK_RATE)
    }

    /** Less common real words, typed exactly: autocorrect must leave every one alone. */
    @Test fun keepsRealWords() {
        val words = listOf(
            "tile", "tour", "dome", "dew", "leg", "sake", "gas", "thus", "seep", "tall", "bees", "gale",
            "fig", "hut", "cot", "lid", "mop", "pug", "rye", "sly", "vat", "wry", "yak", "zap", "nib",
            "moat", "lark", "fend", "jilt", "ruse", "veer", "whim", "yore", "brine", "crypt", "flint",
            "gauze", "knack", "plume", "quill", "shrug", "tryst", "vouch", "wrath", "ok", "yeah", "lol",
        )
        val centre = Touch(0f, 0f)
        val changed = words.filter { w ->
            english.autocorrect(w, List(w.length) { centre }, next.boosts(listOf("the")))?.let { !it.equals(w, true) } == true
        }
        println("real words changed: $changed")
        assertTrue("changed real words: $changed", changed.isEmpty())
    }

    /**
     * Real-world spelling mistakes (Wikipedia's common misspellings list, CC BY-SA 4.0):
     * how often autocorrect fixes them on space, and how often the right word is among
     * the 3 suggestions.
     */
    @Test fun commonMisspellings() {
        val pairs = javaClass.getResource("/misspellings.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.map { it[0] to it[1] }
        var fixed = 0; var top3 = 0
        val missed = ArrayList<String>()
        for ((typo, word) in pairs) {
            if (english.autocorrect(typo)?.equals(word, true) == true) fixed++ else if (missed.size < 60) missed += "$typo->${english.autocorrect(typo)}($word)"
            if (english.suggest(typo).any { it.equals(word, true) }) top3++
        }
        val fixRate = fixed.toDouble() / pairs.size
        val top3Rate = top3.toDouble() / pairs.size
        println("misspellings: ${pairs.size} fixed=${"%.3f".format(fixRate)} top3=${"%.3f".format(top3Rate)}")
        if (System.getenv("SHOW_MISSES") != null) println(missed)
        assertTrue("misspelling fix rate $fixRate", fixRate >= MIN_MISSPELLING_FIX)
        assertTrue("misspelling top-3 rate $top3Rate", top3Rate >= MIN_MISSPELLING_TOP3)
    }

    @Test fun predictsCommonNextWords() {
        val cases = listOf(
            listOf("how", "are") to "you", listOf("i", "miss") to "you", listOf("thank") to "you",
            listOf("see", "you") to "tomorrow", listOf("did", "you") to "know",
        )
        val hits = cases.count { (before, expected) -> expected in next.predict(before, 3).map { it.lowercase() } }
        println("next-word top-3 hits: $hits / ${cases.size}")
        assertTrue(hits >= cases.size - 1)
    }

    companion object {
        // Limits checked on every build (set a little below what the current version does).
        // Before 1.7.0 (old scoring): fast fix 0.55, careful break 0.003.
        // 1.7.0: fast fix 0.84, careful fix 0.90, break 0.000. 1.9.0 (context model): 0.87 / 0.93.
        const val MIN_ACCURACY = 0.915
        const val MAX_BREAK_RATE = 0.003
        const val MIN_MISSPELLING_FIX = 0.82
        const val MIN_MISSPELLING_TOP3 = 0.89
    }
}
