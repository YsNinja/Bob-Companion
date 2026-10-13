package com.quackcraft.quackingly.companion;

import com.quackcraft.quackingly.Quackingly;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * Intercepts chat messages from players who have Quackingly summoned.
 *
 * When a player has Quackingly summoned, their regular chat messages (anything
 * that doesn't start with '/') are forwarded to Quackingly instead of being
 * broadcast to the server. This is how Verity works — you just type in chat
 * and the companion responds.
 *
 * Commands (starting with '/') are NOT intercepted — they go to the normal
 * command handler.
 *
 * This is registered server-side via ServerMessageEvents.CHAT_MESSAGE in
 * Quackingly.onInitialize().
 */
public final class ChatInterceptor {

    private ChatInterceptor() {}

    /**
     * Called for every chat message. Returns true if the message was intercepted
     * (forwarded to Quackingly), false if it should be processed normally.
     */
    public static boolean intercept(MinecraftServer server, ServerPlayerEntity sender, String message) {
        if (message == null || message.isBlank()) return false;
        // Don't intercept commands
        if (message.startsWith("/")) return false;

        CompanionManager.CompanionSession session = CompanionManager.getInstance().getSession(sender);
        if (session == null || !session.isAlive()) return false;

        // Intercept: forward to Quackingly, suppress normal chat broadcast
        // Do not write private chat contents into latest.log.
        Quackingly.LOGGER.debug("[Quackingly] Chat received from {}.", sender.getName().getString());
        CompanionManager.getInstance().sendToCompanion(sender, message);
        return true;
    }
}
