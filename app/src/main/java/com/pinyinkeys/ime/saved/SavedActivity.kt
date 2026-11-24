package com.pinyinkeys.ime.saved

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The list of saved sentences, opened from the app's main screen. */
class SavedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).saved()
        val toneColors = com.pinyinkeys.ime.Settings(this).toneColors
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    val list by dao.all().collectAsState(initial = emptyList())
                    val scope = rememberCoroutineScope()
                    Column(Modifier.padding(16.dp)) {
                        Text("Saved sentences", style = MaterialTheme.typography.headlineSmall)
                        if (list.isEmpty()) {
                            Text(
                                "Nothing saved yet. Tap ☆ on a sentence card in the keyboard.",
                                Modifier.padding(top = 16.dp),
                            )
                        }
                        LazyColumn(
                            Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(list, key = { it.id }) { s ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(12.dp),
                                ) {
                                    Text(s.hanzi, fontSize = 22.sp)
                                    // Same tone colours as the keyboard (when that setting is on).
                                    if (toneColors) Text(com.pinyinkeys.ime.ToneColors.colorize(s.pinyin, dark), fontSize = 15.sp)
                                    else Text(s.pinyin, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(s.en, fontSize = 14.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            DateFormat.getDateInstance().format(Date(s.savedAt)),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { scope.launch { dao.delete(s.id) } }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
