package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.client.network.ClientCompanionPackets;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import javax.sound.sampled.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client-side voice pipeline controller.
 *
 * TWO voice input paths:
 *
 * 1. SVC + Opus (preferred, desktop):
 *    PTT → VoiceInputStartPayload → server captures via MicrophonePacketEvent
 *    Release → VoiceInputStopPayload → server transcribes
 *
 * 2. Java Sound API (Pojav fallback when Opus is broken):
 *    PTT → ClientMicCapture.startCapture() → captures mic locally
 *    Release → ClientMicCapture.stopAndSend() → sends WAV to server via ClientAudioPayload
 *    Server transcribes via GroqSTT → forwards to LLM
 *
 * Always-on mode:
 *    If Opus available: server-side SilenceWatcher handles everything
 *    If Opus broken: client-side ClientMicCapture with VAD (energy threshold + silence gap)
 *
 * Voice OUTPUT (TTS): unchanged — Fish Audio / OpenAI plays via Java Sound SourceDataLine
 */
public final class ClientVoiceController {

    private ClientVoiceController() {}

    private static boolean pttActive = false;
    private static final ConcurrentMap<String, byte[]> ttsCache = new ConcurrentHashMap<>();
    private static final int TTS_CACHE_MAX = 32;

    // Client-side always-on mic capture (when Opus is broken)
    private static Thread clientAlwaysOnThread;
    private static volatile boolean clientAlwaysOnRunning = false;

    // ===== Voice input (push-to-talk) =====

    public static void togglePushToTalk() {
        if (!QuackinglyConfig.get().voiceInputEnabled) {
            MinecraftClient.getInstance().player.sendMessage(
                    Text.literal("Voice input is disabled. Enable it in Mod Menu → Quackingly → Voice.")
                            .formatted(Formatting.RED));
            return;
        }
        if (pttActive) releasePtt();
        else pressPtt();
    }

    public static void pressPtt() {
        try {
            boolean showIndicator = QuackinglyConfig.get().showRecordingIndicator;
            if (ClientMicCapture.isAvailable()) {
                boolean ok = ClientMicCapture.startCapture();
                if (ok) {
                    pttActive = true;
                    if (showIndicator) {
                        MinecraftClient.getInstance().player.sendMessage(
                                Text.literal("● Recording... (release to send)").formatted(Formatting.DARK_RED, Formatting.BOLD));
                    }
                } else {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.literal("Mic capture failed — use text chat.").formatted(Formatting.RED));
                }
            } else {
                ClientCompanionPackets.sendVoiceInputStart();
                pttActive = true;
                if (showIndicator) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.literal("● Recording... (release to send)").formatted(Formatting.DARK_RED, Formatting.BOLD));
                }
            }
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("Failed to start PTT", t);
        }
    }

    public static void releasePtt() {
        if (!pttActive) return;
        pttActive = false;
        try {
            if (ClientMicCapture.isCapturing()) {
                // Pojav fallback — stop and send audio
                ClientMicCapture.stopAndSend();
            } else {
                // SVC path — tell server to stop
                ClientCompanionPackets.sendVoiceInputStop();
            }
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("Failed to release PTT", t);
        }
    }

    public static boolean isPttActive() { return pttActive; }

    // ===== Client-side always-on mic capture (Pojav fallback) =====

    /**
     * Start client-side always-on mic capture with VAD.
     * Used when Opus is broken and always-on listening is enabled.
     */
    public static void startClientAlwaysOn() {
        if (clientAlwaysOnRunning) return;
        if (!ClientMicCapture.isAvailable()) return;
        if (!QuackinglyConfig.get().alwaysOnListening) return;
        if (!QuackinglyConfig.get().voiceInputEnabled) return;

        clientAlwaysOnRunning = true;
        clientAlwaysOnThread = new Thread(() -> {
            Quackingly.LOGGER.info("[Quackingly] Client-side always-on mic capture started (Pojav fallback).");
            while (clientAlwaysOnRunning) {
                try {
                    // Start capturing
                    if (!ClientMicCapture.isCapturing()) {
                        ClientMicCapture.startCapture();
                    }
                    // Wait for silence detection (checks every 200ms)
                    Thread.sleep(200);
                    // Check if we should send
                    if (ClientMicCapture.checkAlwaysOnSilence()) {
                        ClientMicCapture.stopAndSend();
                        // Brief pause before restarting
                        Thread.sleep(100);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    Quackingly.LOGGER.debug("[Quackingly] Always-on mic tick error: {}", t.toString());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
            ClientMicCapture.stopCapture();
            Quackingly.LOGGER.info("[Quackingly] Client-side always-on mic capture stopped.");
        }, "Quackingly-ClientAlwaysOn");
        clientAlwaysOnThread.setDaemon(true);
        clientAlwaysOnThread.start();
    }

    public static void stopClientAlwaysOn() {
        clientAlwaysOnRunning = false;
        clientAlwaysOnThread = null;
        ClientMicCapture.stopCapture();
    }

    // ===== Voice output (TTS playback) =====

    public static void onCompanionReply(String replyText) {
        if (replyText == null || replyText.isBlank()) return;

        TTSProvider provider = TTSProvider.fromConfig();
        if (!provider.isReady()) {
            Quackingly.LOGGER.debug("[Quackingly] TTS provider '{}' not ready; skipping voice.",
                    provider.getProviderName());
            return;
        }

        new Thread(() -> {
            try {
                byte[] mp3 = ttsCache.computeIfAbsent(replyText, t -> {
                    try {
                        return provider.synthesise(t);
                    } catch (Exception e) {
                        Quackingly.LOGGER.warn("TTS synthesis failed (provider={}): {}",
                                provider.getProviderName(), e.getMessage());
                        return new byte[0];
                    }
                });
                if (mp3 != null && mp3.length > 0) {
                    playMp3AtCompanion(mp3);
                }
                if (ttsCache.size() > TTS_CACHE_MAX) {
                    ttsCache.clear();
                }
            } catch (Throwable t) {
                Quackingly.LOGGER.warn("TTS playback pipeline failed", t);
            }
        }, "Quackingly-TTS").start();
    }

    public static void playMp3AtCompanion(byte[] mp3Bytes) {
        MinecraftClient.getInstance().execute(() -> {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("quackingly_tts_", ".mp3");
                Files.write(tmp, mp3Bytes);

                AudioInputStream in = AudioSystem.getAudioInputStream(tmp.toFile());
                AudioFormat baseFormat = in.getFormat();
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false);
                AudioInputStream din = AudioSystem.getAudioInputStream(decodedFormat, in);

                SourceDataLine line = AudioSystem.getSourceDataLine(decodedFormat);
                line.open(decodedFormat);
                line.start();
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    gain.setValue(-3.0f);
                }

                byte[] buf = new byte[4096];
                int n;
                while ((n = din.read(buf)) != -1) line.write(buf, 0, n);
                line.drain();
                line.stop();
                line.close();
                din.close();
                in.close();
            } catch (Throwable t) {
                Quackingly.LOGGER.warn("TTS playback failed", t);
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        });
    }
}
