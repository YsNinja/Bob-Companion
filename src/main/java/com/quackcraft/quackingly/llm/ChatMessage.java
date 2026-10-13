package com.quackcraft.quackingly.llm;

/**
 * Chat message used both for memory and for outgoing LLM calls.
 * Deliberately minimal to keep token usage low.
 */
public class ChatMessage {
    public enum Role { SYSTEM, USER, ASSISTANT }

    public final Role role;
    public final String content;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String c) { return new ChatMessage(Role.SYSTEM, c); }
    public static ChatMessage user(String c) { return new ChatMessage(Role.USER, c); }
    public static ChatMessage assistant(String c) { return new ChatMessage(Role.ASSISTANT, c); }

    /** Approximate token estimate: ~4 chars per token for English text. */
    public int approxTokens() {
        return Math.max(1, content.length() / 4);
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
