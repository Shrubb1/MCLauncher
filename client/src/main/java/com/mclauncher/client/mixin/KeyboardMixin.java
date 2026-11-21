package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import com.mclauncher.client.ConfigScreen;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raw key handling: C = zoom hold, Right-Shift = config screen. */
@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void mcl$onKey(long window, int key, int scancode, int action,
                           int modifiers, CallbackInfo ci) {
        if (!ClientMod.isEnabled()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (key == GLFW.GLFW_KEY_C) {
            if (action == GLFW.GLFW_PRESS) {
                ClientMod.state.zooming = true;
            } else if (action == GLFW.GLFW_RELEASE) {
                ClientMod.state.zooming = false;
            }
        }
        if (action == GLFW.GLFW_PRESS && mc.currentScreen == null
                && mc.player != null) {
            if (key == GLFW.GLFW_KEY_GRAVE_ACCENT
                    && ClientMod.settings.on("sprint")) {
                ClientMod.state.sprintToggled =
                        !ClientMod.state.sprintToggled;
            }
            if (key == GLFW.GLFW_KEY_X) {
                ClientMod.state.sneakToggled =
                        !ClientMod.state.sneakToggled;
            }
            if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                mc.setScreen(new ConfigScreen());
            }
        }
    }
}
