package com.pinyinkeys.ime.translate

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 8000)
            put("system", Prompts.TRANSLATE_SYSTEM)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", english)
                })
            })
            // effort=low: translation doesn't need deep reasoning, and reasoning is billed.
            // format=json_schema: the API guarantees the reply matches our schema.
            put("output_config", buildJsonObject {
                put("effort", "low")
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("schema", json.parseToJsonElement(Prompts.TRANSLATE_SCHEMA))
                })
            })
        }

        val responseText = post(body.toString())
        val response = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw TranslationException("Couldn't translate. Try again.", e)
        }

        when (response["stop_reason"]?.jsonPrimitive?.content) {
            "refusal" -> throw TranslationException("The model declined this text.")
            "max_tokens" -> throw TranslationException("Text too long. Try a shorter message.")
        }
        val text = response["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?.get("text")?.jsonPrimitive?.content
            ?: throw TranslationException("Couldn't translate. Try again.")

        return try {
            json.decodeFromString(TranslationResult.serializer(), text).also {
                if (it.sentences.isEmpty()) throw TranslationException("Nothing to translate.")
            }
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from API: ${e.javaClass.simpleName}")
            throw TranslationException("Couldn't translate. Try again.", e)
        }
    }

    private fun post(body: String): String {
        val url = URL(baseUrl.trimEnd('/') + "/v1/messages")
        if (url.protocol != "https") throw TranslationException("Base URL must start with https://")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
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
