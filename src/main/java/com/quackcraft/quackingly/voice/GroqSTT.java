package com.quackcraft.quackingly.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.util.HttpUtils;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Groq Whisper STT client.
 *
 * POST https://api.groq.com/openai/v1/audio/transcriptions
 *   multipart/form-data:
 *     file=<wav bytes>
 *     model=whisper-large-v3
 *     language=en (optional)
 *
 * Returns: { "text": "..." }
 *
 * Uses a hand-rolled multipart body to avoid pulling in an HTTP client library
 * (keeps the .jar small, helps Pojav/Mojo).
 */
public class GroqSTT {

    public static String transcribe(byte[] wavBytes) throws Exception {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        String key = cfg.apiKey;
        if (key == null || key.isBlank() || !key.startsWith("gsk_")) {
            throw new IllegalStateException("Groq API key required for STT (must start with gsk_)");
        }

        String boundary = "quackingly-boundary-" + System.currentTimeMillis();
        String crlf = "\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        // file field
        body.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"mic.wav\"" + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: audio/wav" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(wavBytes);
        body.write(crlf.getBytes(StandardCharsets.UTF_8));

        // model field
        body.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"model\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.write((cfg.sttModel + crlf).getBytes(StandardCharsets.UTF_8));

        // language field
        body.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"language\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.write(("en" + crlf).getBytes(StandardCharsets.UTF_8));

        body.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        HttpResponse<byte[]> resp = HttpUtils.postBytesWithHeaders(
                "https://api.groq.com/openai/v1/audio/transcriptions",
                body.toByteArray(),
                Map.of(
                        "Authorization", "Bearer " + key,
                        "Content-Type", "multipart/form-data; boundary=" + boundary));

        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Groq STT HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        JsonObject json = JsonParser.parseString(new String(resp.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        String text = json.has("text") ? json.get("text").getAsString().trim() : "";
        Quackingly.LOGGER.info("[Quackingly] STT result: {}", text);
        return text;
    }
}
