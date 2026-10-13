package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.util.HttpUtils;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OpenAI TTS client (implements TTSProvider).
 *
 * This is the FALLBACK TTS provider — Fish Audio is the default.
 * Users who prefer OpenAI can set ttsProvider="openai" in config.
 *
 * POST https://api.openai.com/v1/audio/speech
 *   JSON: { "model": "tts-1", "voice": "alloy", "input": "<text>", "response_format": "mp3" }
 *   Response body: raw MP3 bytes
 *
 * Defaults: tts-1 (NOT tts-1-hd) — tts-1 is the fastest cloud TTS available,
 * ~250ms end-to-end latency.
 *
 * Voices: alloy (neutral), echo (male), fable (British), onyx (deep male),
 *         nova (female), shimmer (soft female).
 */
public class OpenAITTS implements TTSProvider {

    @Override
    public String getProviderName() { return "openai"; }

    @Override
    public boolean isReady() {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        return cfg.openaiTtsApiKey != null && !cfg.openaiTtsApiKey.isBlank();
    }

    @Override
    public byte[] synthesise(String text) throws Exception {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        String key = cfg.openaiTtsApiKey;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OpenAI TTS key required " +
                    "(set in Mod Menu → Quackingly → Voice → OpenAI TTS API Key)");
        }

        String model = (cfg.openaiTtsModel == null || cfg.openaiTtsModel.isBlank()) ? "tts-1" : cfg.openaiTtsModel;
        String voice = (cfg.openaiTtsVoice == null || cfg.openaiTtsVoice.isBlank()) ? "alloy" : cfg.openaiTtsVoice;

        // Truncate to keep TTS latency low
        String truncated = text.length() > 500 ? text.substring(0, 497) + "..." : text;

        String body = String.format(
                "{\"model\":\"%s\",\"voice\":\"%s\",\"input\":%s,\"response_format\":\"mp3\"}",
                model, voice, quoteJson(truncated));

        HttpResponse<byte[]> resp = HttpUtils.postBytesWithHeaders(
                "https://api.openai.com/v1/audio/speech",
                body.getBytes(StandardCharsets.UTF_8),
                Map.of(
                        "Authorization", "Bearer " + key,
                        "Content-Type", "application/json"));

        if (resp.statusCode() >= 400) {
            String err = new String(resp.body(), StandardCharsets.UTF_8);
            Quackingly.LOGGER.warn("[Quackingly] OpenAI TTS HTTP {}: {}", resp.statusCode(), err);
            throw new RuntimeException("OpenAI TTS HTTP " + resp.statusCode() + ": " + err);
        }
        return resp.body();
    }

    private static String quoteJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
