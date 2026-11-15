package com.pinyinkeys.ime

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.pinyinkeys.ime.translate.ClaudeTranslationService
import com.pinyinkeys.ime.translate.TranslationService

/**
 * App settings, stored encrypted on the phone. The encryption key lives in the
 * Android Keystore (hardware-backed on most phones), so the file is unreadable
 * even if copied off the device. Excluded from backups by the manifest.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "settings_encrypted",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Pasted keys sometimes carry invisible characters (line breaks, non-breaking or
     * zero-width spaces). Those aren't allowed in an HTTP header, so keep only the
     * visible ASCII characters an API key can contain. Applied on save and on read,
     * which also repairs a key saved before this fix.
     */
    var apiKey: String
        get() = clean(prefs.getString("api_key", "") ?: "")
        set(v) = prefs.edit().putString("api_key", clean(v)).apply()

    var baseUrl: String
        get() = clean(prefs.getString("base_url", null) ?: "").ifEmpty { ClaudeTranslationService.DEFAULT_BASE_URL }
        set(v) = prefs.edit().putString("base_url", clean(v).ifEmpty { ClaudeTranslationService.DEFAULT_BASE_URL }).apply()

    var model: String
        get() = clean(prefs.getString("model", null) ?: "").ifEmpty { ClaudeTranslationService.DEFAULT_MODEL }
        set(v) = prefs.edit().putString("model", clean(v).ifEmpty { ClaudeTranslationService.DEFAULT_MODEL }).apply()

    /** True if the saved key has the usual Claude API key shape (shown on the settings screen). */
    val apiKeyLooksValid: Boolean get() = apiKey.startsWith("sk-ant-")

    /** Practice mode: require the tone number (e.g. "ni3"). Off by default. */
    var strictTones: Boolean
        get() = prefs.getBoolean("strict_tones", false)
        set(v) = prefs.edit().putBoolean("strict_tones", v).apply()

    /** Offer to paste a screenshot taken in the last minute (needs photo permission). */
    var suggestScreenshots: Boolean
        get() = prefs.getBoolean("suggest_screenshots", false)
        set(v) = prefs.edit().putBoolean("suggest_screenshots", v).apply()

    /** Fix obvious typos when space is pressed. On by default. */
    var autocorrect: Boolean
        get() = prefs.getBoolean("autocorrect", true)
        set(v) = prefs.edit().putBoolean("autocorrect", v).apply()

    /** Learned word pairs for next-word suggestions ("prev<TAB>next<TAB>count" lines). */
    var nextWordData: String
        get() = prefs.getString("next_words", "") ?: ""
        set(v) = prefs.edit().putString("next_words", v).apply()

        /** Recently used emoji, newest first. */
    var recentEmoji: List<String>
        get() = readList("recent_emoji")
        set(v) = writeList("recent_emoji", v)

    /** Clipboard snippets the user pinned. Stored encrypted, only on this phone. */
    var pinnedClips: List<String>
        get() = readList("pinned_clips")
        set(v) = writeList("pinned_clips", v)

    /** Text the user pasted with this keyboard (newest first). Encrypted, on the phone only. */
    var clipHistory: List<String>
        get() = readList("clip_history")
        set(v) = writeList("clip_history", v)

    private fun readList(key: String): List<String> = runCatching {
        Json.decodeFromString(
            ListSerializer(String.serializer()),
            prefs.getString(key, null) ?: return emptyList(),
        )
    }.getOrDefault(emptyList())

    private fun writeList(key: String, v: List<String>) = prefs.edit().putString(
        key,
        Json.encodeToString(
            ListSerializer(String.serializer()), v,
        ),
    ).apply()

    /** Null when no API key is set yet. */
    fun translationService(): TranslationService? =
        apiKey.takeIf { it.isNotEmpty() }?.let { ClaudeTranslationService(it, baseUrl, model) }

    private fun clean(v: String) = v.filter { it in '!'..'~' }
}
