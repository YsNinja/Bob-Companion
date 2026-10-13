package com.quackcraft.quackingly.client.screen;

import com.quackcraft.quackingly.client.network.ClientCompanionPackets;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * In-game mode select screen. Shown after the user clicks "Yes" on the
 * ConfirmSummonScreen (triggered by /quackingly).
 *
 * Bob has one personality: friendly and non-horror.
 *
 * After selecting, the screen closes and the server spawns Quackingly with
 * the chosen mode.
 */
public class InGameModeSelectScreen extends Screen {

    public InGameModeSelectScreen() {
        super(Text.translatable("screen.quackingly.mode_select"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        // Friendly mode button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.mode.normal")
                                .copy().formatted(Formatting.GREEN, Formatting.BOLD),
                        b -> {
                            ClientCompanionPackets.sendSummonWithMode("normal");
                            client.setScreen(null);
                        })
                .dimensions(cx - 60, cy - 12, 120, 40)
                .build());

        // Cancel button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.button.back"),
                        b -> client.setScreen(null))
                .dimensions(cx - 60, cy + 45, 120, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Dark overlay
        ctx.fill(0, 0, width, height, 0x80000000);

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer,
                this.title.copy().formatted(Formatting.AQUA),
                width / 2, height / 2 - 50, 0xFFFFFFFF);

        // Description for the hovered mode would be nice, but for simplicity:
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Bob is here to help with the adventure").formatted(Formatting.GRAY),
                width / 2, height / 2 - 35, 0xFFAAAAAA);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
