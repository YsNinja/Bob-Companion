package com.quackcraft.quackingly.llm;

import com.quackcraft.quackingly.config.QuackinglyConfig;

import java.util.Locale;

/**
 * Detects the LLM provider from the API key prefix.
 *
 * Patterns:
 *   gsk_         -> Groq          (https://api.groq.com/openai/v1)
 *   sk-or-       -> OpenRouter    (https://openrouter.ai/api/v1)
 *   sk-ant-      -> Anthropic     (https://api.anthropic.com/v1) — uses /messages, not /chat/completions
 *   AIza         -> Google Gemini (https://generativelanguage.googleapis.com/v1beta)
 *   sk-          -> OpenAI        (https://api.openai.com/v1)
 *   (empty)      -> none
 *   anything else-> custom        (user must supply baseUrl)
 */
public class ProviderDetector {

    public static String detect(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "none";
        String k = apiKey.trim();
        if (k.startsWith("gsk_")) return "groq";
        if (k.startsWith("sk-or-")) return "openrouter";
        if (k.startsWith("sk-ant-")) return "anthropic";
        if (k.startsWith("AIza")) return "gemini";
        if (k.startsWith("sk-")) return "openai";
        return "custom";
    }

    public static String baseUrlFor(String provider) {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        if (cfg.customBaseUrl != null && !cfg.customBaseUrl.isBlank()) return cfg.customBaseUrl;
        switch (provider.toLowerCase(Locale.ROOT)) {
            case "groq":        return "https://api.groq.com/openai/v1";
            case "openrouter":  return "https://openrouter.ai/api/v1";
            case "openai":      return "https://api.openai.com/v1";
            case "anthropic":   return "https://api.anthropic.com/v1";
            case "gemini":      return "https://generativelanguage.googleapis.com/v1beta";
            default:            return "https://api.groq.com/openai/v1";
        }
    }

    public static String defaultModelFor(String provider) {
        switch (provider.toLowerCase(Locale.ROOT)) {
            case "groq":        return "openai/gpt-oss-20b";
            case "openrouter":  return "openrouter/auto";
            case "openai":      return "gpt-4o-mini";
            case "anthropic":   return "claude-3-5-haiku-latest";
            case "gemini":      return "gemini-1.5-flash";
            default:            return "openai/gpt-oss-20b";
        }
    }

    /** True if the provider speaks the OpenAI /chat/completions protocol natively. */
    public static boolean isOpenAICompatible(String provider) {
        switch (provider.toLowerCase(Locale.ROOT)) {
            case "groq":
            case "openrouter":
            case "openai":
            case "custom":
                return true;
            case "anthropic":
            case "gemini":
                return false;
            default:
                return true;
        }
    }
}

