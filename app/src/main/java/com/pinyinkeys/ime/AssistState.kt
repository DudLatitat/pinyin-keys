package com.pinyinkeys.ime

import com.pinyinkeys.ime.translate.TranslationResult

/** The states of the Assist panel above the keys. */
sealed interface AssistState {
    data object Idle : AssistState
    data object Loading : AssistState
    data class Result(val result: TranslationResult) : AssistState
    data class Error(val message: String) : AssistState
}
