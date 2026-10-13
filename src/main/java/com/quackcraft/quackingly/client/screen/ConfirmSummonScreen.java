package com.quackcraft.quackingly.client.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Confirmation popup shown when the user types /quackingly.
 *
 * Asks whether the player wants to add Bob to this world.
 *   Yes → opens the friendly-personality confirmation screen
 *   No  → closes, nothing happens
 *
 * The popup appears in the centre of the screen with a semi-transparent background.
 */
public class ConfirmSummonScreen extends Screen {

    public ConfirmSummonScreen() {
        super(Text.translatable("screen.quackingly.confirm.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        // Yes button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.confirm.yes")
                                .copy().formatted(Formatting.GREEN, Formatting.BOLD),
                        b -> client.setScreen(new InGameModeSelectScreen()))
                .dimensions(cx - 110, cy + 20, 100, 20)
                .build());

        // No button
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.confirm.no")
                                .copy().formatted(Formatting.RED),
                        b -> client.setScreen(null))
                .dimensions(cx + 10, cy + 20, 100, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Dark overlay
        ctx.fill(0, 0, width, height, 0x80000000);

        // Dialog box background
        int boxW = 320;
        int boxH = 80;
        int boxX = (width - boxW) / 2;
        int boxY = (height - boxH) / 2 - 20;
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xC01A1A2E);
        // Border
        ctx.drawBorder(boxX, boxY, boxW, boxH, 0xFF5555FF);

        // Title text
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.quackingly.confirm.question").formatted(Formatting.WHITE),
                width / 2, boxY + 15, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.quackingly.confirm.subquestion").formatted(Formatting.GRAY),
                width / 2, boxY + 30, 0xFFAAAAAA);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
