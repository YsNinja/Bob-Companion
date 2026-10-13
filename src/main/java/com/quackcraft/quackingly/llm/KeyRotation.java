package com.quackcraft.quackingly.llm;

import com.quackcraft.quackingly.Quackingly;

import java.util.ArrayList;
import java.util.List;

/**
 * Rotates through a primary API key + backup keys.
 *
 * When the primary key fails (401, 402, 429, etc.), the next backup is tried.
 * Once a key is marked exhausted, it's skipped for the rest of the session.
 *
 * IMPORTANT: Conversation memory lives in ConversationMemory (per-session),
 * NOT in the key. So when we rotate to a backup key, Quackingly still
 * remembers everything from the conversation. The user's preferences and
 * the conversation history are fully preserved across key swaps.
 */
public class KeyRotation {
    private final List<String> keys = new ArrayList<>();
    private int currentIndex = 0;
    private final List<String> exhausted = new ArrayList<>();

    /**
     * @param primaryKey    The primary API key (may be null/empty).
     * @param backupKeysCsv Comma-separated backup keys (may be null/empty).
     */
    public KeyRotation(String primaryKey, String backupKeysCsv) {
        if (primaryKey != null && !primaryKey.isBlank()) {
            keys.add(primaryKey.trim());
        }
        if (backupKeysCsv != null && !backupKeysCsv.isBlank()) {
            for (String k : backupKeysCsv.split(",")) {
                String trimmed = k == null ? "" : k.trim();
                if (!trimmed.isBlank() && !keys.contains(trimmed)) {
                    keys.add(trimmed);
                }
            }
        }
        Quackingly.LOGGER.info("[Quackingly] KeyRotation initialised with {} key(s).", keys.size());
    }

    /** Returns the current key, or empty string if no keys are configured. */
    public synchronized String current() {
        if (currentIndex >= keys.size()) return "";
        return keys.get(currentIndex);
    }

    /**
     * Mark the current key as exhausted and advance to the next one.
     * @return the new current key, or null if no more backups available.
     */
    public synchronized String advance() {
        if (currentIndex < keys.size()) {
            exhausted.add(keys.get(currentIndex));
            Quackingly.LOGGER.warn("[Quackingly] Key #{} exhausted. {} key(s) remaining.",
                    currentIndex, keys.size() - currentIndex - 1);
        }
        currentIndex++;
        if (currentIndex >= keys.size()) return null;
        return keys.get(currentIndex);
    }

    /** True if there are more keys to try after the current one. */
    public synchronized boolean hasMore() {
        return currentIndex < keys.size() - 1;
    }

    /** Total number of keys configured (primary + backups). */
    public synchronized int totalKeys() { return keys.size(); }

    /** Number of keys that have been exhausted so far. */
    public synchronized int exhaustedCount() { return exhausted.size(); }
}
