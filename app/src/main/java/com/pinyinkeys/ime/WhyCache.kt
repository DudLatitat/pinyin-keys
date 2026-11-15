package com.pinyinkeys.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.pinyinkeys.ime.translate.Sentence
import java.security.MessageDigest

/**
 * Saves "Why?" explanations on the phone so each one is paid for only once.
 * Encrypted like the settings (it's derived from message text), private to the app,
 * and excluded from backups. Keyed by a hash of the sentence, not the sentence itself.
 */
class WhyCache(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "why_cache_encrypted",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(s: Sentence): String? = prefs.getString(key(s), null)

    fun put(s: Sentence, why: String) {
        // Keep the cache small: start over past MAX_ENTRIES (simple, and rarely hit).
        val editor = prefs.edit()
        if (prefs.all.size >= MAX_ENTRIES) editor.clear()
        editor.putString(key(s), why).apply()
    }

    private fun key(s: Sentence): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((s.en + "\u0000" + s.hanzi).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object { private const val MAX_ENTRIES = 1000 }
}
