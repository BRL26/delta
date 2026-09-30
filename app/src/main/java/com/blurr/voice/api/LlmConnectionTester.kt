package com.blurr.voice.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends a tiny throwaway request to verify a provider's URL, key and model
 * actually work, so the user finds out in settings instead of mid-task.
 */
object LlmConnectionTester {

    private const val TAG = "LlmConnectionTester"

    sealed class Result {
        data class Success(val detail: String) : Result()
        data class Failure(val detail: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun test(config: LlmProviderConfig): Result = withContext(Dispatchers.IO) {
        if (config.effectiveBaseUrl.isBlank()) {
            return@withContext Result.Failure("No base URL set.")
        }
        if (config.provider?.keyRequired == true && config.apiKey.isBlank()) {
            return@withContext Result.Failure("No API key set.")
        }
        if (config.effectiveModel.isBlank()) {
            return@withContext Result.Failure("No model set.")
        }

        val probe = LlmMessage(LlmRole.USER, "Reply with the single word: ok")
        return@withContext try {
            val req = LlmPayloads.buildRequest(config, listOf(probe))
            val request = Request.Builder()
                .url(req.url)
                .post(req.body.toString().toRequestBody("application/json".toMediaType()))
                .apply { req.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = LlmPayloads.parseError(body)
                        ?: "HTTP ${response.code}"
                    Result.Failure(msg.take(300))
                } else {
                    val text = LlmPayloads.parseResponse(config, body)
                    if (text.isNullOrBlank()) {
                        Result.Failure("Connected, but no text came back. Check the model name.")
                    } else {
                        Log.d(TAG, "Test OK for ${config.providerId}")
                        Result.Success(text.trim().take(80))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Test failed for ${config.providerId}", e)
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
