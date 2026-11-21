package com.mclauncher.pro.mixin;

import com.mclauncher.client.ClientMod;
import com.mclauncher.pro.ProMod;
import com.mclauncher.pro.path.Miner;
import com.mclauncher.pro.path.Navigator;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pro key handling: C = zoom hold, grave = sprint toggle, X = sneak toggle. */
@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void mcl$onKey(long window, int action, KeyInput input,
                           CallbackInfo ci) {
        if (!ProMod.isEnabled()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        int key = input.key();
        // Right Shift stops the bot even with a screen open - a pause
        // shouldn't require closing the screen first to end it
        if (action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            if (Navigator.active()) {
                Navigator.cancel("§7[path] stopped");
            } else if (Miner.active()) {
                Miner.cancel("§7[mine] stopped");
            }
        }
        if (mc.currentScreen != null) {
            ClientMod.state.zooming = false;
            return;
        }
        if (key == GLFW.GLFW_KEY_C) {
            if (action == GLFW.GLFW_PRESS) {
                ClientMod.state.zooming = true;
            } else if (action == GLFW.GLFW_RELEASE) {
                ClientMod.state.zooming = false;
            }
        }
        if (action == GLFW.GLFW_PRESS && mc.player != null) {
            if (key == GLFW.GLFW_KEY_GRAVE_ACCENT
                    && ClientMod.settings.on("sprint")) {
                ClientMod.state.sprintToggled =
                        !ClientMod.state.sprintToggled;
            }
            if (key == GLFW.GLFW_KEY_X) {
                ClientMod.state.sneakToggled =
                        !ClientMod.state.sneakToggled;
            }
        }
    }
}
