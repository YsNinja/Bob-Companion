package com.quackcraft.quackingly.network;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.client.network.ClientCompanionPackets;
import com.quackcraft.quackingly.companion.CompanionManager;
import com.quackcraft.quackingly.voice.VoiceInputHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Server-side packet receivers + senders for Quackingly.
 *
 * Registered in Quackingly#onInitialize (common side) after payload types
 * are registered in QuackinglyPayloads.register().
 */
public final class ServerCompanionPackets {

    private ServerCompanionPackets() {}

    public static void register() {
        // Client -> Server: toggle summon/despawn (K keybind)
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.ToggleSummonPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    context.server().execute(() ->
                            CompanionManager.getInstance().toggle(player));
                });

        // Client -> Server: chat message to companion
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.ChatToCompanionPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    String text = payload.text();
                    context.server().execute(() ->
                            CompanionManager.getInstance().sendToCompanion(player, text));
                });

        // Client -> Server: summon with specific mode (from /quackingly confirmation flow)
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.SummonWithModePayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    String mode = payload.mode();
                    context.server().execute(() ->
                            CompanionManager.getInstance().summonWithMode(player, mode));
                });

        // Client -> Server: player pressed push-to-talk — start capturing mic audio
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.VoiceInputStartPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    context.server().execute(() ->
                            VoiceInputHandler.onStartRecording(player));
                });

        // Client -> Server: player released push-to-talk — stop, transcribe, send to LLM
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.VoiceInputStopPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    context.server().execute(() ->
                            VoiceInputHandler.onStopRecording(player));
                });

        // Client -> Server: toggle always-on listening mute
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.ToggleMutePayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    context.server().execute(() ->
                            VoiceInputHandler.toggleAlwaysOnMute(player));
                });

        // Client -> Server: client-captured mic audio (Pojav fallback when Opus is broken)
        ServerPlayNetworking.registerGlobalReceiver(ClientCompanionPackets.ClientAudioPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    byte[] audio = payload.audio();
                    context.server().execute(() ->
                            VoiceInputHandler.onClientAudio(player, audio));
                });
    }

    /** Server -> Client: tell client to open the "Add Quackingly?" confirmation popup. */
    public static void sendOpenConfirmation(ServerPlayerEntity host) {
        try {
            ServerPlayNetworking.send(host, new ClientCompanionPackets.OpenConfirmationScreenPayload());
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("Failed to send open_confirmation packet", t);
        }
    }

    /** Server -> Client: Quackingly's reply text (triggers TTS on client). */
    public static void sendTtsReply(ServerPlayerEntity host, String replyText) {
        try {
            ServerPlayNetworking.send(host, new ClientCompanionPackets.CompanionReplyPayload(replyText));
        } catch (Throwable t) {
            Quackingly.LOGGER.warn("Failed to send companion_reply packet", t);
        }
    }
}
