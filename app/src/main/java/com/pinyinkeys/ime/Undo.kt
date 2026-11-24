package com.pinyinkeys.ime

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Undo / redo for what this keyboard typed or deleted.
 *
 * The keyboard talks to the app through an [InputConnection]. [RecordingConnection] sits
 * in between and notes each change (text added, text removed) so it can be reversed.
 * Kept in memory only, never saved, and cleared when you move the cursor yourself or
 * switch text boxes. Nothing is recorded in password fields.
 */
class UndoHistory {
    /** One change: [removed] text was replaced by [inserted] just before the cursor. */
    data class Op(val removedBefore: String, val removedAfter: String, val inserted: String)

    private val undo = ArrayList<MutableList<Op>>()
    private val redo = ArrayList<List<Op>>()
    private val main = Handler(Looper.getMainLooper())
    /** True while one keyboard action (one tap) is still adding changes to the last group. */
    private var actionOpen = false
    private val closeAction = Runnable { actionOpen = false }

    /** Called whenever undo becomes (un)available, to show or hide the ↶ button. */
    var onChange: () -> Unit = {}
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun record(op: Op) {
        if (op.removedBefore.isEmpty() && op.removedAfter.isEmpty() && op.inserted.isEmpty()) return
        redo.clear()
        val last = undo.lastOrNull()
        when {
            // Changes made while handling the same tap (e.g. an autocorrect's delete + insert)
            // belong together.
            actionOpen && last != null -> last += op
            // Letters typed one after another form one word: undo removes the whole word.
            last != null && isLetter(op) && last.all { isLetter(it) } -> last += op
            // Same for a run of single-character deletes.
            last != null && isOneDelete(op) && last.all { isOneDelete(it) } -> last += op
            else -> { undo += mutableListOf(op); if (undo.size > 30) undo.removeAt(0) }
        }
        actionOpen = true
        main.removeCallbacks(closeAction)
        main.post(closeAction)
        onChange()
    }

    fun clear() { undo.clear(); redo.clear(); onChange() }

    /** Reverses the last group of changes directly on [ic]. Returns false if there was none. */
    fun undo(ic: InputConnection): Boolean {
        val group = undo.removeLastOrNull() ?: return false
        ic.beginBatchEdit()
        for (op in group.asReversed()) {
            if (op.inserted.isNotEmpty()) ic.deleteSurroundingText(op.inserted.length, 0)
            if (op.removedBefore.isNotEmpty()) ic.commitText(op.removedBefore, 1)
            // Text that was after the cursor goes back after it (cursor stays put).
            if (op.removedAfter.isNotEmpty()) ic.commitText(op.removedAfter, 0)
        }
        ic.endBatchEdit()
        redo += group.toList()
        onChange()
        return true
    }

    /** Re-applies the last undone group. */
    fun redo(ic: InputConnection): Boolean {
        val group = redo.removeLastOrNull() ?: return false
        ic.beginBatchEdit()
        for (op in group) {
            ic.deleteSurroundingText(op.removedBefore.length, op.removedAfter.length)
            if (op.inserted.isNotEmpty()) ic.commitText(op.inserted, 1)
        }
        ic.endBatchEdit()
        undo += group.toMutableList()
        onChange()
        return true
    }

    private fun isLetter(op: Op) = op.removedBefore.isEmpty() && op.removedAfter.isEmpty() &&
        op.inserted.length == 1 && (op.inserted[0].isLetterOrDigit() || op.inserted[0] == '\'')

    private fun isOneDelete(op: Op) = op.inserted.isEmpty() && op.removedAfter.isEmpty() && op.removedBefore.length == 1
}

/**
 * Passes everything through to the app, noting changes in [history] (see [UndoHistory]).
 * [enabled] is false in password and other sensitive fields: nothing is read or kept there.
 */
class RecordingConnection(
    target: InputConnection,
    private val history: UndoHistory,
    private val enabled: () -> Boolean,
    private val hasSelection: () -> Boolean,
) : InputConnectionWrapper(target, true) {
    /** Pinyin being composed (underlined); replaced by the characters when committed. */
    private var composing = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (enabled()) {
            // Committing replaces the composing pinyin (never committed itself, so nothing to
            // put back) or the selected text (which we read first so undo can restore it).
            val selected = if (composing.isEmpty() && hasSelection()) getSelectedText(0)?.toString() ?: "" else ""
            history.record(UndoHistory.Op(selected, "", text?.toString() ?: ""))
        }
        composing = ""
        return super.commitText(text, newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (enabled()) {
            val before = if (beforeLength > 0) getTextBeforeCursor(beforeLength, 0)?.toString() ?: "" else ""
            val after = if (afterLength > 0) getTextAfterCursor(afterLength, 0)?.toString() ?: "" else ""
            history.record(UndoHistory.Op(before, after, ""))
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = text?.toString() ?: ""
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        // Letters left as they are become real text.
        if (composing.isNotEmpty() && enabled()) history.record(UndoHistory.Op("", "", composing))
        composing = ""
        return super.finishComposingText()
    }

    override fun performContextMenuAction(id: Int): Boolean {
        // Cut / paste through the app's menu: we can't see what changed, so start over.
        history.clear()
        return super.performContextMenuAction(id)
    }
}
