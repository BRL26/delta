package com.blurr.voice.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format a provider speaks. Most vendors expose an OpenAI-compatible
 * /chat/completions endpoint; Gemini and Anthropic have their own shapes.
 */
enum class LlmFlavor {
    OPENAI_COMPATIBLE,
    GEMINI_NATIVE,
    ANTHROPIC
}

/**
 * A provider the user can configure. [defaultBaseUrl] and [defaultModel] are
 * just starting points - everything is user-editable, so self-hosted endpoints
 * (Ollama, LM Studio, vLLM) work through the same entry.
 */
data class LlmProvider(
    val id: String,
    val displayName: String,
    val flavor: LlmFlavor,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyRequired: Boolean = true,
    val docsUrl: String? = null
) {
    companion object {
        val ALL: List<LlmProvider> = listOf(
            LlmProvider(
                id = "openai",
                displayName = "OpenAI",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                docsUrl = "https://platform.openai.com/api-keys"
            ),
            LlmProvider(
                id = "gemini",
                displayName = "Google Gemini",
                flavor = LlmFlavor.GEMINI_NATIVE,
                defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
                defaultModel = "gemini-2.5-flash",
                docsUrl = "https://aistudio.google.com/app/apikey"
            ),
            LlmProvider(
                id = "anthropic",
                displayName = "Anthropic",
                flavor = LlmFlavor.ANTHROPIC,
                defaultBaseUrl = "https://api.anthropic.com",
                defaultModel = "claude-sonnet-4-5",
                docsUrl = "https://console.anthropic.com/settings/keys"
            ),
            LlmProvider(
                id = "openrouter",
                displayName = "OpenRouter",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://openrouter.ai/api/v1",
                defaultModel = "google/gemini-2.5-flash",
                docsUrl = "https://openrouter.ai/keys"
            ),
            LlmProvider(
                id = "groq",
                displayName = "Groq",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.groq.com/openai/v1",
                defaultModel = "llama-3.3-70b-versatile",
                docsUrl = "https://console.groq.com/keys"
            ),
            LlmProvider(
                id = "together",
                displayName = "Together AI",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.together.xyz/v1",
                defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                docsUrl = "https://api.together.ai/settings/api-keys"
            ),
            LlmProvider(
                id = "mistral",
                displayName = "Mistral",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.mistral.ai/v1",
                defaultModel = "mistral-large-latest",
                docsUrl = "https://console.mistral.ai/api-keys"
            ),
            LlmProvider(
                id = "deepseek",
                displayName = "DeepSeek",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                docsUrl = "https://platform.deepseek.com/api_keys"
            ),
            LlmProvider(
                id = "xai",
                displayName = "xAI Grok",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "https://api.x.ai/v1",
                defaultModel = "grok-2-latest",
                docsUrl = "https://console.x.ai"
            ),
            LlmProvider(
                id = "ollama",
                displayName = "Ollama (local)",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "http://10.0.2.2:11434/v1",
                defaultModel = "llama3.2",
                keyRequired = false,
                docsUrl = "https://ollama.com/library"
            ),
            LlmProvider(
                id = "lmstudio",
                displayName = "LM Studio (local)",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "http://10.0.2.2:1234/v1",
                defaultModel = "local-model",
                keyRequired = false
            ),
            LlmProvider(
                id = "custom_openai",
                displayName = "Custom (OpenAI-compatible)",
                flavor = LlmFlavor.OPENAI_COMPATIBLE,
                defaultBaseUrl = "",
                defaultModel = "",
                keyRequired = false
            )
        )

        fun byId(id: String): LlmProvider? = ALL.find { it.id == id }
    }
}

/** A single message in the neutral shape the app passes around. */
data class LlmMessage(
    val role: LlmRole,
    val text: String,
    val imageBase64: String? = null
)

enum class LlmRole { USER, MODEL }

/**
 * A user-configured provider: a provider plus the user's overrides for URL,
 * key and model. Persisted by [LlmProviderStore].
 */
data class LlmProviderConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val provider: LlmProvider? get() = LlmProvider.byId(providerId)

    val flavor: LlmFlavor get() = provider?.flavor ?: LlmFlavor.OPENAI_COMPATIBLE

    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider?.defaultBaseUrl.orEmpty() }.trimEnd('/')

    val effectiveModel: String
        get() = model.ifBlank { provider?.defaultModel.orEmpty() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("providerId", providerId)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
    }

    companion object {
        fun fromJson(o: JSONObject): LlmProviderConfig? {
            val id = o.optString("providerId").ifBlank { return null }
            return LlmProviderConfig(
                providerId = id,
                baseUrl = o.optString("baseUrl"),
                apiKey = o.optString("apiKey"),
                model = o.optString("model")
            )
        }
    }
}

/**
 * Translates the app's neutral [LlmMessage] list into each provider's request
 * shape, and parses the response back into plain text.
 */
object LlmPayloads {

    /** A fully-built request: target URL, headers, and the JSON body. */
    data class LlmRequest(val url: String, val headers: Map<String, String>, val body: JSONObject)

    fun buildRequest(config: LlmProviderConfig, messages: List<LlmMessage>): LlmRequest {
        return when (config.flavor) {
            LlmFlavor.OPENAI_COMPATIBLE -> buildOpenAi(config, messages)
            LlmFlavor.GEMINI_NATIVE -> buildGemini(config, messages)
            LlmFlavor.ANTHROPIC -> buildAnthropic(config, messages)
        }
    }

    private fun buildOpenAi(config: LlmProviderConfig, messages: List<LlmMessage>): LlmRequest {
        val arr = JSONArray()
        messages.forEach { m ->
            val obj = JSONObject()
            obj.put("role", if (m.role == LlmRole.MODEL) "assistant" else "user")
            if (m.imageBase64 != null) {
                // OpenAI vision shape: content is an array of typed parts.
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.text))
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,${m.imageBase64}")
                    )
                )
                obj.put("content", parts)
            } else {
                obj.put("content", m.text)
            }
            arr.put(obj)
        }
        val body = JSONObject().put("model", config.effectiveModel).put("messages", arr)
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        return LlmRequest("${config.effectiveBaseUrl}/chat/completions", headers, body)
    }

    private fun buildGemini(config: LlmProviderConfig, messages: List<LlmMessage>): LlmRequest {
        // Gemini wants the model in the path and a `contents` array, not `messages`.
        val contents = JSONArray()
        messages.forEach { m ->
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
            if (m.imageBase64 != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", m.imageBase64)
                    )
                )
            }
            val role = if (m.role == LlmRole.MODEL) "model" else "user"
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        val body = JSONObject().put("contents", contents)
        val url = "${config.effectiveBaseUrl}/models/${config.effectiveModel}:generateContent"
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (config.apiKey.isNotBlank()) headers["x-goog-api-key"] = config.apiKey
        return LlmRequest(url, headers, body)
    }

    private fun buildAnthropic(config: LlmProviderConfig, messages: List<LlmMessage>): LlmRequest {
        val arr = JSONArray()
        messages.forEach { m ->
            val obj = JSONObject()
            obj.put("role", if (m.role == LlmRole.MODEL) "assistant" else "user")
            if (m.imageBase64 != null) {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.text))
                parts.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", m.imageBase64)
                    )
                )
                obj.put("content", parts)
            } else {
                obj.put("content", m.text)
            }
            arr.put(obj)
        }
        val body = JSONObject()
            .put("model", config.effectiveModel)
            .put("max_tokens", 4096)
            .put("messages", arr)
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "anthropic-version" to "2023-06-01"
        )
        if (config.apiKey.isNotBlank()) headers["x-api-key"] = config.apiKey
        return LlmRequest("${config.effectiveBaseUrl}/v1/messages", headers, body)
    }

    /** Pulls the assistant text out of a response, whatever the shape. */
    fun parseResponse(config: LlmProviderConfig, body: String): String? {
        return try {
            val json = JSONObject(body)
            when (config.flavor) {
                LlmFlavor.OPENAI_COMPATIBLE -> json.optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }

                LlmFlavor.GEMINI_NATIVE -> json.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

                LlmFlavor.ANTHROPIC -> json.optJSONArray("content")
                    ?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            } ?: body.takeIf { it.isNotBlank() && !body.trimStart().startsWith("{") }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort human-readable error for the test-connection button. */
    fun parseError(body: String): String? {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.let { e ->
                return e.optString("message").ifBlank { e.toString() }
            }
            json.optJSONObject("error")?.optJSONArray("errors")?.let { return it.toString() }
            json.optString("message").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
