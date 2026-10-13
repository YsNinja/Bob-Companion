package com.quackcraft.quackingly.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.util.HttpUtils;

import java.net.http.HttpResponse;
import java.io.IOException;
import java.util.List;

/**
 * LLM provider interface. Single method: chat(messages) -> reply string.
 */
public interface LLMProvider {
    String chat(List<ChatMessage> messages) throws Exception;
    String getProviderName();
    String getModel();
    boolean isReady();

    /* ---- Factory ---- */

    /**
     * Create a provider from the current config (uses the primary API key).
     * For backup-key failover, use {@link #fromConfig(String)} instead.
     */
    static LLMProvider fromConfig() {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        return fromConfig(cfg.apiKey);
    }

    /**
     * Create a provider using a specific API key (used by KeyRotation for
     * backup-key failover). The key's prefix determines which provider impl
     * is returned.
     */
    static LLMProvider fromConfig(String apiKey) {
        QuackinglyConfig.ConfigData cfg = QuackinglyConfig.get();
        String provider = ProviderDetector.detect(apiKey);
        String baseUrl  = ProviderDetector.baseUrlFor(provider);
        String model    = cfg.model != null && !cfg.model.isBlank() ? cfg.model
                          : ProviderDetector.defaultModelFor(provider);

        switch (provider) {
            case "anthropic": return new AnthropicProvider(apiKey, model);
            case "gemini":    return new GeminiProvider(apiKey, model);
            case "none":      Quackingly.LOGGER.warn("No API key set; LLM disabled");
            default:          return new OpenAICompatibleProvider(provider, baseUrl, apiKey, model);
        }
    }

    /**
     * Check if an HTTP error indicates a key/quota problem that should trigger
     * backup-key failover.
     */
    static boolean isKeyOrQuotaError(int httpStatus) {
        return httpStatus == 401      // Unauthorized — bad key
            || httpStatus == 402      // Payment Required — quota/billing
            || httpStatus == 403      // Forbidden — key revoked
            || httpStatus == 429;     // Too Many Requests — rate limited
    }

    /* ---- OpenAI-compatible implementation (Groq, OpenAI, OpenRouter, Cerebras, custom) ---- */

    class OpenAICompatibleProvider implements LLMProvider {
        private final String provider;
        private final String baseUrl;
        private final String apiKey;
        private final String model;

        public OpenAICompatibleProvider(String provider, String baseUrl, String apiKey, String model) {
            this.provider = provider;
            this.baseUrl  = baseUrl;
            this.apiKey   = apiKey;
            this.model    = model;
        }

        @Override public String getProviderName() { return provider; }
        @Override public String getModel()        { return model; }
        @Override public boolean isReady()        { return apiKey != null && !apiKey.isBlank(); }

        @Override
        public String chat(List<ChatMessage> messages) throws Exception {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", 0.7);
            body.addProperty("max_tokens", 512);
            JsonArray arr = new JsonArray();
            for (ChatMessage m : messages) {
                JsonObject o = new JsonObject();
                o.addProperty("role", m.role.name().toLowerCase());
                o.addProperty("content", m.content);
                arr.add(o);
            }
            body.add("messages", arr);

            HttpResponse<String> resp = HttpUtils.postJson(
                    baseUrl + "/chat/completions",
                    "Bearer " + apiKey,
                    body.toString());

            if (resp.statusCode() >= 400) {
                throw new LLMHttpException(resp.statusCode(),
                        "LLM " + provider + " HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!json.has("choices") || !json.get("choices").isJsonArray()
                    || json.getAsJsonArray("choices").isEmpty()) {
                throw new IOException("The provider returned no chat choices. Check the selected OpenRouter model.");
            }
            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                    ? choice.getAsJsonObject("message") : new JsonObject();
            if (!message.has("content") || message.get("content").isJsonNull()) {
                throw new IOException("The selected model returned an empty message (content=null). Choose a chat model that supports text output.");
            }
            if (message.get("content").isJsonPrimitive()) {
                return message.get("content").getAsString().trim();
            }
            if (message.get("content").isJsonArray()) {
                StringBuilder content = new StringBuilder();
                for (var part : message.getAsJsonArray("content")) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text")
                            && part.getAsJsonObject().get("text").isJsonPrimitive()) {
                        if (content.length() > 0) content.append('\n');
                        content.append(part.getAsJsonObject().get("text").getAsString());
                    }
                }
                if (content.length() > 0) return content.toString().trim();
            }
            throw new IOException("The selected model returned no readable text. Choose a chat model with text output.");
        }
    }

    /* ---- Anthropic (different protocol: /messages, x-api-key header) ---- */

    class AnthropicProvider implements LLMProvider {
        private final String apiKey;
        private final String model;
        public AnthropicProvider(String apiKey, String model) {
            this.apiKey = apiKey; this.model = model;
        }
        @Override public String getProviderName() { return "anthropic"; }
        @Override public String getModel()        { return model; }
        @Override public boolean isReady()        { return apiKey != null && !apiKey.isBlank(); }

        @Override
        public String chat(List<ChatMessage> messages) throws Exception {
            String systemPrompt = "";
            JsonArray arr = new JsonArray();
            for (ChatMessage m : messages) {
                if (m.role == ChatMessage.Role.SYSTEM) { systemPrompt = m.content; continue; }
                JsonObject o = new JsonObject();
                o.addProperty("role", m.role == ChatMessage.Role.USER ? "user" : "assistant");
                o.addProperty("content", m.content);
                arr.add(o);
            }

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", 512);
            body.addProperty("system", systemPrompt);
            body.add("messages", arr);

            HttpResponse<String> resp = HttpUtils.postJsonWithHeaders(
                    "https://api.anthropic.com/v1/messages",
                    body.toString(),
                    java.util.Map.of(
                            "x-api-key", apiKey,
                            "anthropic-version", "2023-06-01",
                            "content-type", "application/json"));

            if (resp.statusCode() >= 400) {
                throw new LLMHttpException(resp.statusCode(),
                        "Anthropic HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            return json.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString().trim();
        }
    }

    /* ---- Gemini ---- */

    class GeminiProvider implements LLMProvider {
        private final String apiKey;
        private final String model;
        public GeminiProvider(String apiKey, String model) {
            this.apiKey = apiKey; this.model = model;
        }
        @Override public String getProviderName() { return "gemini"; }
        @Override public String getModel()        { return model; }
        @Override public boolean isReady()        { return apiKey != null && !apiKey.isBlank(); }

        @Override
        public String chat(List<ChatMessage> messages) throws Exception {
            String systemPrompt = "";
            JsonArray contents = new JsonArray();
            for (ChatMessage m : messages) {
                if (m.role == ChatMessage.Role.SYSTEM) { systemPrompt = m.content; continue; }
                JsonObject part = new JsonObject();
                part.addProperty("text", m.content);
                JsonArray parts = new JsonArray(); parts.add(part);
                JsonObject o = new JsonObject();
                o.addProperty("role", m.role == ChatMessage.Role.USER ? "user" : "model");
                o.add("parts", parts);
                contents.add(o);
            }
            JsonObject body = new JsonObject();
            body.add("contents", contents);
            if (!systemPrompt.isEmpty()) {
                JsonObject sys = new JsonObject();
                JsonObject sysPart = new JsonObject();
                sysPart.addProperty("text", systemPrompt);
                JsonArray sysParts = new JsonArray(); sysParts.add(sysPart);
                sys.add("parts", sysParts);
                body.add("systemInstruction", sys);
            }

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey;
            HttpResponse<String> resp = HttpUtils.postJson(url, "Bearer " + apiKey, body.toString());
            if (resp.statusCode() >= 400) {
                throw new LLMHttpException(resp.statusCode(),
                        "Gemini HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            return json.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString().trim();
        }
    }

    /**
     * Typed exception carrying the HTTP status code, so KeyRotation can detect
     * 401/402/429 errors and fail over to the next backup key.
     */
    class LLMHttpException extends Exception {
        public final int httpStatus;
        public LLMHttpException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
