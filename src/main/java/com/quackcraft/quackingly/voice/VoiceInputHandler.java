package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.companion.CompanionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * Server-side orchestrator for PUSH-TO-TALK voice input.
 *
 * NOTE: Always-on listening (the default mode, like Dr Donut's Verity) is handled
 * entirely by SilenceWatcher + QuackinglyVoiceChatPlugin — no client packets needed.
 * The player just talks normally and SVC's VAD + our silence detection handles
 * sentence boundaries automatically.
 *
 * This class only handles the OPTIONAL push-to-talk override:
 *   - Client holds '-' key → VoiceInputStartPayload → onStartRecording()
 *   - Client releases '-' key → VoiceInputStopPayload → onStopRecording()
 *
 * PTT is useful when:
 *   - The player has a noisy background and always-on picks up too much
 *   - The player wants explicit control over when Quackingly listens
 *   - Always-on is disabled in config
 */
public final class VoiceInputHandler {

    private VoiceInputHandler() {}

    /** Called when client sends VoiceInputStartPayload (PTT pressed). */
    public static void onStartRecording(ServerPlayerEntity player) {
        try {
            if (!isSvcAvailable()) {
                // SVC not installed — client-side Java Sound capture handles this
                return;
            }
            if (!com.quackcraft.quackingly.config.QuackinglyConfig.get().voiceInputEnabled) {
                return;
            }
            CompanionManager.CompanionSession session = CompanionManager.getInstance().getSession(player);
            if (session == null || !session.isAlive()) {
                player.sendMessage(Text.literal("Summon Quackingly first (press K or type /quackingly).")
                        .formatted(Formatting.RED));
                return;
            }
            // PTT overrides always-on — restart the collector fresh for this PTT session
            QuackinglyVoiceChatPlugin.startRecording(player.getUuid());
            player.sendMessage(Text.literal("● Recording... (release to send)")
                    .formatted(Formatting.DARK_RED, Formatting.BOLD));
        } catch (NoClassDefFoundError svc404) {
            // SVC not installed — client-side capture handles voice
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("[Quackingly] Failed to start PTT recording", t);
        }
    }

    /** Called when client sends VoiceInputStopPayload (PTT released). */
    public static void onStopRecording(ServerPlayerEntity player) {
        final MinecraftServer server = player.getServer();
        if (server == null) return;

        final byte[] wavBytes;
        try {
            wavBytes = QuackinglyVoiceChatPlugin.stopRecording(player.getUuid());
        } catch (NoClassDefFoundError svc404) {
            return; // SVC not installed — client-side capture handles this
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("[Quackingly] Failed to stop PTT recording", t);
            return;
        }

        // If always-on is enabled, restart the collector so listening continues
        if (com.quackcraft.quackingly.config.QuackinglyConfig.get().alwaysOnListening
                && !SilenceWatcher.isMuted(player.getUuid())) {
            QuackinglyVoiceChatPlugin.startRecording(player.getUuid());
        }

        if (wavBytes == null || wavBytes.length == 0) {
            player.sendMessage(Text.literal("(no audio captured — did you hold the key long enough?)")
                    .formatted(Formatting.GRAY));
            return;
        }

        player.sendMessage(Text.literal("⟳ Transcribing...")
                .formatted(Formatting.ITALIC, Formatting.DARK_GRAY));

        final ServerPlayerEntity host = player;
        new Thread(() -> {
            try {
                String text = GroqSTT.transcribe(wavBytes);
                if (text == null || text.isBlank()) {
                    server.execute(() -> host.sendMessage(
                            Text.literal("(couldn't make out what you said)")
                                    .formatted(Formatting.GRAY)));
                    return;
                }
                final String transcript = text;
                server.execute(() -> host.sendMessage(
                        Text.literal("[you] " + transcript).formatted(Formatting.ITALIC, Formatting.GRAY)));
                server.execute(() -> CompanionManager.getInstance().sendToCompanion(host, transcript));
            } catch (Exception e) {
                Quackingly.LOGGER.warn("[Quackingly] PTT STT pipeline failed", e);
                server.execute(() -> host.sendMessage(
                        Text.literal("(STT failed: " + e.getMessage() + ")").formatted(Formatting.RED)));
            }
        }, "Quackingly-STT-PTT").start();
    }

    /** Toggle the player's always-on listening mute state. */
    public static void toggleAlwaysOnMute(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        boolean nowMuted = !SilenceWatcher.isMuted(uuid);
        SilenceWatcher.setMuted(uuid, nowMuted);
        if (nowMuted) {
            player.sendMessage(Text.literal("🔇 Quackingly is now muted (always-on listening paused)")
                    .formatted(Formatting.YELLOW));
        } else {
            try {
                QuackinglyVoiceChatPlugin.startRecording(uuid);
            } catch (NoClassDefFoundError svc404) {
                // SVC not installed — client-side capture handles this
            }
            player.sendMessage(Text.literal("🎤 Quackingly is listening (always-on)")
                    .formatted(Formatting.AQUA));
        }
    }

    /**
     * Called when client sends a ClientAudioPayload (client-captured mic audio).
     * This is the Pojav fallback — when SVC's Opus is broken, the client captures
     * audio via Java Sound API and sends the WAV bytes to the server.
     *
     * The server transcribes via GroqSTT and forwards the text to the LLM.
     */
    public static void onClientAudio(ServerPlayerEntity player, byte[] wavBytes) {
        final MinecraftServer server = player.getServer();
        if (server == null) return;
        if (wavBytes == null || wavBytes.length == 0) return;

        // Verify Quackingly is summoned
        CompanionManager.CompanionSession session = CompanionManager.getInstance().getSession(player);
        if (session == null || !session.isAlive()) return;

        player.sendMessage(Text.literal("⟳ Transcribing...")
                .formatted(Formatting.ITALIC, Formatting.DARK_GRAY));

        final ServerPlayerEntity host = player;
        new Thread(() -> {
            try {
                String text = GroqSTT.transcribe(wavBytes);
                if (text == null || text.isBlank()) {
                    server.execute(() -> host.sendMessage(
                            Text.literal("(couldn't make out what you said)")
                                    .formatted(Formatting.GRAY)));
                    return;
                }
                final String transcript = text;
                server.execute(() -> host.sendMessage(
                        Text.literal("[you] " + transcript).formatted(Formatting.ITALIC, Formatting.GRAY)));
                server.execute(() -> CompanionManager.getInstance().sendToCompanion(host, transcript));
            } catch (Exception e) {
                Quackingly.LOGGER.warn("[Quackingly] Client audio STT failed", e);
                server.execute(() -> host.sendMessage(
                        Text.literal("(STT failed: " + e.getMessage() + ")").formatted(Formatting.RED)));
            }
        }, "Quackingly-STT-ClientAudio").start();
    }

    private static boolean isSvcAvailable() {
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
