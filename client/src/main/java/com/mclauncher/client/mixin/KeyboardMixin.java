package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import com.mclauncher.client.ConfigScreen;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right-Shift opens the HUD config screen. Feature keys live in pro. */
@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void mcl$onKey(long window, int action, KeyInput input,
                           CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (action == GLFW.GLFW_PRESS && mc.currentScreen == null
                && mc.player != null
                && input.key() == GLFW.GLFW_KEY_RIGHT_SHIFT
                && (ClientMod.state == null || !ClientMod.state.navActive)) {
            mc.setScreen(new ConfigScreen());
        }
    }
}
