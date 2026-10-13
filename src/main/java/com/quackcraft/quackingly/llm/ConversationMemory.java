package com.quackcraft.quackingly.llm;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.config.QuackinglyConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-optimised conversation memory.
 *
 * Strategy (this is what Verity does to "not suck all the tokens"):
 *   1. Keep the last N turns verbatim (N = maxMemoryTurns, default 10).
 *   2. When the buffer overflows, summarise the dropped turns into a single
 *      compressed "Earlier conversation:" system note (≤ maxSummaryTokens).
 *   3. Always prepend the system prompt.
 *
 * Total per-call cost ≈ system prompt (~300 tokens) + summary (~400 tokens) +
 * last N turns (~150 tokens each = 1500 tokens). That's ~2.2k tokens per call,
 * vs unbounded growth if we kept everything.
 */
public class ConversationMemory {
    private final List<ChatMessage> history = new ArrayList<>();
    private String compressedSummary = "";

    public synchronized void reset() {
        history.clear();
        compressedSummary = "";
    }

    public synchronized void addUser(String text) {
        history.add(ChatMessage.user(text));
        compactIfNeeded();
    }

    public synchronized void addAssistant(String text) {
        history.add(ChatMessage.assistant(text));
        compactIfNeeded();
    }

    /**
     * Build the outgoing message list for an LLM call:
     *   [system prompt, optional summary-of-earlier, ...last N turns]
     */
    public synchronized List<ChatMessage> buildForCall(String systemPrompt) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(systemPrompt));
        if (!compressedSummary.isBlank()) {
            out.add(ChatMessage.system(
                    "Earlier conversation summary (for context, may be omitted in your reply):\n"
                            + compressedSummary));
        }
        out.addAll(history);
        return out;
    }

    private void compactIfNeeded() {
        int maxTurns = QuackinglyConfig.get().maxMemoryTurns;
        if (history.size() <= maxTurns) return;

        // Drop the oldest 2 turns and fold them into the summary
        List<ChatMessage> dropped = new ArrayList<>();
        for (int i = 0; i < 2 && !history.isEmpty(); i++) dropped.add(history.remove(0));

        StringBuilder sb = new StringBuilder();
        if (!compressedSummary.isBlank()) sb.append(compressedSummary).append("\n\n");
        for (ChatMessage m : dropped) {
            sb.append(m.role == ChatMessage.Role.USER ? "User" : "Bob").append(": ")
              .append(truncate(m.content, 200)).append("\n");
        }
        compressedSummary = sb.toString().trim();

        // Hard cap the summary length
        int max = QuackinglyConfig.get().maxSummaryTokens * 4;  // 4 chars/token
        if (compressedSummary.length() > max) {
            compressedSummary = compressedSummary.substring(compressedSummary.length() - max);
        }

        Quackingly.LOGGER.debug("[Quackingly] Memory compacted. Summary now {} chars, history {} turns.",
                compressedSummary.length(), history.size());
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
