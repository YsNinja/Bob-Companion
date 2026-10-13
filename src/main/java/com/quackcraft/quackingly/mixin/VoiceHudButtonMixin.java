package com.quackcraft.quackingly.mixin;

import com.quackcraft.quackingly.client.VoiceHud;
import com.quackcraft.quackingly.client.screen.BobConfigScreen;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.voice.ClientVoiceController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Unique;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes clicks on the HUD mic control to the same push-to-talk path as V. */
@Mixin(Mouse.class)
public abstract class VoiceHudButtonMixin {
    @Unique private static boolean bobVoiceButtonHeld;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void quackingly$voiceButton(long window, int button, int action, int mods, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.currentScreen != null
                || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        if (action == GLFW.GLFW_PRESS) {
            double x = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double y = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
            if (x < VoiceHud.X || x >= VoiceHud.X + VoiceHud.WIDTH
                    || y < VoiceHud.Y || y >= VoiceHud.Y + VoiceHud.HEIGHT) return;
            if (!QuackinglyConfig.get().voiceInputEnabled) {
                client.setScreen(new BobConfigScreen(null));
            } else {
                bobVoiceButtonHeld = true;
                ClientVoiceController.pressPtt();
            }
            ci.cancel();
        } else if (action == GLFW.GLFW_RELEASE && bobVoiceButtonHeld) {
            bobVoiceButtonHeld = false;
            ClientVoiceController.releasePtt();
            ci.cancel();
        }
    }
}
