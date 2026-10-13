package com.quackcraft.quackingly.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.companion.CompanionManager;
import com.quackcraft.quackingly.network.ServerCompanionPackets;
import com.quackcraft.quackingly.skin.SkinApplier;
import com.quackcraft.quackingly.skin.SkinLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Commands for Quackingly.
 *
 * /quackingly              — opens confirmation popup (Are you sure? → mode select → spawn)
 * /quack summon            — direct summon (uses config default mode)
 * /quack despawn           — direct despawn
 * /quack skin set <user>   — apply that player's skin to Quackingly
 * /quack skin reset        — back to default skin
 * /quack mode normal       — switch to normal mode on live companion
 * /quack mode unhinged     — switch to unhinged mode on live companion
 */
public class QuackSkinCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /bob — opens the friendly companion confirmation popup
        dispatcher.register(literal("bob")
                .executes(QuackSkinCommand::quackinglyConfirm));

        // Backwards-compatible alias
        dispatcher.register(literal("quackingly")
                .executes(QuackSkinCommand::quackinglyConfirm));

        // /q <message> — quick talk to Quackingly (short, easy to type)
        dispatcher.register(literal("q")
                .then(argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> quickSay(ctx))));

        // /quack — direct command tree
        dispatcher.register(literal("quack")
            .then(literal("skin")
                .then(literal("set")
                    .then(argument("username", StringArgumentType.word())
                        .executes(QuackSkinCommand::skinSet)))
                .then(literal("reset")
                    .executes(QuackSkinCommand::skinReset)))
            .then(literal("summon").executes(QuackSkinCommand::summon))
            .then(literal("despawn").executes(QuackSkinCommand::despawn))
            .then(literal("mode").then(literal("normal").executes(c -> mode(c, "normal"))))
        );
    }

    /** /q <message> — quick way to talk to Quackingly without typing /quack say */
    private static int quickSay(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String msg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "message");
        CompanionManager.getInstance().sendToCompanion(player, msg);
        return 1;
    }

    /**
     * /quackingly — sends a packet to the client to open the confirmation popup.
     * The popup asks "Are you sure you want to add Quackingly to this world?"
     * If the player clicks Yes, a mode-select screen opens (Normal/Unhinged).
     * The mode select then sends a SummonWithModePayload to the server.
     */
    private static int quackinglyConfirm(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFeedback(() ->
                    Text.literal("Run this as a player.").formatted(Formatting.RED), false);
            return 0;
        }
        // Check if already summoned — if so, offer to despawn instead
        CompanionManager.CompanionSession existing = CompanionManager.getInstance().getSession(player);
        if (existing != null && existing.isAlive()) {
            // Already summoned — toggle (despawn)
            CompanionManager.getInstance().toggle(player);
            return 1;
        }
        // Not summoned — send packet to open confirmation popup on client
        ServerCompanionPackets.sendOpenConfirmation(player);
        ctx.getSource().sendFeedback(() ->
                Text.literal("Opening Quackingly confirmation...").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int skinSet(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFeedback(() -> Text.literal("Run as a player.").formatted(Formatting.RED), false);
            return 0;
        }
        String username = StringArgumentType.getString(ctx, "username");
        CompanionManager.CompanionSession s = CompanionManager.getInstance().getSession(player);
        if (s == null || !s.isAlive()) {
            player.sendMessage(Text.literal("Summon Quackingly first (/quackingly).").formatted(Formatting.RED));
            return 0;
        }
        try {
            SkinLoader.FetchedSkin skin = SkinLoader.fetch(username);
            SkinApplier.applySkinFromUsername(s.getFakePlayer(), username);
            player.sendMessage(Text.translatable("chat.quackingly.skin_set", username).formatted(Formatting.GREEN));
        } catch (Exception e) {
            player.sendMessage(Text.translatable("chat.quackingly.skin_failed", username).formatted(Formatting.RED));
            Quackingly.LOGGER.warn("Skin set failed for '{}': {}", username, e.getMessage());
        }
        return 1;
    }

    private static int skinReset(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        CompanionManager.CompanionSession s = CompanionManager.getInstance().getSession(player);
        if (s == null || !s.isAlive()) {
            player.sendMessage(Text.literal("Summon Quackingly first.").formatted(Formatting.RED));
            return 0;
        }
        SkinApplier.applyDefaultSkin(s.getFakePlayer());
        player.sendMessage(Text.literal("Skin reset to default.").formatted(Formatting.GREEN));
        return 1;
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        CompanionManager.getInstance().toggle(p);
        return 1;
    }

    private static int despawn(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        CompanionManager.CompanionSession s = CompanionManager.getInstance().getSession(p);
        if (s != null && s.isAlive()) CompanionManager.getInstance().toggle(p);
        return 1;
    }

    private static int mode(CommandContext<ServerCommandSource> ctx, String m) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) return 0;
        CompanionManager.CompanionSession s = CompanionManager.getInstance().getSession(p);
        if (s == null || !s.isAlive()) {
            p.sendMessage(Text.literal("Summon Quackingly first.").formatted(Formatting.RED));
            return 0;
        }
        s.setMode(m);
        p.sendMessage(Text.literal("Quackingly mode: " + m).formatted(Formatting.AQUA));
        return 1;
    }
}
