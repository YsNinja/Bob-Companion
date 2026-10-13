package com.quackcraft.quackingly.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.llm.ProviderDetector;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent config for Bob. Stored as JSON in config/quackingly.json
 *
 * Fields are deliberately simple — the Mod Menu screen reads/writes these directly.
 */
public class QuackinglyConfig {
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("bob-companion.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ConfigData data = new ConfigData();

    public static ConfigData get() { return data; }

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                ConfigData loaded = GSON.fromJson(json, ConfigData.class);
                if (loaded != null) data = loaded;
            }
        } catch (Exception e) {
            Quackingly.LOGGER.warn("Failed to load Quackingly config, using defaults", e);
        }
        // Refresh derived fields
        data.detectedProvider = ProviderDetector.detect(data.apiKey);
        if (data.defaultSkinUser == null || data.defaultSkinUser.isBlank()) {
            data.defaultSkinUser = "Steve";
        }
        // Bob has one friendly personality; old or hand-edited values cannot enable another mode.
        data.defaultMode = "normal";
        // Migrate retired Groq defaults and the historic spelling typo.
        if (data.model != null) {
            if (data.model.equals("llama-3.3-70b-verseatile")
                    || data.model.equals("llama-3.3-70b-versatile")) {
                data.model = "openai/gpt-oss-20b";
            }
        }
        if (data.model == null || data.model.isBlank()) {
            data.model = ProviderDetector.defaultModelFor(data.detectedProvider);
        }
        save();
    }

    public static void save() {
        data.detectedProvider = ProviderDetector.detect(data.apiKey);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException e) {
            Quackingly.LOGGER.error("Failed to save Quackingly config", e);
        }
    }

    public static class ConfigData {
        // No shared or bundled credentials. The player supplies their own provider key if desired.
        public String apiKey = "";
        public String detectedProvider = "groq";
        public String customBaseUrl = "";
        public String model = "openai/gpt-oss-20b";
        public String backupApiKeys = "";

        // TTS provider selection
        public String ttsProvider = "fish_audio"; // "fish_audio" (default, free) | "openai"

        // Optional text-to-speech settings; voice is not enabled by default.
        public String fishAudioApiKey = "";
        public String fishVoiceReferenceId = "063421453a724a6a8063255c867e9589";
        public String fishBackupVoiceReferenceId = "99906ab84a8d4e16897b357cf633a46b";

        // OpenAI TTS (fallback — paid, but fast)
        public String openaiTtsApiKey = "";
        public String openaiTtsVoice = "alloy";  // alloy, echo, fable, onyx, nova, shimmer
        public String openaiTtsModel = "tts-1";  // tts-1 (fast) or tts-1-hd (quality)

        // Response mode: text-only by default.
        public String responseMode = "chat_only"; // text-only default keeps voice services idle

        // Behaviour
        public String defaultMode = "normal";
        public boolean voiceInputEnabled = false; // opt in to microphone use
        public boolean alwaysOnListening = false; // microphone remains off unless explicitly enabled
        public int silenceThresholdMs = 600;     // gap in ms to detect end of sentence
        public int minUtteranceMs = 400;         // ignore utterances shorter than this (filters out coughs etc)
        public int maxUtteranceMs = 10000;       // force-transcribe if utterance exceeds this (prevents runaway)
        public boolean showRecordingIndicator = true; // show "● Recording..." when mic is active
        public int voiceActivationThreshold = 300; // VAD energy threshold (0-2000, lower = more sensitive)
        public boolean autoSummon = false;
        public boolean followPlayer = true;      // companion AI: follow host
        public boolean lookAtPlayer = true;      // companion AI: face host when close
        public boolean autoDefend = true;         // Bob warns and fights nearby hostile mobs
        public boolean autoSleep = true;            // Bob uses a safe nearby bed at night

        // Skin
        public String defaultSkinUser = "Steve";

        // Token optimisation
        public int maxMemoryTurns = 6;          // small bounded conversation history
        public int maxSummaryTokens = 200;      // compact summary of older turns

        // Voice input (Groq Whisper STT — for future voice input feature)
        public String sttModel = "whisper-large-v3";
    }

}

