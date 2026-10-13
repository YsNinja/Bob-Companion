package com.quackcraft.quackingly.client.screen;

import com.quackcraft.quackingly.Quackingly;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;

/**
 * After picking a world, Bob has one friendly personality.
 *
 * On "Play", we:
 *   1. Save the chosen mode into QuackinglyConfig.
 *   2. Directly launch the selected world via vanilla's LevelStorage.Session +
 *      IntegratedServerLoader.start(). This is the same code path that
 *      vanilla's WorldListWidget.WorldEntry#play uses.
 *
 * If the world fails to load for any reason, we fall back to the vanilla
 * SelectWorldScreen so the user can try manually.
 */
public class ModeSelectScreen extends Screen {
    private final Screen parent;
    private final File worldDir;
    private String selectedMode;

    public ModeSelectScreen(Screen parent, File worldDir) {
        super(Text.translatable("screen.quackingly.mode_select"));
        this.parent = parent;
        this.worldDir = worldDir;
        this.selectedMode = com.quackcraft.quackingly.config.QuackinglyConfig.get().defaultMode;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        // Friendly personality
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.mode.normal")
                                .copy().formatted(Formatting.GREEN),
                        b -> { selectedMode = "normal"; clearAndInit(); })
                .dimensions(cx - 90, cy - 30, 180, 40)
                .build());

        // Play — directly launches the world
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.button.play")
                                .copy().formatted(Formatting.AQUA, Formatting.BOLD),
                        b -> launchWorld())
                .dimensions(cx - 100, cy + 80, 200, 20)
                .build());

        // Back
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.button.back"),
                        b -> client.setScreen(parent))
                .dimensions(cx - 100, height - 30, 200, 20)
                .build());
    }

    private void launchWorld() {
        // Save the chosen mode
        com.quackcraft.quackingly.config.QuackinglyConfig.get().defaultMode = selectedMode;
        com.quackcraft.quackingly.config.QuackinglyConfig.save();

        // Set flag so the client auto-summons Quackingly after world join
        com.quackcraft.quackingly.QuackinglyClient.setAutoSummonOnJoin(true, selectedMode);

        if (client == null || worldDir == null) return;

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.createIntegratedServerLoader().start(worldDir.getName(), () -> {
                mc.setScreen(parent);
            });
        } catch (Throwable t) {
            com.quackcraft.quackingly.Quackingly.LOGGER.error("[Quackingly] Failed to launch world '{}' directly, falling back to vanilla world list",
                    worldDir.getName(), t);
            client.setScreen(new SelectWorldScreen(parent));
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer,
                this.title.copy().formatted(Formatting.AQUA),
                width / 2, 20, 0xFFFFFFFF);

        String desc = "Friendly, supportive, and ready to join the adventure.";
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(desc).formatted(Formatting.GRAY),
                width / 2, height / 2 + 5, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("World: " + worldDir.getName()).formatted(Formatting.DARK_GRAY),
                width / 2, height / 2 + 50, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Press Play to launch — then press K in-game to summon Bob.")
                        .formatted(Formatting.DARK_GRAY),
                width / 2, height / 2 + 65, 0xFFFFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }
}
