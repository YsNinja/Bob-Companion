package com.quackcraft.quackingly.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Collects decoded PCM samples from SVC's MicrophonePacketEvent and packages
 * them as a WAV file suitable for Groq Whisper STT.
 *
 * SVC delivers Opus frames at 48 kHz mono, 20ms each (960 samples per frame).
 * After decoding via OpusDecoder.decode(byte[]) we get short[] PCM samples
 * (16-bit signed, little-endian in the WAV).
 *
 * WAV format:
 *   RIFF header (12 bytes)
 *   fmt  chunk (16 bytes for PCM)
 *   data chunk (8-byte header + PCM bytes)
 *
 * Total overhead: 44 bytes + PCM payload.
 *
 * Min duration for Groq Whisper: ~100ms (5 frames). Below that, STT returns "".
 */
public class MicPacketCollector {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private final ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(64 * 1024);  // 64KB initial
    private int sampleCount = 0;

    public MicPacketCollector() {
        // empty
    }

    /** Append decoded PCM samples (short[], 16-bit signed). */
    public void appendSamples(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        ByteBuffer bb = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = bb.asShortBuffer();
        sb.put(pcm);
        pcmOut.write(bb.array(), 0, bb.array().length);
        sampleCount += pcm.length;
    }

    /** Legacy method — kept for backward compat. Accepts raw Opus bytes (no-op here; decoding happens in the plugin). */
    public void append(byte[] opusFrame) {
        // no-op — use appendSamples(short[]) instead
    }

    /**
     * Build a 16-bit PCM mono 48kHz WAV file from the collected samples.
     * Returns null if no samples were collected.
     */
    public byte[] toWav() {
        byte[] pcm = pcmOut.toByteArray();
        if (pcm.length == 0) return null;

        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 44);

        int chunkSize = 36 + pcm.length;
        int subchunk2Size = pcm.length;
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        // RIFF header
        wav.writeBytes("RIFF".getBytes());
        writeIntLE(wav, chunkSize);
        wav.writeBytes("WAVE".getBytes());

        // fmt chunk
        wav.writeBytes("fmt ".getBytes());
        writeIntLE(wav, 16);                // PCM chunk size
        writeShortLE(wav, (short) 1);       // PCM format
        writeShortLE(wav, (short) CHANNELS);
        writeIntLE(wav, SAMPLE_RATE);
        writeIntLE(wav, byteRate);
        writeShortLE(wav, (short) blockAlign);
        writeShortLE(wav, (short) BITS_PER_SAMPLE);

        // data chunk
        wav.writeBytes("data".getBytes());
        writeIntLE(wav, subchunk2Size);
        wav.writeBytes(pcm);

        return wav.toByteArray();
    }

    /**
     * Take a snapshot of the current PCM, build a WAV, then reset the collector
     * so it can continue accumulating fresh audio. Used by the SilenceWatcher
     * for sentence-boundary transcription without stopping the collector.
     */
    public synchronized byte[] snapshotAndReset() {
        byte[] wav = toWav();
        // Reset internal state
        pcmOut.reset();
        sampleCount = 0;
        return wav;
    }

    public int getSampleCount() { return sampleCount; }
    public double getDurationSeconds() { return (double) sampleCount / SAMPLE_RATE; }

    public void close() {
        try { pcmOut.close(); } catch (Exception ignored) {}
    }

    private static void writeIntLE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream o, short v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }
}
