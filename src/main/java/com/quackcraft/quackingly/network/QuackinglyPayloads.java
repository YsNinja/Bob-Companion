package com.quackcraft.quackingly.network;

import com.quackcraft.quackingly.Quackingly;
import com.quackcraft.quackingly.client.network.ClientCompanionPackets;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers all custom payload types with Fabric's networking registry.
 *
 * CRITICAL: In Fabric 1.21.1, you MUST register payload types BEFORE registering
 * receivers. If you skip this step, the mod crashes on startup with:
 *   "Cannot register handler as no payload type has been registered with name
 *    'quackingly:toggle_summon' for SERVERBOUND PLAY"
 *
 * This class is called FIRST in Quackingly.onInitialize(), before
 * ServerCompanionPackets.register().
 */
public final class QuackinglyPayloads {

    private QuackinglyPayloads() {}

    public static void register() {
        // Client -> Server (playC2S) payloads
        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.ToggleSummonPayload.ID,
                ClientCompanionPackets.ToggleSummonPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.ChatToCompanionPayload.ID,
                ClientCompanionPackets.ChatToCompanionPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.SummonWithModePayload.ID,
                ClientCompanionPackets.SummonWithModePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.VoiceInputStartPayload.ID,
                ClientCompanionPackets.VoiceInputStartPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.VoiceInputStopPayload.ID,
                ClientCompanionPackets.VoiceInputStopPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.ToggleMutePayload.ID,
                ClientCompanionPackets.ToggleMutePayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                ClientCompanionPackets.ClientAudioPayload.ID,
                ClientCompanionPackets.ClientAudioPayload.CODEC);

        // Server -> Client (playS2C) payloads
        PayloadTypeRegistry.playS2C().register(
                ClientCompanionPackets.CompanionReplyPayload.ID,
                ClientCompanionPackets.CompanionReplyPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ClientCompanionPackets.OpenConfirmationScreenPayload.ID,
                ClientCompanionPackets.OpenConfirmationScreenPayload.CODEC);

        Quackingly.LOGGER.info("[Quackingly] Registered 9 payload types (7 C2S, 2 S2C).");
    }
}
