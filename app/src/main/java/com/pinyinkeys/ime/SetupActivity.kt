package com.pinyinkeys.ime

import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.pinyinkeys.ime.settings.SettingsApp

/** The app icon opens this: keyboard setup steps plus all settings (see settings/ for pages). */
class SetupActivity : ComponentActivity() {
    // Compose "state": when these change, the screen redraws. Re-checked in onResume,
    // because the user leaves to Android's settings to enable the keyboard and comes back.
    private val keyboardEnabled = mutableStateOf(false)
    private val keyboardSelected = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsApp(
                        activity = this,
                        settings = settings,
                        enabled = keyboardEnabled.value,
                        selected = keyboardSelected.value,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKeyboardStatus()
    }

    // Also called when focus returns (the keyboard picker is a dialog, not a new screen).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshKeyboardStatus()
    }

    private fun refreshKeyboardStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        keyboardEnabled.value = imm.enabledInputMethodList.any { it.packageName == packageName }
        // The current keyboard is stored as "package/serviceClass".
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
        keyboardSelected.value = current?.substringBefore('/') == packageName
    }
}
