package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.util.HttpUtils;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fish Audio TTS client.
 *
 * POST https://api.fish.audio/v1/tts
 *   Headers:
 *     Authorization: Bearer <FISH_API_KEY>
 *     Content-Type:  application/json
 *     model:         s2.1-pro-free    (free tier, 83 languages, Fair Use)
 *   Body (JSON):
 *     {
 *       "text": "<reply text>",
 *       "reference_id": "<VOICE_ID>",
 *       "format": "mp3",
 *       "prosody": { "speed": 1, "volume": 0 }
 *     }
 *   Response: raw MP3 bytes
 *
 * Default voice reference_id: "063421453a724a6a8063255c867e9589"
 *   (This is the "verity" voice on Fish Audio — the closest match to the
 *    actual Verity mod's voice character.)
 * Backup voice reference_id: "99906ab84a8d4e16897b357cf633a46b"
 *   (Second candidate for A/B testing if the primary doesn't sound right.)
 *
 * Error handling:
 *   401 — bad API key (user needs to check their Fish Audio key)
 *   402 — quota/billing exhausted (user needs to top up or use a free model)
 *   422 — bad reference_id (voice doesn't exist or was deleted)
 *   429 — rate limited (Fair Use limit hit — wait and retry)
 *
 * Free tier notes:
 *   s2.1-pro-free is "unlimited under Fair Use" per Fish Audio's docs.
 *   Fair Use ≈ reasonable personal use; heavy commercial use may be throttled.
 *   Verify current limits at https://fish.audio before assuming unlimited.
 */
public class FishAudioTTS implements TTSProvider {

    private static final String ENDPOINT = "https://api.fish.audio/v1/tts";
    private static final String DEFAULT_MODEL = "s2.1-pro-free";
    private static final String DEFAULT_VOICE = "063421453a724a6a8063255c867e9589";
    private static final String BACKUP_VOICE  = "99906ab84a8d4e16897b357cf633a46b";

    private String lastVoiceTried;

    @Override
    public String getProviderName() { return "fish_audio"; }

    @Override
    public boolean isReady() {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        return cfg.fishAudioApiKey != null && !cfg.fishAudioApiKey.isBlank();
    }

    @Override
    public byte[] synthesise(String text) throws Exception {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        String key = cfg.fishAudioApiKey;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Fish Audio API key required " +
                    "(set in Mod Menu → Quackingly → Voice → Fish Audio API Key)");
        }

        // Truncate to keep TTS latency low and avoid runaway costs
        String truncated = text.length() > 500 ? text.substring(0, 497) + "..." : text;

        // Try the primary voice first; on 422 (bad reference_id), fall back to backup voice
        String[] voicesToTry;
        String configuredVoice = cfg.fishVoiceReferenceId;
        if (configuredVoice == null || configuredVoice.isBlank()) {
            configuredVoice = DEFAULT_VOICE;
        }
        if (configuredVoice.equals(DEFAULT_VOICE) || configuredVoice.equals(BACKUP_VOICE)) {
            voicesToTry = new String[]{configuredVoice,
                    configuredVoice.equals(DEFAULT_VOICE) ? BACKUP_VOICE : DEFAULT_VOICE};
        } else {
            // User specified a custom voice — try it, then fall back to default
            voicesToTry = new String[]{configuredVoice, DEFAULT_VOICE};
        }

        Exception lastError = null;
        for (String voiceId : voicesToTry) {
            try {
                lastVoiceTried = voiceId;
                byte[] result = doSynthesise(key, truncated, voiceId);
                if (result != null && result.length > 0) return result;
            } catch (FishAudioException e) {
                // 422 = bad reference_id — try the next voice
                if (e.httpStatus == 422) {
                    Quackingly.LOGGER.warn("[Quackingly] Fish Audio voice '{}' rejected (422), trying fallback.",
                            voiceId);
                    lastError = e;
                    continue;
                }
                // 401/402/429 — key/quota errors, don't retry with different voice
                throw e;
            }
        }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("Fish Audio TTS returned no audio for unknown reasons.");
    }

    private byte[] doSynthesise(String apiKey, String text, String voiceReferenceId) throws Exception {
        String body = String.format(
                "{\"text\":%s,\"reference_id\":%s,\"format\":\"mp3\",\"prosody\":{\"speed\":1,\"volume\":0}}",
                quoteJson(text),
                quoteJson(voiceReferenceId));

        HttpResponse<byte[]> resp = HttpUtils.postBytesWithHeaders(
                ENDPOINT,
                body.getBytes(StandardCharsets.UTF_8),
                Map.of(
                        "Authorization", "Bearer " + apiKey,
                        "Content-Type", "application/json",
                        "model", "s2.1-pro-free"));

        int status = resp.statusCode();
        if (status >= 400) {
            String errBody = new String(resp.body(), StandardCharsets.UTF_8);
            String friendly = friendlyError(status, errBody);
            Quackingly.LOGGER.warn("[Quackingly] Fish Audio TTS HTTP {}: {}", status, errBody);
            throw new FishAudioException(status, friendly, errBody);
        }
        return resp.body();
    }

    /** Convert Fish Audio HTTP error codes to user-friendly messages. */
    private static String friendlyError(int status, String body) {
        switch (status) {
            case 401: return "Fish Audio API key is invalid. Check the key in Mod Menu → Voice.";
            case 402: return "Fish Audio quota/billing exhausted. Top up at fish.audio or use a free model.";
            case 403: return "Fish Audio API key was revoked. Generate a new one at fish.audio.";
            case 422: return "Fish Audio voice reference_id is invalid. Try a different voice ID.";
            case 429: return "Fish Audio rate limit hit (Fair Use). Wait a moment and try again.";
            default:  return "Fish Audio TTS error (HTTP " + status + "): " + body;
        }
    }

    public String getLastVoiceTried() { return lastVoiceTried; }

    /** Typed exception carrying the HTTP status for backup-key logic. */
    public static class FishAudioException extends Exception {
        public final int httpStatus;
        public FishAudioException(int status, String friendly, String rawBody) {
            super(friendly);
            this.httpStatus = status;
        }
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
