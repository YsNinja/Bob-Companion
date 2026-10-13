package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.companion.CompanionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background thread that watches for sentence boundaries in always-on mode.
 *
 * For each player who has always-on listening active, we track:
 *   - Whether they're currently in an "utterance" (recently received mic packets)
 *   - The last time a mic packet arrived
 *
 * When the player stops speaking for ≥ silenceThresholdMs (default 600ms), we
 * treat that as a sentence boundary:
 *   1. Snapshot the collected WAV (without stopping the collector)
 *   2. Reset the collector so it can accumulate the next utterance
 *   3. If the utterance is long enough (≥ minUtteranceMs), transcribe it
 *   4. Forward the transcript to CompanionManager → LLM → reply
 *
 * Also handles maxUtteranceMs: if a single utterance exceeds this (default 10s),
 * we force-transcribe it mid-speech to prevent runaway memory usage. This lets
 * Quackingly reply WHILE the user is still talking (like Verity does).
 *
 * The watcher runs every 100ms (10Hz) — cheap enough to not impact server tick.
 */
public final class SilenceWatcher {

    private SilenceWatcher() {}

    private static volatile boolean running = false;
    private static Thread watcherThread;
    private static MinecraftServer server;

    /** Per-player mute state (always-on can be muted without disabling the feature). */
    private static final ConcurrentHashMap<UUID, Boolean> muted = new ConcurrentHashMap<>();

    /** Per-player "currently in utterance" state. */
    private static final ConcurrentHashMap<UUID, Long> utteranceStartTime = new ConcurrentHashMap<>();

    public static void start(MinecraftServer server) {
        if (running) return;
        SilenceWatcher.server = server;
        running = true;
        watcherThread = new Thread(SilenceWatcher::watch, "Quackingly-SilenceWatcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        Quackingly.LOGGER.info("[Quackingly] SilenceWatcher started — always-on voice active.");
    }

    public static void stop() {
        running = false;
        watcherThread = null;
        utteranceStartTime.clear();
    }

    /** Mute/unmute a player's always-on listening (toggled by client key). */
    public static void setMuted(UUID playerUuid, boolean isMuted) {
        if (isMuted) {
            muted.put(playerUuid, true);
            // Stop any active collector
            try {
                QuackinglyVoiceChatPlugin.stopRecording(playerUuid);
            } catch (NoClassDefFoundError svc404) {
                // SVC not installed — nothing to stop
            }
            utteranceStartTime.remove(playerUuid);
        } else {
            muted.remove(playerUuid);
        }
    }

    public static boolean isMuted(UUID playerUuid) {
        return muted.containsKey(playerUuid);
    }

    /** Called when a player's Quackingly session starts — begin always-on capture. */
    public static void onSessionStart(UUID playerUuid) {
        var cfg = com.quackcraft.quackingly.config.QuackinglyConfig.get();
        if (!cfg.voiceInputEnabled || !cfg.alwaysOnListening) return;
        try {
            if (!QuackinglyVoiceChatPlugin.isOpusAvailable()) {
                Quackingly.LOGGER.debug("[Quackingly] SVC/Opus not available for {} — client-side capture handles voice.", playerUuid);
                return;
            }
            if (!muted.containsKey(playerUuid)) {
                QuackinglyVoiceChatPlugin.startRecording(playerUuid);
            }
        } catch (NoClassDefFoundError svc404) {
            // SVC not installed — client-side Java Sound capture handles voice input
        }
    }

    /** Called when a player's Quackingly session ends — stop capture. */
    public static void onSessionEnd(UUID playerUuid) {
        try {
            QuackinglyVoiceChatPlugin.stopRecording(playerUuid);
        } catch (NoClassDefFoundError svc404) {
            // SVC not installed — nothing to stop
        }
        utteranceStartTime.remove(playerUuid);
    }

    private static void watch() {
        while (running) {
            try {
                Thread.sleep(100); // 10Hz check
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (server == null) continue;

            try {
                long now = System.currentTimeMillis();
                var cfg = com.quackcraft.quackingly.config.QuackinglyConfig.get();
                if (!cfg.voiceInputEnabled || !cfg.alwaysOnListening) continue;

                int silenceMs = cfg.silenceThresholdMs;
                int minMs = cfg.minUtteranceMs;
                int maxMs = cfg.maxUtteranceMs;

                // Check each player who has an active collector
                for (var entry : CompanionManager.getInstance().sessionsView()) {
                    ServerPlayerEntity player = entry.getHost();
                    if (player == null) continue;
                    UUID uuid = player.getUuid();

                    if (muted.containsKey(uuid)) continue;
                    // Guard all SVC plugin calls — if SVC isn't installed, skip server-side VAD
                    boolean isRec;
                    try {
                        isRec = QuackinglyVoiceChatPlugin.isRecording(uuid);
                    } catch (NoClassDefFoundError svc404) { continue; }
                    if (!isRec) continue;

                    long sinceLast = QuackinglyVoiceChatPlugin.msSinceLastPacket(uuid);
                    int sampleCount = QuackinglyVoiceChatPlugin.getSampleCount(uuid);
                    // 48kHz mono = 48000 samples/sec → ms = samples / 48
                    int utteranceMs = sampleCount / 48;

                    Long startTs = utteranceStartTime.get(uuid);
                    boolean inUtterance = startTs != null;

                    if (!inUtterance) {
                        // Not currently in an utterance — wait for speech to start.
                        // SVC only sends packets when VAD detects speech, so any packet means speech started.
                        if (sinceLast < 200) {
                            // Speech just started
                            utteranceStartTime.put(uuid, now);
                        }
                        continue;
                    }

                    // In an utterance — check for sentence boundary or max-length cutoff
                    boolean silenceBoundary = sinceLast >= silenceMs;
                    boolean maxLength = utteranceMs >= maxMs;

                    if (silenceBoundary || maxLength) {
                        // Sentence ended (or max length hit) — snapshot, reset, transcribe
                        byte[] wav;
                        try {
                            wav = QuackinglyVoiceChatPlugin.snapshotAndReset(uuid);
                        } catch (NoClassDefFoundError svc404) { continue; }
                        utteranceStartTime.remove(uuid);

                        if (wav == null || wav.length == 0) continue;
                        if (utteranceMs < minMs) {
                            // Too short — probably a cough or background noise, skip
                            continue;
                        }

                        // Transcribe on a worker thread (don't block the watcher)
                        final ServerPlayerEntity host = player;
                        final String mode = maxLength ? "(max-length)" : "(sentence-end)";
                        new Thread(() -> transcribeAndForward(host, wav, mode), "Quackingly-STT").start();
                    }
                }
            } catch (Throwable t) {
                Quackingly.LOGGER.debug("[Quackingly] SilenceWatcher tick error: {}", t.toString());
            }
        }
    }

    private static void transcribeAndForward(ServerPlayerEntity player, byte[] wavBytes, String triggerMode) {
        MinecraftServer srv = player.getServer();
        if (srv == null) return;
        try {
            String text = GroqSTT.transcribe(wavBytes);
            if (text == null || text.isBlank()) {
                // Couldn't transcribe — don't bother the player
                Quackingly.LOGGER.debug("[Quackingly] STT returned empty for {} {}", player.getName().getString(), triggerMode);
                return;
            }
            final String transcript = text.trim();
            Quackingly.LOGGER.info("[Quackingly] Voice input from {}: \"{}\" {}",
                    player.getName().getString(), transcript, triggerMode);

            // Show the player what was heard (subtle indicator)
            srv.execute(() -> player.sendMessage(
                    Text.literal("[you] " + transcript).formatted(Formatting.ITALIC, Formatting.GRAY)));

            // Forward to companion
            srv.execute(() -> CompanionManager.getInstance().sendToCompanion(player, transcript));
        } catch (Exception e) {
            Quackingly.LOGGER.warn("[Quackingly] Always-on STT failed for {}: {}",
                    player.getName().getString(), e.getMessage());
        }
    }
}
