package com.pinyinkeys.ime.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinyinkeys.ime.Screenshots
import com.pinyinkeys.ime.Settings
import com.pinyinkeys.ime.THEMES

/** The pages of the settings app. MENU is the main list. */
enum class Page(val title: String) {
    MENU("Pinyin Keys"), SETUP("Setup"), TRANSLATION("Translation"), TYPING("Typing"),
    LOOK("Look"), CLIPBOARD("Clipboard"), PRACTICE("Practice"), LEARNED("What I've learned"),
}

/**
 * Whole settings app. Instead of one Activity per screen, we keep a remembered `page`
 * value and draw the matching page. rememberSaveable keeps it across screen rotation.
 */
@Composable
fun SettingsApp(activity: ComponentActivity, settings: Settings, enabled: Boolean, selected: Boolean) {
    var page by rememberSaveable { mutableStateOf(Page.MENU) }
    // BackHandler intercepts the system Back gesture: on a sub-page it returns to the menu;
    // on the menu it is disabled, so Back closes the app as usual.
    BackHandler(enabled = page != Page.MENU) { page = Page.MENU }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page != Page.MENU) TextButton(onClick = { page = Page.MENU }) { Text("←", fontSize = 20.sp) }
            Text(page.title, style = MaterialTheme.typography.headlineSmall)
        }
        when (page) {
            Page.MENU -> MenuPage(activity, settings, enabled, selected) { page = it }
            Page.SETUP -> SetupSteps(activity, enabled, selected)
            Page.TRANSLATION -> TranslationPage(settings)
            Page.TYPING -> TypingPage(settings)
            Page.LOOK -> LookPage(settings)
            Page.CLIPBOARD -> ClipboardPage(activity, settings)
            Page.PRACTICE -> PracticePage(settings)
            Page.LEARNED -> LearnedPage(settings)
        }
    }
}

@Composable
private fun MenuPage(
    activity: ComponentActivity, settings: Settings, enabled: Boolean, selected: Boolean, open: (Page) -> Unit,
) {
    // Setup card shows only until the keyboard is enabled and chosen.
    if (!(enabled && selected)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                SetupSteps(activity, enabled, selected)
            }
        }
    }
    // Summaries are read when the menu is drawn, i.e. each time you come back to it.
    val keySummary = when {
        settings.apiKey.isEmpty() -> "API key not set"
        settings.apiKeyLooksValid -> "API key saved ✓"
        else -> "API key saved (doesn't look like a Claude key)"
    }
    val typing = listOfNotNull(
        if (settings.autocorrect) "Autocorrect on" else "Autocorrect off",
        if (settings.glideTyping) "glide" else null,
        keyHeightName(settings.keyScale).lowercase() + " keys",
    ).joinToString(" · ")
    val look = settings.theme + if (settings.toneColors) " · tone colours" else ""
    val learnedCount = settings.userWords.size + settings.learnedFixes.size + settings.preferredPicks.size
    Column {
        MenuRow("Translation", keySummary) { open(Page.TRANSLATION) }
        MenuRow("Typing", typing) { open(Page.TYPING) }
        MenuRow("Look", look) { open(Page.LOOK) }
        MenuRow("Clipboard", if (settings.suggestScreenshots) "Screenshot suggestions on" else "Screenshot suggestions off") {
            open(Page.CLIPBOARD)
        }
        MenuRow("Practice", if (settings.strictTones) "Tone numbers required" else "Tone numbers optional") {
            open(Page.PRACTICE)
        }
        MenuRow("What I've learned", "$learnedCount words and fixes") { open(Page.LEARNED) }
        MenuRow("Saved sentences", "Open your saved list") {
            activity.startActivity(Intent(activity, com.pinyinkeys.ime.saved.SavedActivity::class.java))
        }
        if (enabled && selected) MenuRow("Setup", "Keyboard is on and selected ✓") { open(Page.SETUP) }
    }
    Text(
        "Your text is sent to the translation API only when you tap Translate.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun MenuRow(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun SetupSteps(activity: ComponentActivity, enabled: Boolean, selected: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("1. Turn on Pinyin Keys in the system keyboard list." + if (enabled) " ✓" else "")
        Button(onClick = {
            activity.startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }) { Text("Open keyboard settings") }
        Text("2. Choose Pinyin Keys as the current keyboard." + if (selected) " ✓" else "")
        Button(onClick = {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }) { Text("Pick keyboard") }
    }
}

@Composable
private fun TranslationPage(settings: Settings) {
    // The stored key is never shown back; the field starts empty.
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.apiKey.isNotEmpty()) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var saved by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

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
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Advanced ▴" else "Advanced ▾") }
    if (advanced) {
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
    }
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
    Text(
        "Your text is sent to the translation API only when you tap Translate.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A switch with a short label and optional small help text underneath. */
@Composable
fun SwitchRow(label: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val KEY_HEIGHTS = listOf(1f to "Normal", 1.12f to "Tall", 1.25f to "Extra tall")
private fun keyHeightName(scale: Float) =
    KEY_HEIGHTS.minByOrNull { kotlin.math.abs(it.first - scale) }?.second ?: "Normal"

// Segmented buttons are still marked "experimental" in material3 1.3, so we opt in.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypingPage(settings: Settings) {
    var autoFix by remember { mutableStateOf(settings.autocorrect) }
    SwitchRow("Autocorrect", "Fixes obvious typos. Backspace right after a correction puts back what you typed.", autoFix) {
        autoFix = it; settings.autocorrect = it
    }
    var numRow by remember { mutableStateOf(settings.numberRow) }
    SwitchRow("Number row", "Show 1–0 above the letters.", numRow) { numRow = it; settings.numberRow = it }
    var glideT by remember { mutableStateOf(settings.glideTyping) }
    SwitchRow("Glide typing", "Slide across the letters of a word.", glideT) { glideT = it; settings.glideTyping = it }
    var vib by remember { mutableStateOf(settings.vibrateKeys) }
    SwitchRow("Vibrate", "Small buzz on each key press.", vib) { vib = it; settings.vibrateKeys = it }
    var snd by remember { mutableStateOf(settings.soundKeys) }
    SwitchRow("Sound", "Click sound on each key press.", snd) { snd = it; settings.soundKeys = it }

    Text("Key height", Modifier.padding(top = 4.dp))
    var scale by remember { mutableStateOf(settings.keyScale) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        KEY_HEIGHTS.forEachIndexed { i, (v, name) ->
            SegmentedButton(
                selected = scale == v,
                onClick = { scale = v; settings.keyScale = v },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = KEY_HEIGHTS.size),
            ) { Text(name) }
        }
    }
}

@Composable
private fun LookPage(settings: Settings) {
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    var theme by remember { mutableStateOf(settings.theme) }
    // "System" previews as Dark or Light depending on the phone's dark mode right now.
    val systemPreview = if (isSystemInDarkTheme()) THEMES["Dark"] else THEMES["Light"]
    val names = listOf("System") + THEMES.keys
    // A simple 2-column grid: chunk the list into pairs and draw each pair as a Row.
    // (LazyVerticalGrid can't sit inside a scrolling Column.)
    names.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { name ->
                ThemeTile(
                    name = name,
                    colors = THEMES[name] ?: systemPreview,
                    selected = theme == name,
                    modifier = Modifier.weight(1f),
                ) { theme = name; settings.theme = name }
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
        }
    }
    var toneCol by remember { mutableStateOf(settings.toneColors) }
    SwitchRow("Colour pinyin by tone", "1 red · 2 orange · 3 green · 4 blue", toneCol) {
        toneCol = it; settings.toneColors = it
    }
    Text("Takes effect the next time the keyboard opens.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ThemeTile(
    name: String, colors: com.pinyinkeys.ime.KbColors?, selected: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .border(if (selected) 3.dp else 1.dp, outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (colors != null) {
            // A tiny keyboard: background with three keys (one darker "function" key).
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
                    .background(colors.background).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(colors.key to "A", colors.key to "B", colors.functionKey to "⏎").forEach { (bg, label) ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(bg),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = colors.label, fontSize = 12.sp) }
                }
            }
        }
        Text((if (selected) "✓ " else "") + name, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ClipboardPage(activity: ComponentActivity, settings: Settings) {
    var shots by remember {
        mutableStateOf(settings.suggestScreenshots && Screenshots.hasPermission(activity))
    }
    // Asks Android for photo access; the switch turns on only if it's granted.
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settings.suggestScreenshots = granted
        shots = granted
    }
    SwitchRow(
        "Suggest new screenshots to paste",
        if (Screenshots.hasLimitedAccessOnly(activity))
            "Photo access is set to \"selected photos\", which doesn't include new " +
                "screenshots. Allow all photos for Pinyin Keys in Android's app settings."
        else "Needs photo access. Only a screenshot from the last two minutes is looked at.",
        shots,
    ) { on ->
        if (!on) { settings.suggestScreenshots = false; shots = false }
        else if (Screenshots.hasPermission(activity)) { settings.suggestScreenshots = true; shots = true }
        else askPhotos.launch(Screenshots.permission)
    }
}

@Composable
private fun PracticePage(settings: Settings) {
    var strict by remember { mutableStateOf(settings.strictTones) }
    SwitchRow("Require tone numbers", "Answers must include tones, e.g. ni3 hao3.", strict) {
        strict = it; settings.strictTones = it
    }
}
