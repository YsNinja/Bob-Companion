package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.config.QuackinglyConfig;

/**
 * Pluggable TTS provider abstraction.
 *
 * Implementations:
 *   - FishAudioTTS  (default) — free tier, voice cloning, "verity" voice available
 *   - OpenAITTS     (fallback) — fast but paid
 *
 * The provider is selected at runtime via config.ttsProvider.
 * Callers use TTSProvider.fromConfig() to get the active provider.
 */
public interface TTSProvider {

    /** Synthesise the given text to MP3 bytes. Throws on error. */
    byte[] synthesise(String text) throws Exception;

    /** Human-readable provider name (for logging / error messages). */
    String getProviderName();

    /** True if this provider has the credentials it needs to function. */
    boolean isReady();

    /** Factory: returns the configured provider, or FishAudioTTS by default. */
    static TTSProvider fromConfig() {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        String provider = cfg.ttsProvider;
        if (provider == null || provider.isBlank()) provider = "fish_audio";
        if (provider.equalsIgnoreCase("openai")) return new OpenAITTS();
        return new FishAudioTTS();
    }

    /**
     * Check if an HTTP error indicates a key/quota problem that should trigger
     * backup-key failover. Used by the client TTS pipeline.
     */
    static boolean isKeyOrQuotaError(int httpStatus) {
        return httpStatus == 401      // Unauthorized — bad key
            || httpStatus == 402      // Payment Required — quota/billing
            || httpStatus == 403      // Forbidden — key revoked
            || httpStatus == 429;     // Too Many Requests — rate limited
    }
}
