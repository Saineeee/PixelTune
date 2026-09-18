package com.theveloper.pixeltune.data.ai.provider

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating AI client instances based on provider type
 */
@Singleton
class AiClientFactory @Inject constructor() {

    // PERF: every AiClient wraps its own HTTP stack (DeepSeek constructs an
    // OkHttpClient — own dispatcher, connection pool and idle threads — and
    // Gemini's Client does the same). createClient() is invoked per AI
    // operation, so batch AI metadata completion built one client (and one
    // OkHttp thread pool) per song. Cache one client per provider and rebuild
    // only when the API key changes; the replaced instance is left to GC once
    // in-flight calls holding it finish.
    private val cachedClients = ConcurrentHashMap<AiProvider, CachedClient>()

    private data class CachedClient(val apiKey: String, val client: AiClient)

    /**
     * Create an AI client for the specified provider
     * @param provider The AI provider type
     * @param apiKey The API key for the provider
     * @return AiClient instance
     */
    fun createClient(provider: AiProvider, apiKey: String): AiClient {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("API Key cannot be blank for ${provider.displayName}")
        }

        val entry = cachedClients.compute(provider) { _, existing ->
            if (existing != null && existing.apiKey == apiKey) {
                existing
            } else {
                CachedClient(apiKey, buildClient(provider, apiKey))
            }
        }
        return entry.client
    }

    private fun buildClient(provider: AiProvider, apiKey: String): AiClient = when (provider) {
        AiProvider.GEMINI -> GeminiAiClient(apiKey)
        AiProvider.DEEPSEEK -> DeepSeekAiClient(apiKey)
    }
}
