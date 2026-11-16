package com.pinyinkeys.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** The app icon opens this: keyboard setup steps plus translation settings. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Pinyin Keys", style = MaterialTheme.typography.headlineSmall)
                        Text("1. Turn on Pinyin Keys in the system keyboard list.")
                        Button(onClick = {
                            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
                        }) { Text("Open keyboard settings") }
                        Text("2. Choose Pinyin Keys as the current keyboard.")
                        Button(onClick = {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showInputMethodPicker()
                        }) { Text("Pick keyboard") }

                        Button(onClick = {
                            startActivity(Intent(this@SetupActivity, com.pinyinkeys.ime.saved.SavedActivity::class.java))
                        }) { Text("Saved sentences") }

                        HorizontalDivider()
                        Text("Translation", style = MaterialTheme.typography.titleMedium)

                        // The stored key is never shown back; the field starts empty.
                        var key by remember { mutableStateOf("") }
                        var hasKey by remember { mutableStateOf(settings.apiKey.isNotEmpty()) }
                        var baseUrl by remember { mutableStateOf(settings.baseUrl) }
                        var model by remember { mutableStateOf(settings.model) }
                        var saved by remember { mutableStateOf(false) }

                        Text(
                            when {
                                !hasKey -> "API key: not set"
                                settings.apiKeyLooksValid -> "API key: saved ✓"
                                else -> "API key: saved, but it doesn't look like a Claude key (should start with sk-ant-)"
                            }
                        )
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it; saved = false },
                            label = { Text(if (hasKey) "Replace API key" else "API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it; saved = false },
                            label = { Text("Base URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; saved = false },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            if (key.isNotBlank()) settings.apiKey = key
                            settings.baseUrl = baseUrl
                            settings.model = model
                            key = ""
                            hasKey = settings.apiKey.isNotEmpty()
                            baseUrl = settings.baseUrl
                            model = settings.model
                            saved = true
                        }) { Text("Save") }
                        if (saved) Text("Saved.")
                        HorizontalDivider()
                        Text("Practice", style = MaterialTheme.typography.titleMedium)
                        var strict by remember { mutableStateOf(settings.strictTones) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Require tone numbers (e.g. ni3)", Modifier.weight(1f))
                            Switch(checked = strict, onCheckedChange = { strict = it; settings.strictTones = it })
                        }
                        HorizontalDivider()
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        var theme by remember { mutableStateOf(settings.theme) }
                        // Each choice shows a little key in that theme's colors.
                        (listOf("System") + THEMES.keys).forEach { name ->
                            Row(
                                Modifier.fillMaxWidth().clickable { theme = name; settings.theme = name }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = theme == name, onClick = { theme = name; settings.theme = name })
                                Text(name, Modifier.weight(1f))
                                THEMES[name]?.let { kb ->
                                    Box(
                                        Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(6.dp))
                                            .background(kb.background).padding(4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(kb.key),
                                            contentAlignment = Alignment.Center,
                                        ) { Text("A", color = kb.label, fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                        Text("Takes effect the next time the keyboard opens.", style = MaterialTheme.typography.bodySmall)

                        HorizontalDivider()
                        Text("Typing", style = MaterialTheme.typography.titleMedium)
                        var autoFix by remember { mutableStateOf(settings.autocorrect) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Autocorrect obvious typos", Modifier.weight(1f))
                            Switch(checked = autoFix, onCheckedChange = { autoFix = it; settings.autocorrect = it })
                        }
                        Text(
                            "Backspace right after a correction puts back what you typed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        var numRow by remember { mutableStateOf(settings.numberRow) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Number row above the letters", Modifier.weight(1f))
                            Switch(checked = numRow, onCheckedChange = { numRow = it; settings.numberRow = it })
                        }
                        Text("Key height", Modifier.padding(top = 4.dp))
                        var scale by remember { mutableStateOf(settings.keyScale) }
                        listOf(1f to "Normal", 1.12f to "Tall", 1.25f to "Extra tall").forEach { (v, name) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { scale = v; settings.keyScale = v },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = scale == v, onClick = { scale = v; settings.keyScale = v })
                                Text(name)
                            }
                        }
                        HorizontalDivider()
                        // What the keyboard learned from your typing; remove anything wrong.
                        Text("What I've learned", style = MaterialTheme.typography.titleMedium)
                        var fixes by remember { mutableStateOf(settings.learnedFixes) }
                        var words by remember { mutableStateOf(settings.userWords) }
                        fun bump() { settings.learnVersion = settings.learnVersion + 1 }
                        Text("Typos I fix for you", style = MaterialTheme.typography.bodyMedium)
                        if (fixes.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                        fixes.forEach { line ->
                            val parts = line.split('\t')
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${parts.getOrNull(0)} → ${parts.getOrNull(1)}", Modifier.weight(1f))
                                TextButton(onClick = {
                                    fixes = fixes - line; settings.learnedFixes = fixes; bump()
                                }) { Text("✕") }
                            }
                        }
                        Text("Your words (never corrected)", style = MaterialTheme.typography.bodyMedium)
                        if (words.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                        words.forEach { w ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w, Modifier.weight(1f))
                                TextButton(onClick = {
                                    words = words - w; settings.userWords = words; bump()
                                }) { Text("✕") }
                            }
                        }
                        Button(onClick = {
                            settings.forgetAllLearning()
                            fixes = emptyList(); words = emptyList()
                        }) { Text("Forget everything learned") }
                        Text(
                            "Also forgets your phrases and touch habits. Long-press a suggestion on the " +
                                "keyboard to forget a single word.",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        HorizontalDivider()
                        Text("Clipboard", style = MaterialTheme.typography.titleMedium)
                        var shots by remember {
                            mutableStateOf(settings.suggestScreenshots && Screenshots.hasPermission(this@SetupActivity))
                        }
                        // Asks Android for photo access; the switch turns on only if it's granted.
                        val askPhotos = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            settings.suggestScreenshots = granted
                            shots = granted
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Suggest new screenshots to paste", Modifier.weight(1f))
                            Switch(checked = shots, onCheckedChange = { on ->
                                if (!on) { settings.suggestScreenshots = false; shots = false }
                                else if (Screenshots.hasPermission(this@SetupActivity)) {
                                    settings.suggestScreenshots = true; shots = true
                                } else askPhotos.launch(Screenshots.permission)
                            })
                        }
                        Text(
                            if (Screenshots.hasLimitedAccessOnly(this@SetupActivity))
                                "Photo access is set to \"selected photos\", which doesn't include new " +
                                    "screenshots. Allow all photos for Pinyin Keys in Android's app settings."
                            else "Needs photo access. Only a screenshot from the last two minutes is looked at.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Your text is sent to the translation API only when you tap Translate.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
