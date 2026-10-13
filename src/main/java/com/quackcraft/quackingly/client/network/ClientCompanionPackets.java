package com.quackcraft.quackingly.client.network;

import com.quackcraft.quackingly.Quackingly;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * All custom network payloads for Quackingly.
 *
 * Client -> Server (C2S):
 *   ToggleSummonPayload       — toggle spawn/despawn (K keybind)
 *   ChatToCompanionPayload    — send text to Quackingly
 *   SummonWithModePayload     — spawn Quackingly with a specific mode (from /quackingly flow)
 *
 * Server -> Client (S2C):
 *   CompanionReplyPayload          — Quackingly's reply text (triggers TTS)
 *   OpenConfirmationScreenPayload  — tells client to open the "Add Quackingly?" popup
 */
public class ClientCompanionPackets {

    // ===== Client -> Server =====

    public record ToggleSummonPayload() implements CustomPayload {
        public static final CustomPayload.Id<ToggleSummonPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "toggle_summon"));
        public static final PacketCodec<RegistryByteBuf, ToggleSummonPayload> CODEC =
                PacketCodec.unit(new ToggleSummonPayload());
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public record ChatToCompanionPayload(String text) implements CustomPayload {
        public static final CustomPayload.Id<ChatToCompanionPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "chat_to_companion"));
        public static final PacketCodec<RegistryByteBuf, ChatToCompanionPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.STRING, ChatToCompanionPayload::text, ChatToCompanionPayload::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
        public String text() { return text; }
    }

    public record SummonWithModePayload(String mode) implements CustomPayload {
        public static final CustomPayload.Id<SummonWithModePayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "summon_with_mode"));
        public static final PacketCodec<RegistryByteBuf, SummonWithModePayload> CODEC =
                PacketCodec.tuple(PacketCodecs.STRING, SummonWithModePayload::mode, SummonWithModePayload::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
        public String mode() { return mode; }
    }

    /** Client → Server: player pressed push-to-talk key, start capturing mic audio. */
    public record VoiceInputStartPayload() implements CustomPayload {
        public static final CustomPayload.Id<VoiceInputStartPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "voice_input_start"));
        public static final PacketCodec<RegistryByteBuf, VoiceInputStartPayload> CODEC =
                PacketCodec.unit(new VoiceInputStartPayload());
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client → Server: player released push-to-talk key, stop capturing and transcribe. */
    public record VoiceInputStopPayload() implements CustomPayload {
        public static final CustomPayload.Id<VoiceInputStopPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "voice_input_stop"));
        public static final PacketCodec<RegistryByteBuf, VoiceInputStopPayload> CODEC =
                PacketCodec.unit(new VoiceInputStopPayload());
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client → Server: toggle always-on listening mute (so player can mute Quackingly's mic). */
    public record ToggleMutePayload() implements CustomPayload {
        public static final CustomPayload.Id<ToggleMutePayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "toggle_mute"));
        public static final PacketCodec<RegistryByteBuf, ToggleMutePayload> CODEC =
                PacketCodec.unit(new ToggleMutePayload());
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client → Server: client-captured mic audio (WAV bytes) for STT (Pojav fallback). */
    public record ClientAudioPayload(byte[] audio) implements CustomPayload {
        public static final CustomPayload.Id<ClientAudioPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "client_audio"));
        public static final PacketCodec<RegistryByteBuf, ClientAudioPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.BYTE_ARRAY,
                        ClientAudioPayload::audio,
                        ClientAudioPayload::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
        public byte[] audio() { return audio; }
    }

    // ===== Server -> Client =====

    public record CompanionReplyPayload(String text) implements CustomPayload {
        public static final CustomPayload.Id<CompanionReplyPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "companion_reply"));
        public static final PacketCodec<RegistryByteBuf, CompanionReplyPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.STRING, CompanionReplyPayload::text, CompanionReplyPayload::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
        public String text() { return text; }
    }

    public record OpenConfirmationScreenPayload() implements CustomPayload {
        public static final CustomPayload.Id<OpenConfirmationScreenPayload> ID =
                new CustomPayload.Id<>(Identifier.of(Quackingly.MOD_ID, "open_confirmation"));
        public static final PacketCodec<RegistryByteBuf, OpenConfirmationScreenPayload> CODEC =
                PacketCodec.unit(new OpenConfirmationScreenPayload());
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    // ===== Send helpers =====

    public static void sendToggleSummon() {
        try { ClientPlayNetworking.send(new ToggleSummonPayload()); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send toggle_summon packet", t); }
    }

    public static void sendChatToCompanion(String text) {
        try { ClientPlayNetworking.send(new ChatToCompanionPayload(text)); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send chat_to_companion packet", t); }
    }

    public static void sendSummonWithMode(String mode) {
        try { ClientPlayNetworking.send(new SummonWithModePayload(mode)); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send summon_with_mode packet", t); }
    }

    public static void sendVoiceInputStart() {
        try { ClientPlayNetworking.send(new VoiceInputStartPayload()); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send voice_input_start packet", t); }
    }

    public static void sendVoiceInputStop() {
        try { ClientPlayNetworking.send(new VoiceInputStopPayload()); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send voice_input_stop packet", t); }
    }

    public static void sendToggleMute() {
        try { ClientPlayNetworking.send(new ToggleMutePayload()); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send toggle_mute packet", t); }
    }

    public static void sendClientAudio(byte[] audio) {
        try { ClientPlayNetworking.send(new ClientAudioPayload(audio)); }
        catch (Throwable t) { Quackingly.LOGGER.warn("Failed to send client_audio packet", t); }
    }
}
