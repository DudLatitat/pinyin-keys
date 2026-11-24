package com.pinyinkeys.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot

/**
 * Glide (swipe) typing: drag a finger across the letters of a word without lifting.
 *
 * Each letter key reports where it is on screen ([keyRects]); while a glide is going,
 * the finger's path is collected in [points] (also drawn as a trail over the keys).
 * When the finger lifts, [GlideDecoder] finds the words whose shape best fits the path.
 * Everything happens on the phone; the path is never stored.
 */
class GlideState {
    /** Letter -> its key's bounds, in the keyboard window's coordinates. */
    val keyRects = HashMap<Char, Rect>()
    /** The current glide's path (empty when not gliding). */
    val points = mutableStateListOf<Offset>()
    var active by mutableStateOf(false)
        private set

    /** Set by the keyboard: called when a glide starts / ends. */
    var onStart: () -> Unit = {}
    var onEnd: (List<Offset>, Map<Char, Rect>) -> Unit = { _, _ -> }

    fun start(p: Offset) { points.clear(); points += p; active = true; onStart() }
    fun add(p: Offset) { if (active) points += p }
    fun end() {
        if (!active) return
        active = false
        val path = points.toList()
        points.clear()
        onEnd(path, HashMap(keyRects))
    }
}

object GlideDecoder {
    private const val SAMPLES = 48

    /**
     * Up to [n] words for a glide [path], best first. Words must start and end near where
     * the finger started and stopped, and pass near each of their letters in order; then
     * the one whose ideal path (key centre to key centre) is closest to the real one wins,
     * with common words and sentence context ([boost]) breaking ties.
     */
    fun decode(
        path: List<Offset>,
        keys: Map<Char, Rect>,
        dict: EnglishDictionary,
        boost: Map<String, Double> = emptyMap(),
        n: Int = 4,
    ): List<String> {
        if (path.size < 2 || keys.size < 20) return emptyList()
        val keyW = keys.values.map { it.width }.average().toFloat()
        val centers = keys.mapValues { it.value.center }
        val user = resample(path, SAMPLES)
        val dense = resample(path, 96)
        fun near(p: Offset, r: Float) = centers.filter { dist(it.value, p) < r }.keys
        val starts = near(path.first(), keyW * 1.2f)
        val ends = near(path.last(), keyW * 1.2f)

        val scored = ArrayList<Pair<String, Double>>()
        dict.forEachWord { word, freq ->
            val w = word.lowercase().filter { it in 'a'..'z' }
            if (w.length < 2 || w[0] !in starts || w.last() !in ends) return@forEachWord
            // Double letters ("hello") are one stop on the path.
            val letters = StringBuilder().apply { w.forEach { if (isEmpty() || last() != it) append(it) } }.toString()
            if (letters.length < 2) return@forEachWord
            // Quick check: the path passes near every letter, in order.
            var i = 0
            for (c in letters) {
                val ctr = centers[c] ?: return@forEachWord
                while (i < dense.size && dist(dense[i], ctr) > keyW * 0.95f) i++
                if (i == dense.size) return@forEachWord
            }
            // Shape: average distance between the real path and the ideal one, in keys.
            val ideal = resample(letters.map { centers.getValue(it) }, SAMPLES)
            var sum = 0.0
            for (k in 0 until SAMPLES) sum += dist(user[k], ideal[k])
            val d = sum / SAMPLES / keyW
            scored += word to (-d * 3.0 + freq / 255.0 * 2.0 + (boost[word.lowercase()] ?: 0.0) * 0.3)
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(n)
    }

    /** [n] points evenly spaced along the path (so fast and slow glides compare equally). */
    fun resample(path: List<Offset>, n: Int): List<Offset> {
        if (path.size == 1) return List(n) { path[0] }
        val seg = DoubleArray(path.size)
        for (i in 1 until path.size) seg[i] = seg[i - 1] + dist(path[i - 1], path[i])
        val total = seg.last()
        if (total == 0.0) return List(n) { path[0] }
        val out = ArrayList<Offset>(n)
        var j = 1
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (j < path.size - 1 && seg[j] < target) j++
            val span = seg[j] - seg[j - 1]
            val t = if (span == 0.0) 0f else ((target - seg[j - 1]) / span).toFloat().coerceIn(0f, 1f)
            out += path[j - 1] + (path[j] - path[j - 1]) * t
        }
        return out
    }

    private fun dist(a: Offset, b: Offset) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
