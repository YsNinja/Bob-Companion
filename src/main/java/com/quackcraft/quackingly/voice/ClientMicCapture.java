package com.quackcraft.quackingly.voice;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.client.network.ClientCompanionPackets;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;

/**
 * Client-side microphone capture using Java Sound API (TargetDataLine).
 *
 * This is the FALLBACK voice input path — used when SVC's Opus decoder is broken
 * (which is the case on Pojav/Android where libopus4j.so can't load).
 *
 * Flow:
 *   1. Client presses PTT key (-) or always-on triggers
 *   2. This class opens a TargetDataLine at 16kHz 16-bit mono
 *   3. Captures audio into a buffer
 *   4. On stop: builds a WAV (16kHz mono 16-bit, Groq Whisper format)
 *   5. Sends WAV bytes to server via ClientAudioPayload packet
 *   6. Server transcribes via GroqSTT → forwards to LLM
 *
 * If TargetDataLine can't open (e.g. on some Android devices), it fails gracefully
 * and the user is told to use text chat.
 *
 * VAD (Voice Activity Detection) for always-on mode:
 *   Simple energy threshold — if RMS of the last 100ms of audio is below a threshold,
 *   we consider it silence. When silence persists for 600ms after speech, we send.
 */
public final class ClientMicCapture {

    private ClientMicCapture() {}

    private static final int SAMPLE_RATE = 16000;  // 16kHz — what Groq Whisper expects
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final AudioFormat FORMAT = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);

    private static volatile boolean capturing = false;
    private static TargetDataLine micLine;
    private static ByteArrayOutputStream captureBuffer;
    private static long captureStartTime;
    private static long lastLoudAudioTime;

    // VAD thresholds (silence gap and min/max are fixed; energy threshold is configurable)
    private static final long VAD_SILENCE_MS = 600;            // silence gap to trigger send
    private static final long MIN_UTTERANCE_MS = 400;           // ignore very short clips
    private static final long MAX_UTTERANCE_MS = 10000;         // force send if too long

    /** Check if Java Sound mic capture is available on this platform. */
    public static boolean isAvailable() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            return AudioSystem.isLineSupported(info);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start capturing mic audio. Returns true if capture started successfully. */
    public static boolean startCapture() {
        if (capturing) return true;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(FORMAT, SAMPLE_RATE * 2);  // 1-second buffer
            micLine.start();
            captureBuffer = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 10);  // 10s max
            captureStartTime = System.currentTimeMillis();
            lastLoudAudioTime = 0;
            capturing = true;

            // Read thread — reads audio data from the mic line
            new Thread(() -> {
                byte[] readBuf = new byte[1024];
                while (capturing && micLine != null) {
                    int n = micLine.read(readBuf, 0, readBuf.length);
                    if (n > 0) {
                        captureBuffer.write(readBuf, 0, n);
                        // VAD: check if this chunk is "loud" (speech)
                        if (isSpeech(readBuf, n)) {
                            lastLoudAudioTime = System.currentTimeMillis();
                        }
                    }
                }
            }, "Quackingly-MicCapture").start();

            return true;
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("[Quackingly] Client mic capture failed to start: {}", t.getMessage());
            capturing = false;
            return false;
        }
    }

    /**
     * Stop capturing, build WAV, send to server.
     * Returns true if audio was sent.
     */
    public static boolean stopAndSend() {
        if (!capturing) return false;
        capturing = false;

        try { if (micLine != null) { micLine.stop(); micLine.close(); } }
        catch (Throwable ignored) {}
        micLine = null;

        if (captureBuffer == null) return false;
        byte[] pcm = captureBuffer.toByteArray();
        captureBuffer = null;

        if (pcm.length == 0) return false;

        long durationMs = (long) ((double) pcm.length / (SAMPLE_RATE * 2) * 1000);
        if (durationMs < MIN_UTTERANCE_MS) {
            Quackingly.LOGGER.debug("[Quackingly] Client mic clip too short ({}ms), skipping.", durationMs);
            return false;
        }

        byte[] wav = buildWav(pcm);
        Quackingly.LOGGER.info("[Quackingly] Sending {} bytes of mic audio to server ({}ms).",
                wav.length, durationMs);

        // Send to server — server will transcribe via GroqSTT
        ClientCompanionPackets.sendClientAudio(wav);
        return true;
    }

    /** Check if always-on mode should send (silence detected after speech). */
    public static boolean checkAlwaysOnSilence() {
        if (!capturing || lastLoudAudioTime == 0) return false;
        long sinceLoud = System.currentTimeMillis() - lastLoudAudioTime;
        long totalDuration = System.currentTimeMillis() - captureStartTime;
        return sinceLoud >= VAD_SILENCE_MS || totalDuration >= MAX_UTTERANCE_MS;
    }

    /** Stop without sending (for mute toggle). */
    public static void stopCapture() {
        capturing = false;
        try { if (micLine != null) { micLine.stop(); micLine.close(); } }
        catch (Throwable ignored) {}
        micLine = null;
        captureBuffer = null;
    }

    public static boolean isCapturing() { return capturing; }

    /** Simple energy-based VAD: compute RMS of the audio chunk. Uses configurable threshold. */
    private static boolean isSpeech(byte[] data, int len) {
        if (len < 2) return false;
        long sum = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            sum += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sum / samples);
        // Use the configurable threshold from QuackinglyConfig
        double threshold = com.quackcraft.quackingly.config.QuackinglyConfig.get().voiceActivationThreshold;
        return rms > threshold;
    }

    /** Build a 16kHz mono 16-bit WAV from raw PCM. */
    private static byte[] buildWav(byte[] pcm) {
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 44);
        int chunkSize = 36 + pcm.length;
        int byteRate = SAMPLE_RATE * CHANNELS * SAMPLE_SIZE_BITS / 8;
        int blockAlign = CHANNELS * SAMPLE_SIZE_BITS / 8;

        wav.writeBytes("RIFF".getBytes());
        writeIntLE(wav, chunkSize);
        wav.writeBytes("WAVE".getBytes());
        wav.writeBytes("fmt ".getBytes());
        writeIntLE(wav, 16);
        writeShortLE(wav, (short) 1);
        writeShortLE(wav, (short) CHANNELS);
        writeIntLE(wav, SAMPLE_RATE);
        writeIntLE(wav, byteRate);
        writeShortLE(wav, (short) blockAlign);
        writeShortLE(wav, (short) SAMPLE_SIZE_BITS);
        wav.writeBytes("data".getBytes());
        writeIntLE(wav, pcm.length);
        wav.writeBytes(pcm);
        return wav.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
    private static void writeShortLE(ByteArrayOutputStream o, short v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }
}
