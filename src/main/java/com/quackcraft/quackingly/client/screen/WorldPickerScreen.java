package com.quackcraft.quackingly.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * World Picker screen. Lists every saved world in .minecraft/saves as a button.
 * "Create New World" button at the bottom calls the vanilla create-world flow.
 *
 * On selecting a world -> ModeSelectScreen(worldDir).
 * On confirm -> we save Bob's friendly mode into config and trigger
 * vanilla world load by switching screens to net.minecraft.client.gui.screen.world.CreateWorldScreen
 * or by calling LevelStorageSession via the regular selectWorld path.
 *
 * This screen is opened from the title-screen via the Q keybind (see QuackinglyClient).
 */
public class WorldPickerScreen extends Screen {
    private static final int ENTRIES_PER_PAGE = 8;
    private final Screen parent;
    private int scroll = 0;

    public WorldPickerScreen(Screen parent) {
        super(Text.translatable("screen.quackingly.world_picker"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<File> worlds = listWorlds();

        int y = 40;
        int max = Math.min(worlds.size(), ENTRIES_PER_PAGE);
        for (int i = 0; i < max; i++) {
            File w = worlds.get(i + scroll);
            final File worldFile = w;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(worldLabel(w)),
                            b -> client.setScreen(new ModeSelectScreen(this, worldFile)))
                    .dimensions(width / 2 - 200, y, 400, 20)
                    .build());
            y += 24;
        }

        // Create New World — CreateWorldScreen.create() opens the screen itself
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.button.create_new"),
                        b -> net.minecraft.client.gui.screen.world.CreateWorldScreen.create(client, this))
                .dimensions(width / 2 - 200, y + 10, 400, 20)
                .build());

        // Back
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("screen.quackingly.button.back"),
                        b -> client.setScreen(parent))
                .dimensions(width / 2 - 100, height - 30, 200, 20)
                .build());
    }

    private String worldLabel(File f) {
        String name = f.getName();
        String display = name;
        File levelName = new File(f, "levelname.txt");
        if (levelName.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(levelName.toPath());
                if (!lines.isEmpty()) display = lines.get(0);
            } catch (Exception ignored) {}
        }
        return display + "  (" + name + ")";
    }

    private List<File> listWorlds() {
        File saves = new File(MinecraftClient.getInstance().runDirectory, "saves");
        File[] kids = saves.listFiles(File::isDirectory);
        if (kids == null) return new ArrayList<>();
        List<File> out = new ArrayList<>(Arrays.asList(kids));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, this.title.copy().formatted(Formatting.AQUA),
                width / 2, 15, 0xFFFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }
}
