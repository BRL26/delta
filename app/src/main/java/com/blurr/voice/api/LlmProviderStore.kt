package com.blurr.voice.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.blurr.voice.BuildConfig

/**
 * Persists the user's provider configurations in SharedPreferences and resolves
 * which provider the agent should use.
 *
 * Resolution order: the user's selected provider, then any configured provider
 * with a key, then - for backwards compatibility with the original
 * build-time config - the GCLOUD_PROXY_URL from local.properties.
 */
class LlmProviderStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All saved configs, in insertion order. */
    fun getConfigs(): List<LlmProviderConfig> {
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                LlmProviderConfig.fromJson(arr.optJSONObject(i) ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getConfig(providerId: String): LlmProviderConfig? =
        getConfigs().firstOrNull { it.providerId == providerId }

    fun saveConfig(config: LlmProviderConfig) {
        val updated = getConfigs().toMutableList()
        val idx = updated.indexOfFirst { it.providerId == config.providerId }
        if (idx >= 0) updated[idx] = config else updated.add(config)
        writeConfigs(updated)
        if (getActiveProviderId() == null) setActiveProviderId(config.providerId)
    }

    fun deleteConfig(providerId: String) {
        writeConfigs(getConfigs().filterNot { it.providerId == providerId })
        if (getActiveProviderId() == providerId) setActiveProviderId(null)
    }

    fun getActiveProviderId(): String? = prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActiveProviderId(providerId: String?) {
        prefs.edit { if (providerId == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, providerId) }
    }

    /**
     * The config the agent should use, or null if nothing is configured.
     * Prefers an explicitly selected provider, then any provider with a
     * non-blank key or a non-standard base URL.
     */
    fun getActiveConfig(): LlmProviderConfig? {
        val configs = getConfigs()
        if (configs.isEmpty()) return null
        getActiveProviderId()?.let { id -> configs.firstOrNull { it.providerId == id }?.let { return it } }
        return configs.firstOrNull { it.apiKey.isNotBlank() }
            ?: configs.firstOrNull { it.baseUrl.isNotBlank() }
    }

    /**
     * The legacy build-time proxy, exposed as a config so it flows through the
     * same code path. Returns null when the user has configured a provider, so
     * their choice always wins.
     */
    fun getLegacyProxyConfig(): LlmProviderConfig? {
        val url = BuildConfig.GCLOUD_PROXY_URL
        if (url.isBlank()) return null
        if (getActiveConfig() != null) return null
        return LlmProviderConfig(
            providerId = LEGACY_PROXY_ID,
            baseUrl = url,
            apiKey = BuildConfig.GCLOUD_PROXY_URL_KEY,
            model = ""
        )
    }

    /** The config in effect right now, user-provided or legacy. */
    fun resolveConfig(): LlmProviderConfig? = getActiveConfig() ?: getLegacyProxyConfig()

    private fun writeConfigs(configs: List<LlmProviderConfig>) {
        val arr = org.json.JSONArray()
        configs.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_CONFIGS, arr.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "LlmProviders"
        private const val KEY_CONFIGS = "configs"
        private const val KEY_ACTIVE = "active_provider"
        const val LEGACY_PROXY_ID = "__legacy_proxy__"

        @Volatile
        private var instance: LlmProviderStore? = null

        fun getInstance(context: Context): LlmProviderStore =
            instance ?: synchronized(this) {
                instance ?: LlmProviderStore(context).also { instance = it }
            }
    }
}
