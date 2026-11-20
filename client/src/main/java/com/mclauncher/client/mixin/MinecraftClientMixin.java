package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies borderless-windowed mode once the game window is fully up. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void mcl$borderless(CallbackInfo ci) {
        if (!ClientMod.settings.on("borderless")
                || ClientMod.settings.borderlessApplied) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getWindow() == null) {
            return;
        }
        try {
            long handle = mc.getWindow().getHandle();
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED,
                    GLFW.GLFW_FALSE);
            long monitor = GLFW.glfwGetPrimaryMonitor();
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode != null) {
                GLFW.glfwSetWindowPos(handle, 0, 0);
                GLFW.glfwSetWindowSize(handle, mode.width(), mode.height());
            }
        } catch (Throwable ignored) {
        }
        ClientMod.settings.borderlessApplied = true;
    }
}
