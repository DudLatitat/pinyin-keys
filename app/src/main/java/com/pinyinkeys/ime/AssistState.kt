package com.pinyinkeys.ime

import com.pinyinkeys.ime.translate.TranslationResult

/** The states of the Assist panel above the keys. */
sealed interface AssistState {
    data object Idle : AssistState
    data object Loading : AssistState
    /** [complete] is false while sentences are still streaming in. */
    data class Result(
        val result: TranslationResult,
        val complete: Boolean = true,
        /** Set after explaining a received Chinese message: a suggested reply. */
        val reply: com.pinyinkeys.ime.translate.Sentence? = null,
    ) : AssistState
    data class Error(val message: String) : AssistState
}

/** Something loaded on demand for one sentence card (its breakdown, or its "Why?"). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}
