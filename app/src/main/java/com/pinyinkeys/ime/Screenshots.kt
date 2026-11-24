package com.pinyinkeys.ime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Finds a screenshot taken in the last minute, so the keyboard can offer to paste it.
 * Only the newest image in a "Screenshots" folder is looked at; nothing else is read.
 */
object Screenshots {
    /** The photo permission for this Android version. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 14+ lets you allow only "selected photos". New screenshots aren't included
     * then, so the settings screen warns about it.
     */
    fun hasLimitedAccessOnly(context: Context) = Build.VERSION.SDK_INT >= 34 && !hasPermission(context) &&
        context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED

    /** Newest screenshot added within [maxAgeMs], or null. */
    fun recent(context: Context, maxAgeMs: Long = 120_000): Uri? {
        if (!hasPermission(context)) return null
        val since = (System.currentTimeMillis() - maxAgeMs) / 1000  // DATE_ADDED is in seconds
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH
            else MediaStore.Images.Media.DATA
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                // Phones name the folder differently ("Screenshots", "DCIM/Screenshots", ...),
                // so match either the path or the album name.
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ($folder LIKE ? OR " +
                    "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)",
                arrayOf(since.toString(), "%Screenshot%", "%Screenshot%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        }.getOrNull()
    }
}
