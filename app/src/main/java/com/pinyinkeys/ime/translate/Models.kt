package com.pinyinkeys.ime.translate

import kotlinx.serialization.Serializable

// The data shapes the rest of the app sees. Every provider must return these,
// so swapping providers never touches the UI.

@Serializable
data class Chunk(
    val hanzi: String,
    val pinyin: String,
    val role: String,
    /** Short English meaning of this word ("this", "test"). Empty in older saved data. */
    val meaning: String = "",
)

/** What Translate returns: kept small so it comes back fast. */
@Serializable
data class Sentence(
    val en: String,
    val hanzi: String,
    val pinyin: String,
)

/** Loaded only when a card is tapped. */
@Serializable
data class SentenceDetails(
    /** One per character, number-tone form ("wo3"). Drives Practice mode later. */
    val syllables: List<String>,
    val chunks: List<Chunk>,
)

/** What the model returns per numbered input line (the English is already known). */
/** How translations should sound. */
enum class Tone(val icon: String, val label: String) {
    Casual("随", "Casual"),
    Slang("俏", "Slang"),
    Polite("敬", "Polite");

    fun next(): Tone = entries[(ordinal + 1) % entries.size]
}

@Serializable
data class LineResult(val hanzi: String, val pinyin: String)

@Serializable
data class LineResults(val sentences: List<LineResult>)

/** A received message explained: its sentences (hanzi, pinyin, English) and a reply. */
@Serializable
data class Explained(val sentences: List<Sentence>, val reply: Sentence)

@Serializable
data class WhyResult(val why: String)

@Serializable
data class TranslationResult(val sentences: List<Sentence>)

/** Any failure the UI should show as "Couldn't translate". [userMessage] never contains message text. */
class TranslationException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)
