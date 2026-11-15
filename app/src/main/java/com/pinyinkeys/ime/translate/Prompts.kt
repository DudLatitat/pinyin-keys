package com.pinyinkeys.ime.translate

/** Prompt text and output schema, shared by every provider so they all get the same instructions. */
object Prompts {
    val TRANSLATE_SYSTEM = """
        You translate casual English text messages into natural spoken Mandarin (Simplified Chinese)
        for a beginner learner who is texting close friends and family.

        Rules:
        - Use casual, everyday phrasing a native speaker would actually text. Avoid textbook or formal wording.
        - Keep the meaning and tone (warmth, jokes, teasing). Keep emoji as they are.
        - Split the input into sentences. Return one entry per sentence, in order.
        - "en": the original English sentence.
        - "hanzi": the Chinese sentence, with Chinese punctuation.
        - "pinyin": the whole sentence in pinyin with tone marks, words separated by spaces.
        - "syllables": one entry per Chinese character (skip punctuation and emoji), lowercase pinyin
          with a tone number 1-5 (5 = neutral tone), e.g. "wo3", "le5". Use "v" for ü, e.g. "nv3".
        - "chunks": the sentence split into meaningful words/particles, in order, covering every character.
          Each has its hanzi, its pinyin with tone marks, and a short English grammar role such as
          Subject, Verb, Object, Time, Place, Adverb, Measure word, Question particle, New state, Possessive.
        - Output only the JSON object.
    """.trimIndent()

    /** JSON Schema for structured outputs. additionalProperties=false + required are needed for strict mode. */
    const val TRANSLATE_SCHEMA = """
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["sentences"],
      "properties": {
        "sentences": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["en", "hanzi", "pinyin", "syllables", "chunks"],
            "properties": {
              "en": {"type": "string"},
              "hanzi": {"type": "string"},
              "pinyin": {"type": "string"},
              "syllables": {"type": "array", "items": {"type": "string"}},
              "chunks": {
                "type": "array",
                "items": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["hanzi", "pinyin", "role"],
                  "properties": {
                    "hanzi": {"type": "string"},
                    "pinyin": {"type": "string"},
                    "role": {"type": "string"}
                  }
                }
              }
            }
          }
        }
      }
    }
    """
}
