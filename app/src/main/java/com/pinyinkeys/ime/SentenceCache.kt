package com.pinyinkeys.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.pinyinkeys.ime.translate.Sentence
import com.pinyinkeys.ime.translate.SentenceDetails
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Saves each sentence's breakdown and "Why?" on the phone so each is paid for only once.
 * Encrypted like the settings (it's derived from message text), private to the app,
 * and excluded from backups. Keyed by a hash of the sentence, not the sentence itself.
 */
class SentenceCache(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "sentence_cache_encrypted",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun getWhy(s: Sentence): String? = prefs.getString("why:" + key(s), null)
    fun putWhy(s: Sentence, why: String) = put("why:" + key(s), why)

    fun getDetails(s: Sentence): SentenceDetails? = prefs.getString("details:" + key(s), null)?.let {
        runCatching { json.decodeFromString(SentenceDetails.serializer(), it) }.getOrNull()
    }
    fun putDetails(s: Sentence, d: SentenceDetails) =
        put("details:" + key(s), json.encodeToString(SentenceDetails.serializer(), d))

    /** A sentence translated before (keyed by its normalized English). */
    fun getTranslation(english: String): Sentence? =
        prefs.getString("tr:" + hash(Translator.normalize(english)), null)?.let {
            runCatching { json.decodeFromString(Sentence.serializer(), it) }.getOrNull()
                ?.let { cached -> cached.copy(en = english.removePrefix("[polite] ")) }
        }

    fun putTranslation(english: String, s: Sentence) =
        put("tr:" + hash(Translator.normalize(english)), json.encodeToString(Sentence.serializer(), s))

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun put(k: String, v: String) {
        // Keep the cache small: start over past MAX_ENTRIES (simple, and rarely hit).
        val editor = prefs.edit()
        if (prefs.all.size >= MAX_ENTRIES) editor.clear()
        editor.putString(k, v).apply()
    }

    private fun key(s: Sentence): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((s.en + "\u0000" + s.hanzi).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object { private const val MAX_ENTRIES = 5000 }
}
