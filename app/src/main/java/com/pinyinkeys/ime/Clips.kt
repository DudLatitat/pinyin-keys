package com.pinyinkeys.ime

import android.content.Context
import java.io.File

/**
 * Clipboard history entries are stored as strings. Text is stored as-is; an image is
 * stored as a marker plus the name of its copy in the app's private "clips" folder.
 */
object Clips {
    private const val IMAGE_PREFIX = "\u0000img:"
    const val MAX_HISTORY = 15

    fun imageEntry(fileName: String) = IMAGE_PREFIX + fileName
    fun isImage(entry: String) = entry.startsWith(IMAGE_PREFIX)
    fun dir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }
    fun imageFile(context: Context, entry: String): File? =
        if (isImage(entry)) File(dir(context), entry.removePrefix(IMAGE_PREFIX)) else null
}
