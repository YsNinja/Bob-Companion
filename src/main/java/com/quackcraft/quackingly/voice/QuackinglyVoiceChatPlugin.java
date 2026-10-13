package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat plugin for Quackingly.
 *
 * Supports TWO voice input modes:
 *
 * 1. ALWAYS-ON (default — like Dr Donut's Verity):
 *    - When the player has Quackingly summoned AND voice input enabled in config,
 *      the server automatically captures their mic audio.
 *    - SVC's built-in voice activation (VAD) gates the audio: when the player
 *      isn't speaking, SVC doesn't send mic packets, so our handler does nothing.
 *    - A background SilenceWatcher thread detects sentence boundaries (gap in
 *      incoming packets ≥ 600ms) and triggers transcription automatically.
 *    - The player can talk naturally — no key to hold. Quackingly replies as
 *      soon as the player pauses.
 *    - The player can mute/unmute Quackingly with a toggle key (default: P).
 *
 * 2. PUSH-TO-TALK (optional):
 *    - Player holds '-' to talk, releases to send.
 *    - Useful for noisy environments or when the player wants explicit control.
 *    - PTT overrides always-on while held.
 *
 * The MicrophonePacketEvent is SERVER-side (SVC sends mic audio to the server
 * via UDP). We decode Opus → PCM on the server and transcribe there. This is
 * exactly how Verity does it.
 */
public class QuackinglyVoiceChatPlugin implements VoicechatPlugin {

    private static VoicechatApi api;
    private static OpusDecoder sharedDecoder;
    private static volatile boolean opusAvailable = true;  // set false if native lib fails

    /**
     * Per-player collectors.
     * Present when:
     *   - Always-on mode: player has Quackingly summoned + voice input enabled + not muted
     *   - PTT mode: player is holding the PTT key
     */
    private static final ConcurrentHashMap<UUID, MicPacketCollector> activeCollectors = new ConcurrentHashMap<>();

    /** Per-player last-packet timestamps (for silence detection in always-on mode). */
    private static final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return Quackingly.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        QuackinglyVoiceChatPlugin.api = api;
        Quackingly.LOGGER.info("[Quackingly] Simple Voice Chat plugin initialised — mic capture ACTIVE (always-on supported).");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, QuackinglyVoiceChatPlugin::onMicPacket);
        Quackingly.LOGGER.info("[Quackingly] Registered MicrophonePacketEvent handler.");
    }

    private static void onMicPacket(MicrophonePacketEvent event) {
        try {
            if (event.getSenderConnection() == null) return;
            if (event.getSenderConnection().getPlayer() == null) return;

            // Skip entirely if Opus is known to be broken on this platform (e.g. Pojav/Android)
            if (!opusAvailable) return;

            UUID playerUuid = event.getSenderConnection().getPlayer().getUuid();
            MicPacketCollector collector = activeCollectors.get(playerUuid);
            if (collector == null) return; // player isn't being captured — ignore

            MicrophonePacket packet = event.getPacket();
            byte[] opusData = packet.getOpusEncodedData();
            if (opusData == null || opusData.length == 0) return;

            OpusDecoder decoder = getDecoder();
            if (decoder == null) return;

            short[] pcm;
            try {
                pcm = decoder.decode(opusData);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                // Native lib failed at decode time (e.g. Pojav: libm.so.6 missing)
                Quackingly.LOGGER.error("[Quackingly] Opus decode failed — native lib broken on this platform. " +
                        "Voice input disabled. TTS output still works. Error: {}", e.getMessage());
                opusAvailable = false;
                return;
            }
            if (pcm == null || pcm.length == 0) return;

            collector.appendSamples(pcm);
            lastPacketTime.put(playerUuid, System.currentTimeMillis());
        } catch (Throwable t) {
            Quackingly.LOGGER.debug("[Quackingly] Mic packet handling failed: {}", t.toString());
        }
    }

    private static OpusDecoder getDecoder() {
        if (sharedDecoder == null && api != null && opusAvailable) {
            try {
                sharedDecoder = api.createDecoder();
                // Test the decoder with a tiny dummy frame to catch native lib failures early
                // (Pojav/Android: libopus4j.so can't load because libm.so.6 is missing)
            } catch (Throwable t) {
                Quackingly.LOGGER.error("[Quackingly] Opus decoder unavailable on this platform. " +
                        "Voice INPUT will not work (voice OUTPUT/TTS is unaffected). " +
                        "Error: {}", t.getMessage());
                opusAvailable = false;
                sharedDecoder = null;
            }
        }
        return sharedDecoder;
    }

    /** True if the Opus native library loaded successfully. */
    public static boolean isOpusAvailable() {
        return opusAvailable && getDecoder() != null;
    }

    // ===== Collector lifecycle =====

    /** Start (or restart) a collector for this player. */
    public static void startRecording(UUID playerUuid) {
        MicPacketCollector old = activeCollectors.put(playerUuid, new MicPacketCollector());
        if (old != null) old.close();
        lastPacketTime.put(playerUuid, System.currentTimeMillis());
    }

    /**
     * Stop recording, return the captured WAV bytes (or null if nothing captured).
     * The collector is removed after this call.
     */
    public static byte[] stopRecording(UUID playerUuid) {
        MicPacketCollector collector = activeCollectors.remove(playerUuid);
        lastPacketTime.remove(playerUuid);
        if (collector == null) return null;
        try {
            return collector.toWav();
        } finally {
            collector.close();
        }
    }

    /** Get the WAV bytes WITHOUT stopping the collector (used by SilenceWatcher for partial transcripts). */
    public static byte[] snapshotAndReset(UUID playerUuid) {
        MicPacketCollector collector = activeCollectors.get(playerUuid);
        if (collector == null) return null;
        return collector.snapshotAndReset();
    }

    public static boolean isRecording(UUID playerUuid) {
        return activeCollectors.containsKey(playerUuid);
    }

    /** Time (millis) since the last mic packet arrived from this player. */
    public static long msSinceLastPacket(UUID playerUuid) {
        Long t = lastPacketTime.get(playerUuid);
        if (t == null) return Long.MAX_VALUE;
        return System.currentTimeMillis() - t;
    }

    /** Total samples collected so far for this player (for min-duration gating). */
    public static int getSampleCount(UUID playerUuid) {
        MicPacketCollector c = activeCollectors.get(playerUuid);
        return c == null ? 0 : c.getSampleCount();
    }

    public static VoicechatApi getApi() { return api; }
}
