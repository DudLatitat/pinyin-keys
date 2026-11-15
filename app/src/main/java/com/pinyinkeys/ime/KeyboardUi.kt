package com.pinyinkeys.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOL_ROWS = listOf("1234567890", "@#\$%&-+()/", "*\"':;!?")

/**
 * The QWERTY keyboard. Pure UI: every key just calls one of the callbacks,
 * and the service turns that into edits on the text field.
 *
 * `sensitive` is unused in Milestone 1 (the keyboard is plain everywhere); later the
 * translation panel above the keys will be hidden when it's true.
 */
@Composable
fun KeyboardUi(
    sensitive: Boolean,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            // On the wide inner screen of a foldable, cap the width so keys don't get huge.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
                    rows.forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (i == 2) {
                                if (symbols) Spacer1(1.5f)
                                else Key(if (shift) "⇧" else "⇪", 1.5f) { shift = !shift }
                            }
                            if (i == 1) Spacer1(0.5f)
                            row.forEach { c ->
                                val label = if (shift && !symbols) c.uppercase() else c.toString()
                                Key(label) {
                                    onText(label)
                                    shift = false
                                }
                            }
                            if (i == 1) Spacer1(0.5f)
                            if (i == 2) Key("⌫", 1.5f, onClick = onBackspace)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Key(if (symbols) "ABC" else "?123", 1.5f) { symbols = !symbols }
                        Key("🌐", 1f, onClick = onSwitchKeyboard)
                        Key(",", 1f) { onText(",") }
                        Key("space", 4f) { onText(" ") }
                        Key(".", 1f) { onText(".") }
                        Key("⏎", 1.5f, onClick = onEnter)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, weight: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(weight)
            .height(46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.Spacer1(weight: Float) {
    Box(Modifier.weight(weight))
}
