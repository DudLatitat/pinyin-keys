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
    // Opening encrypted prefs talks to the Keystore and is slow, so it's done once per
    // process and shared by every Settings(...) object (they're cheap wrappers).
    private val prefs: SharedPreferences = shared(context)

    companion object {
        @Volatile private var cached: SharedPreferences? = null
        private fun shared(context: Context): SharedPreferences =
            cached ?: synchronized(this) {
                cached ?: EncryptedSharedPreferences.create(
                    "settings_encrypted",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { cached = it }
            }
    }

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

    /** Translation tone: casual (default), slang or polite. */
    var tone: com.pinyinkeys.ime.translate.Tone
        get() = prefs.getString("tone", null)?.let { n -> com.pinyinkeys.ime.translate.Tone.entries.firstOrNull { it.name == n } }
            ?: if (prefs.getBoolean("polite", false)) com.pinyinkeys.ime.translate.Tone.Polite else com.pinyinkeys.ime.translate.Tone.Casual
        set(v) = prefs.edit().putString("tone", v.name).apply()

        /** Keyboard theme name (see THEMES), or "System" to follow the phone's dark mode. */
    var theme: String
        get() = prefs.getString("theme", "System") ?: "System"
        set(v) = prefs.edit().putString("theme", v).apply()

        /** Key height: 1.0 normal, 1.12 tall, 1.25 extra tall. */
    var keyScale: Float
        get() = prefs.getFloat("key_scale", 1f)
        set(v) = prefs.edit().putFloat("key_scale", v).apply()

    /** Vibrate briefly on each key press. On by default. */
    var vibrateKeys: Boolean
        get() = prefs.getBoolean("vibrate_keys", true)
        set(v) = prefs.edit().putBoolean("vibrate_keys", v).apply()

    /** Play the phone's key click on each key press. Off by default. */
    var soundKeys: Boolean
        get() = prefs.getBoolean("sound_keys", false)
        set(v) = prefs.edit().putBoolean("sound_keys", v).apply()

    /** Colour pinyin by tone (1 red, 2 orange, 3 green, 4 blue). On by default. */
    var toneColors: Boolean
        get() = prefs.getBoolean("tone_colors", true)
        set(v) = prefs.edit().putBoolean("tone_colors", v).apply()

    /** Glide typing: slide across letters to type a word. On by default. */
    var glideTyping: Boolean
        get() = prefs.getBoolean("glide_typing", true)
        set(v) = prefs.edit().putBoolean("glide_typing", v).apply()

    /** One-handed keyboard: -1 left, 1 right, 0 off. */
    var oneHanded: Int
        get() = prefs.getInt("one_handed", 0)
        set(v) = prefs.edit().putInt("one_handed", v).apply()

    /** Show the 1-0 number row above the letters. */
    var numberRow: Boolean
        get() = prefs.getBoolean("number_row", true)
        set(v) = prefs.edit().putBoolean("number_row", v).apply()

    /** Recently used emoji, newest first. */
    var recentEmoji: List<String>
        get() = readList("recent_emoji")
        set(v) = writeList("recent_emoji", v)

    /** Clipboard snippets the user pinned. Stored encrypted, only on this phone. */
    var pinnedClips: List<String>
        get() = readList("pinned_clips")
        set(v) = writeList("pinned_clips", v)

    /** Words the user wants kept as typed (never autocorrected). */
    var userWords: List<String>
        get() = readList("user_words")
        set(v) = writeList("user_words", v)

    /** Personal touch bias per letter: "a<TAB>dx<TAB>dy" lines. */
    var touchBias: List<String>
        get() = readList("touch_bias")
        set(v) = writeList("touch_bias", v)

    /** Bumped when the app's "learned" screen edits learned data, so the keyboard reloads it. */
    var learnVersion: Int
        get() = prefs.getInt("learn_version", 0)
        set(v) = prefs.edit().putInt("learn_version", v).apply()

    /** Forget everything the keyboard learned about your typing. */
    fun forgetAllLearning() {
        prefs.edit().remove("learned_fixes").remove("user_words").remove("next_words").remove("touch_bias").remove("preferred_picks")
            .putInt("learn_version", learnVersion + 1).apply()
    }

    /** Words you picked instead of what you typed ("typed<TAB>picked"). */
    var preferredPicks: List<String>
        get() = readList("preferred_picks")
        set(v) = writeList("preferred_picks", v)

    /** Typos you've fixed by picking a suggestion ("typo<TAB>fix"). */
    var learnedFixes: List<String>
        get() = readList("learned_fixes")
        set(v) = writeList("learned_fixes", v)

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
