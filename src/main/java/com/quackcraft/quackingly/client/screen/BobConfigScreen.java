package com.quackcraft.quackingly.client.screen;

import com.quackcraft.quackingly.config.QuackinglyConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Lightweight settings screen; no Cloth Config or other UI library required. */
public final class BobConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;

    public BobConfigScreen(Screen parent) {
        super(Text.literal("Bob Companion Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(320, width - 40);
        int x = (width - fieldWidth) / 2;
        int y = Math.max(72, height / 2 - 55);

        apiKeyField = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20, Text.literal("Provider API key"));
        apiKeyField.setMaxLength(512);
        apiKeyField.setText(QuackinglyConfig.get().apiKey == null ? "" : QuackinglyConfig.get().apiKey);
        apiKeyField.setPlaceholder(Text.literal("Paste your provider API key"));
        apiKeyField.setRenderTextProvider((value, firstCharacterIndex) ->
                Text.literal("•".repeat(value.length())).asOrderedText());
        addDrawableChild(apiKeyField);

        modelField = new TextFieldWidget(textRenderer, x, y + 55, fieldWidth, 20, Text.literal("Model"));
        modelField.setMaxLength(160);
        modelField.setText(QuackinglyConfig.get().model == null ? "openai/gpt-oss-20b" : QuackinglyConfig.get().model);
        modelField.setPlaceholder(Text.literal("openai/gpt-oss-20b"));
        addDrawableChild(modelField);

        addDrawableChild(ButtonWidget.builder(defenseLabel(), button -> {
                    QuackinglyConfig.get().autoDefend = !QuackinglyConfig.get().autoDefend;
                    button.setMessage(defenseLabel());
                    QuackinglyConfig.save();
                })
                .dimensions(x, y + 83, fieldWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(voiceLabel(), button -> {
                    QuackinglyConfig.get().voiceInputEnabled = !QuackinglyConfig.get().voiceInputEnabled;
                    button.setMessage(voiceLabel());
                    QuackinglyConfig.save();
                })
                .dimensions(x, y + 111, fieldWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(sleepLabel(), button -> {
                    QuackinglyConfig.get().autoSleep = !QuackinglyConfig.get().autoSleep;
                    button.setMessage(sleepLabel());
                    QuackinglyConfig.save();
                })
                .dimensions(x, y + 139, fieldWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose())
                .dimensions(x, y + 167, fieldWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> client.setScreen(parent))
                .dimensions(x, y + 195, fieldWidth, 20).build());
        setInitialFocus(apiKeyField);
    }

    private static Text defenseLabel() {
        return Text.literal("Help fight nearby monsters: " + (QuackinglyConfig.get().autoDefend ? "ON" : "OFF"));
    }

    private static Text voiceLabel() {
        return Text.literal("Microphone voice input: " + (QuackinglyConfig.get().voiceInputEnabled ? "ON" : "OFF"));
    }

    private static Text sleepLabel() {
        return Text.literal("Bob sleeps in a nearby bed at night: " + (QuackinglyConfig.get().autoSleep ? "ON" : "OFF"));
    }

    private void saveAndClose() {
        QuackinglyConfig.ConfigData config = QuackinglyConfig.get();
        config.apiKey = apiKeyField.getText().trim();
        config.model = modelField.getText().trim().isEmpty() ? "openai/gpt-oss-20b" : modelField.getText().trim();
        QuackinglyConfig.save();
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 28, 0xFFFFFFFF);
        int x = width / 2 - Math.min(160, (width - 40) / 2);
        int y = Math.max(72, height / 2 - 55);
        context.drawTextWithShadow(textRenderer, Text.literal("API key (hidden while typing)"), x, y - 13, 0xFFCCCCCC);
        context.drawTextWithShadow(textRenderer, Text.literal("Model"), x, y + 42, 0xFFCCCCCC);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("API key stays local and is sent with your chat request.").formatted(Formatting.GRAY),
                width / 2, y + 225, 0xFFAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
