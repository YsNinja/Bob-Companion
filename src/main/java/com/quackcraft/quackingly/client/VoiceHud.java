package com.quackcraft.quackingly.client;

import com.quackcraft.quackingly.QuackinglyClient;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.voice.ClientVoiceController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Small clickable push-to-talk control in the in-game HUD. */
public final class VoiceHud {
    public static final int X = 8;
    public static final int Y = 8;
    public static final int WIDTH = 148;
    public static final int HEIGHT = 22;

    private VoiceHud() {}

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.currentScreen != null) return;
        boolean enabled = QuackinglyConfig.get().voiceInputEnabled;
        boolean recording = ClientVoiceController.isPttActive();
        int color = recording ? 0xD0A51E24 : enabled ? 0xB0202920 : 0xB02A2A2A;
        context.fill(X, Y, X + WIDTH, Y + HEIGHT, color);
        String key = QuackinglyClient.getTalkKey() == null
                ? "V" : QuackinglyClient.getTalkKey().getBoundKeyLocalizedText().getString();
        String label = recording ? "Recording — release to send"
                : enabled ? "Hold or click to talk  [" + key + "]" : "Mic off — click to enable in settings";
        context.drawTextWithShadow(client.textRenderer, Text.literal(label), X + 5, Y + 7,
                recording ? 0xFFFF7777 : 0xFFFFFFFF);
    }
}
