package com.quackcraft.quackingly.mixin;

import com.quackcraft.quackingly.client.screen.WorldPickerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "Play with Quackingly" button into the vanilla TitleScreen,
 * positioned immediately to the right of the "Singleplayer" button.
 *
 * When clicked, it opens the WorldPickerScreen (which lets the user pick a
 * world, then choose Normal/Unhinged mode, then launch).
 *
 * The button is 100px wide (vs Singleplayer's 200px) and sits at the same Y,
 * so it doesn't overlap any vanilla buttons.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quackingly$addPlayButton(CallbackInfo ci) {
        // In 1.21.1, the Singleplayer button is at:
        //   x = this.width / 2 - 100, y = this.height / 4 + 48
        //   width = 200, height = 20
        // We add our button to the RIGHT of it, at the same Y.
        int spRightEdge = this.width / 2 + 100;
        int buttonX = spRightEdge + 4;       // 4px gap
        int buttonY = this.height / 4 + 48;  // same Y as Singleplayer
        int buttonW = 100;

        // Only add if there's room on screen (button fits within width)
        if (buttonX + buttonW <= this.width) {
            this.addDrawableChild(ButtonWidget.builder(
                            Text.translatable("screen.quackingly.title_button"),
                            b -> MinecraftClient.getInstance().setScreen(
                                    new WorldPickerScreen((TitleScreen) (Object) this)))
                    .dimensions(buttonX, buttonY, buttonW, 20)
                    .build());
        }
    }
}
