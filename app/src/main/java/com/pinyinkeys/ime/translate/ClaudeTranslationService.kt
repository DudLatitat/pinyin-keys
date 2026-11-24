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

    override fun explainChinese(message: String): Explained {
        val text = call(Prompts.EXPLAIN_SYSTEM, message, Prompts.EXPLAIN_SCHEMA, maxTokens = 4000)
        return try {
            json.decodeFromString(Explained.serializer(), text)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't explain (E1: unexpected reply shape). Try again.", e)
        }
    }

    override fun translateLines(lines: List<String>, tone: Tone, onLine: (Int, Sentence) -> Unit): List<Sentence> {
        // Stream the reply and hand over each line as soon as it's complete.
        val scanner = JsonItemScanner()
        var index = 0
        val system = when (tone) {
            Tone.Casual -> Prompts.TRANSLATE_SYSTEM
            Tone.Slang -> Prompts.TRANSLATE_SYSTEM + "\n\n" + Prompts.SLANG_NOTE
            Tone.Polite -> Prompts.TRANSLATE_SYSTEM + "\n\n" + Prompts.POLITE_NOTE
        }
        val text = call(system, Prompts.numberedLines(lines), Prompts.TRANSLATE_SCHEMA, maxTokens = 8000) { delta ->
            scanner.append(delta).forEach { item ->
                val r = runCatching { json.decodeFromString(LineResult.serializer(), item) }.getOrNull()
                if (r != null && index < lines.size) onLine(index, Sentence(lines[index], r.hanzi, r.pinyin))
                index++
            }
        }
        val results = try {
            json.decodeFromString(LineResults.serializer(), text).sentences
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't translate (E1: unexpected reply shape). Try again.", e)
        }
        if (results.size != lines.size) {
            Log.w(TAG, "Line count mismatch: ${results.size} vs ${lines.size}")
            throw TranslationException("Couldn't translate (E5: lines didn't match). Try again.")
        }
        return results.mapIndexed { i, r -> Sentence(lines[i], r.hanzi, r.pinyin) }
    }

    override fun warmUp() {
        // A tiny, free request (list one model) that sets up the secure connection, which
        // Android then keeps open for the real request. Carries the API key, no message text.
        runCatching {
            val url = URL(baseUrl.trimEnd('/') + "/v1/models?limit=1")
            if (url.protocol != "https") return
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", API_VERSION)
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() }  // read fully so the connection can be reused
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

    /**
     * One Messages API request with structured output. Returns the JSON text the model
     * produced. With [onDelta], the reply is streamed (server-sent events) and each new
     * piece of text is passed to [onDelta] as it arrives.
     */
    private fun call(
        system: String,
        user: String,
        schema: String,
        maxTokens: Int,
        onDelta: ((String) -> Unit)? = null,
    ): String {
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
            if (onDelta != null) put("stream", true)
        }

        return request(body.toString()) { reader ->
            if (onDelta != null) readStream(reader, onDelta) else readWhole(reader.readText())
        }
    }

    /** Non-streamed reply: one JSON object with the text inside. */
    private fun readWhole(raw: String): String {
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
        checkStop(response["stop_reason"]?.jsonPrimitive?.content)
        return response["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?.get("text")?.jsonPrimitive?.content
            ?: throw TranslationException(
                "Couldn't translate (E3: no text, stop=${response["stop_reason"]?.jsonPrimitive?.content}). Try again."
            )
    }

    /**
     * Streamed reply: lines of "data: {...}" events. Text arrives in
     * content_block_delta events of type text_delta; the stop reason in message_delta.
     */
    private fun readStream(reader: java.io.BufferedReader, onDelta: (String) -> Unit): String {
        val text = StringBuilder()
        var stop: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val event = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }
                .getOrNull() ?: continue
            when (event["type"]?.jsonPrimitive?.content) {
                "content_block_delta" -> {
                    val delta = event["delta"]?.jsonObject ?: continue
                    if (delta["type"]?.jsonPrimitive?.content == "text_delta") {
                        val piece = delta["text"]?.jsonPrimitive?.content ?: continue
                        text.append(piece)
                        onDelta(piece)
                    }
                }
                "message_delta" -> stop = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content ?: stop
                "error" -> {
                    val type = event["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                    Log.w(TAG, "Stream error: $type")
                    throw TranslationException(
                        if (type == "overloaded_error") "Translation service is busy. Try again."
                        else "Couldn't translate (stream: $type). Try again."
                    )
                }
                "message_stop" -> break
            }
        }
        checkStop(stop)
        if (text.isEmpty()) throw TranslationException("Couldn't translate (E3: no text, stop=$stop). Try again.")
        return text.toString()
    }

    private fun checkStop(stop: String?) {
        when (stop) {
            "refusal" -> throw TranslationException("The model declined this text.")
            "max_tokens" -> throw TranslationException("Text too long. Try a shorter message.")
        }
    }

    /** Content type of the last reply, for error messages only. */
    private var lastContentType: String? = null

    /**
     * Sends [body] and hands the reply to [read]. The connection is not closed afterwards:
     * Android keeps it open for a short while and reuses it for the next request, which
     * skips a new secure handshake (about 0.3-0.5 s).
     */
    private fun <T> request(body: String, read: (java.io.BufferedReader) -> T): T {
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
                // Read and drop the error body so the connection can be reused; never log it.
                runCatching { conn.errorStream?.use { it.readBytes() } }
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
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use(read)
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.javaClass.simpleName}")
            conn.disconnect()  // a broken connection must not be reused
            throw TranslationException("No connection. Try again.", e)
        }
    }

    companion object {
        private const val TAG = "ClaudeTranslation"
        private const val API_VERSION = "2026-10-21"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
