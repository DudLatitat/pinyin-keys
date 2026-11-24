package com.pinyinkeys.ime

import android.text.InputType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Which number pad a field needs. Apps say what they expect through EditorInfo.inputType:
 * TYPE_CLASS_NUMBER (amounts, PINs), TYPE_CLASS_PHONE, or TYPE_CLASS_DATETIME.
 */
data class NumberPadKind(val decimal: Boolean, val signed: Boolean, val phone: Boolean) {
    companion object {
        fun forInputType(type: Int): NumberPadKind? = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberPadKind(
                decimal = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                signed = type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                phone = false,
            )
            InputType.TYPE_CLASS_PHONE -> NumberPadKind(decimal = false, signed = false, phone = true)
            InputType.TYPE_CLASS_DATETIME -> NumberPadKind(decimal = false, signed = false, phone = false)
            else -> null
        }
    }
}

/** A big 4x4 number pad, like a standard keyboard shows for number fields. */
@Composable
fun NumberPad(
    kind: NumberPadKind,
    enterLabel: String?,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
) {
    val gap = 0.dp  // keys include their own gaps (see KEY_GAP_V)
    val rowHeight = PANEL_HEIGHT / 4 - KEY_GAP_V
    // Bottom-left/right slots depend on what the field accepts.
    val bottomLeft = when { kind.phone -> "*"; kind.signed -> "-"; else -> null }
    val bottomRight = when { kind.phone -> "#"; kind.decimal -> ","; else -> null }
    val rightOfNine = when { kind.phone -> "+"; kind.decimal -> "."; else -> null }
    val kb = LocalKb.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        PadRow(gap) {
            Key("1", height = rowHeight) { onText("1") }
            Key("2", height = rowHeight) { onText("2") }
            Key("3", height = rowHeight) { onText("3") }
            Key("⌫", height = rowHeight, function = true, repeat = true, onClick = onBackspace)
        }
        PadRow(gap) {
            Key("4", height = rowHeight) { onText("4") }
            Key("5", height = rowHeight) { onText("5") }
            Key("6", height = rowHeight) { onText("6") }
            Key(enterLabel ?: "⏎", height = rowHeight, function = true,
                labelColor = if (enterLabel != null) kb.accent else null, onClick = onEnter)
        }
        PadRow(gap) {
            Key("7", height = rowHeight) { onText("7") }
            Key("8", height = rowHeight) { onText("8") }
            Key("9", height = rowHeight) { onText("9") }
            SlotKey(rightOfNine, rowHeight, onText)
        }
        PadRow(gap) {
            SlotKey(bottomLeft, rowHeight, onText)
            Key("0", height = rowHeight) { onText("0") }
            SlotKey(if (kind.phone) " " else null, rowHeight, onText, label = if (kind.phone) "␣" else null)
            SlotKey(bottomRight, rowHeight, onText)
        }
    }
}

@Composable
private fun PadRow(gap: androidx.compose.ui.unit.Dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), content = content)
}

/** A function-coloured key, or an empty dark slot when this field doesn't need it. */
@Composable
private fun RowScope.SlotKey(
    text: String?,
    height: androidx.compose.ui.unit.Dp,
    onText: (String) -> Unit,
    label: String? = null,
) {
    if (text != null) Key(label ?: text, height = height, function = true) { onText(text) }
    else Box(Modifier.weight(1f).height(height + KEY_GAP_V).padding(horizontal = KEY_GAP_H / 2, vertical = KEY_GAP_V / 2)) {
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).background(LocalKb.current.functionKey))
    }
}
