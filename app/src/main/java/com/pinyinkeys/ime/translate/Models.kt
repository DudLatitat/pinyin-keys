package com.pinyinkeys.ime.translate

import kotlinx.serialization.Serializable

// The data shapes the rest of the app sees. Every provider must return these,
// so swapping providers never touches the UI.

@Serializable
data class Chunk(
    val hanzi: String,
    val pinyin: String,
    val role: String,
)

@Serializable
data class Sentence(
    val en: String,
    val hanzi: String,
    val pinyin: String,
    /** One per character, number-tone form ("wo3"). Drives Practice mode later. */
    val syllables: List<String>,
    val chunks: List<Chunk>,
)

@Serializable
data class WhyResult(val why: String)

@Serializable
data class TranslationResult(val sentences: List<Sentence>)

/** Any failure the UI should show as "Couldn't translate". [userMessage] never contains message text. */
class TranslationException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)
