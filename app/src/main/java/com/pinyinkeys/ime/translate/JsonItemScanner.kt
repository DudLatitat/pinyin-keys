package com.pinyinkeys.ime.translate

/**
 * Finds complete items of the "sentences" array while the JSON is still arriving,
 * so each sentence card can be shown as soon as its text is done.
 *
 * Feed it the growing reply with [append]; it returns any newly finished items as
 * JSON strings. It only tracks braces and strings, which is all it needs for our
 * fixed shape: {"sentences":[{...},{...}]} (items are objects at depth 2).
 */
class JsonItemScanner {
    private val buf = StringBuilder()
    private var pos = 0
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var itemStart = -1

    fun append(chunk: String): List<String> {
        buf.append(chunk)
        val done = mutableListOf<String>()
        while (pos < buf.length) {
            val c = buf[pos]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> {
                    depth++
                    if (depth == 2) itemStart = pos
                }
                '}' -> {
                    if (depth == 2 && itemStart >= 0) {
                        done += buf.substring(itemStart, pos + 1)
                        itemStart = -1
                    }
                    depth--
                }
            }
            pos++
        }
        return done
    }
}
