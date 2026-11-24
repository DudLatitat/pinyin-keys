package com.pinyinkeys.ime

import android.content.Context
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * How likely a word is after the previous two ("context"), from counts of 1-, 2- and
 * 3-word sequences in everyday example sentences (assets/en_lm.bin, built from Tatoeba,
 * CC BY 2.0 FR, by tools/build_ngram_lm.py). Lets autocorrect pick "from" over "form" in
 * "a letter from home". Read straight from the app package (memory-mapped), so it costs
 * almost no memory, and it's only ever read: nothing you type is added to it.
 */
class NgramModel private constructor(private val buf: ByteBuffer) {
    private val total: Int = buf.getInt(8)
    private val n: Int = buf.getInt(12)
    private val keysAt = 16
    private val countsAt = 16 + n * 8

    /** How many times this exact word sequence ("a b c") was seen; 0 if never/rare. */
    fun count(ngram: String): Int {
        val key = fnv(ngram)
        var lo = 0; var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = buf.getLong(keysAt + mid * 8)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return buf.getInt(countsAt + mid * 4)
            }
        }
        return 0
    }

    /**
     * How much more (or less) likely [word] is after [before] than in general, in nats
     * (the same units as EnglishDictionary's scores). 0 when there's nothing to go on.
     * Uses "stupid backoff": the 3-word count if there is one, else the 2-word count
     * (discounted), else nothing.
     */
    fun contextBoost(word: String, before: List<String>): Double {
        val w = word.lowercase()
        val cw = count(w)
        if (cw == 0) return 0.0
        val ctx = (listOf("^", "^") + before.map { it.lowercase() }).takeLast(2)
        val (a, b) = ctx[0] to ctx[1]
        val pWord = cw.toDouble() / total
        val c3 = count("$a $b $w")
        val p = if (c3 > 0 && a != "^") c3.toDouble() / maxOf(1, count("$a $b")) else {
            val c2 = count("$b $w")
            if (c2 > 0) 0.4 * c2 / maxOf(1, count(b)) else 0.16 * pWord
        }
        return (kotlin.math.ln(p / pWord)).coerceIn(-1.5, 4.0)
    }

    companion object {
        @Volatile private var loaded: NgramModel? = null

        /**
         * Opens the model once; call off the main thread. The file is stored uncompressed
         * in the APK (see noCompress in build.gradle.kts) so it can be memory-mapped.
         */
        fun get(context: Context): NgramModel? = loaded ?: synchronized(this) {
            loaded ?: runCatching {
                context.assets.openFd("en_lm.bin").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                        fromBuffer(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
                    }
                }
            }.getOrNull()?.also { loaded = it }
        }

        /** From the file's bytes (also used by unit tests). */
        fun fromBuffer(buf: ByteBuffer): NgramModel? {
            if (buf.capacity() < 16 || buf.get(0) != 'P'.code.toByte() || buf.get(3) != 'M'.code.toByte()) return null
            return NgramModel(buf)
        }

        /** FNV-1a 64-bit hash of the UTF-8 text (must match tools/build_ngram_lm.py). */
        fun fnv(s: String): Long {
            var h = -0x340d631b7bdddcdbL  // 0xcbf29ce484222325
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
