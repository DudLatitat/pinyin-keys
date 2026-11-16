package com.pinyinkeys.ime

/**
 * Where on a key the last tap landed, as an offset from the key's centre in key sizes
 * (-0.5 = left/top edge, +0.5 = right/bottom edge). Autocorrect uses it: a tap on the
 * right edge of "p" was quite likely meant for "o". Kept in memory only.
 */
object TouchTracker {
    @Volatile var dx = 0f
    @Volatile var dy = 0f
}

/** One typed letter's tap offset (see [TouchTracker]). */
data class Touch(val dx: Float, val dy: Float)
