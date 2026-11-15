package com.pinyinkeys.ime

import android.content.Context
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

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", null) ?: ClaudeTranslationService.DEFAULT_BASE_URL
        set(v) = prefs.edit().putString("base_url", v.trim().ifEmpty { ClaudeTranslationService.DEFAULT_BASE_URL }).apply()

    var model: String
        get() = prefs.getString("model", null) ?: ClaudeTranslationService.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { ClaudeTranslationService.DEFAULT_MODEL }).apply()

    /** Null when no API key is set yet. */
    fun translationService(): TranslationService? =
        apiKey.takeIf { it.isNotEmpty() }?.let { ClaudeTranslationService(it, baseUrl, model) }
}
