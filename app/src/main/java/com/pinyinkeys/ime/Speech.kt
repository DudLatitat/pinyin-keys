package com.pinyinkeys.ime

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads Chinese aloud with the phone's own text-to-speech engine (the voice the phone
 * already has, e.g. Samsung's or Google's). The text goes only to that engine on the phone.
 */
class Speech(context: Context) {
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts.language = Locale.SIMPLIFIED_CHINESE
            tts.setSpeechRate(0.85f)  // a little slower, for learning
            pending?.let { speak(it) }
            pending = null
        }
    }

    /** Returns false if the phone has no Chinese voice installed. */
    fun speak(text: String): Boolean {
        if (!ready) { pending = text; return true }
        val ok = tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
        if (ok) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card")
        return ok
    }

    fun shutdown() = tts.shutdown()
}
