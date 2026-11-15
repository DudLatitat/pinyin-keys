package com.pinyinkeys.ime.translate

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the Claude Messages API over plain HTTPS (HttpURLConnection, built into Android),
 * so there's no extra HTTP library. Everything Claude-specific lives in this file.
 */
class ClaudeTranslationService(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : TranslationService {

    private val json = Json { ignoreUnknownKeys = true }

    override fun translate(english: String): TranslationResult {
        val text = call(Prompts.TRANSLATE_SYSTEM, english, Prompts.TRANSLATE_SCHEMA, maxTokens = 8000)
        return try {
            json.decodeFromString(TranslationResult.serializer(), text)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't translate (E1: unexpected reply shape). Try again.", e)
        }.also {
            if (it.sentences.isEmpty()) throw TranslationException("Nothing to translate.")
        }
    }

    override fun details(sentence: Sentence): SentenceDetails {
        val text = call(Prompts.DETAILS_SYSTEM, Prompts.sentenceInput(sentence), Prompts.DETAILS_SCHEMA, maxTokens = 4000)
        return try {
            json.decodeFromString(SentenceDetails.serializer(), text)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't load breakdown. Try again.", e)
        }
    }

    override fun explain(sentence: Sentence, details: SentenceDetails): String {
        val text = call(Prompts.WHY_SYSTEM, Prompts.whyInput(sentence, details), Prompts.WHY_SCHEMA, maxTokens = 2000)
        return try {
            json.decodeFromString(WhyResult.serializer(), text).why.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't load explanation. Try again.", e)
        }.ifEmpty { throw TranslationException("Couldn't load explanation. Try again.") }
    }

    /** One Messages API request with structured output. Returns the JSON text the model produced. */
    private fun call(system: String, user: String, schema: String, maxTokens: Int): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
            // No thinking step: translation/explanations don't need it, and it was the main
            // source of waiting (and is billed as output). Allowed at effort "low" on Haiku.
            put("thinking", buildJsonObject { put("type", "disabled") })
            // effort=low: keep replies lean.
            // format=json_schema: the API guarantees the reply matches our schema.
            put("output_config", buildJsonObject {
                put("effort", "low")
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("schema", json.parseToJsonElement(schema))
                })
            })
        }

        val raw = post(body.toString())
        val response = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            // Describe the reply's shape only (type, size, first character class), never its text.
            val start = when (raw.trimStart().firstOrNull()) { '{' -> "json"; '<' -> "html"; null -> "empty"; else -> "other" }
            val kind = when (e) {
                is kotlinx.serialization.SerializationException -> "parse"
                is IllegalArgumentException -> "notObject"
                else -> e.javaClass.simpleName.take(24)
            }
            Log.w(TAG, "Unreadable reply: $kind type=$lastContentType len=${raw.length} start=$start")
            throw TranslationException(
                "Couldn't translate (E2: $kind, $start, ${raw.length} chars, ${lastContentType ?: "no type"}). Try again.", e
            )
        }

        when (response["stop_reason"]?.jsonPrimitive?.content) {
            "refusal" -> throw TranslationException("The model declined this text.")
            "max_tokens" -> throw TranslationException("Text too long. Try a shorter message.")
        }
        return response["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?.get("text")?.jsonPrimitive?.content
            ?: throw TranslationException(
                "Couldn't translate (E3: no text, stop=${response["stop_reason"]?.jsonPrimitive?.content}). Try again."
            )
    }

    /** Content type of the last reply, for error messages only. */
    private var lastContentType: String? = null

    private fun post(body: String): String {
        val url = URL(baseUrl.trimEnd('/') + "/v1/messages")
        if (url.protocol != "https") throw TranslationException("Base URL must start with https://")
        val conn = try { (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
        } } catch (e: IllegalArgumentException) {
            // A header value with a character HTTP doesn't allow (almost always the key).
            Log.w(TAG, "Invalid header value")
            throw TranslationException("API key has invalid characters. Paste it again in Pinyin Keys settings.", e)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Log the status only, never the body (it can echo our text back).
                Log.w(TAG, "API error HTTP $code")
                throw TranslationException(
                    when (code) {
                        401, 403 -> "API key rejected. Check it in Pinyin Keys settings."
                        429 -> "Rate limited. Wait a moment and try again."
                        400 -> "Request rejected (check model name in settings)."
                        in 500..599 -> "Translation service is busy. Try again."
                        else -> "Couldn't translate (HTTP $code)."
                    }
                )
            }
            lastContentType = conn.contentType?.substringBefore(';')
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.javaClass.simpleName}")
            throw TranslationException("No connection. Try again.", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ClaudeTranslation"
        private const val API_VERSION = "2026-10-21"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
