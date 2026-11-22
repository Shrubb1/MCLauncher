package com.mclauncher.pro.mixin;

import com.mclauncher.client.ClientMod;
import com.mclauncher.pro.ProMod;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies (and reverts) borderless-windowed mode. The windowed
 * geometry is captured on apply so toggling off restores it. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    private static boolean haveOrig;
    private static int origX, origY, origW, origH;

    @Inject(method = "tick", at = @At("TAIL"))
    private void mcl$borderless(CallbackInfo ci) {
        if (!ProMod.isEnabled() || ClientMod.settings == null) {
            return;
        }
        boolean want = ClientMod.settings.on("borderless");
        if (want == ClientMod.settings.borderlessApplied) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getWindow() == null) {
            return;
        }
        long handle = mc.getWindow().getHandle();
        try {
            if (want) {
                int[] x = new int[1], y = new int[1];
                int[] w = new int[1], h = new int[1];
                GLFW.glfwGetWindowPos(handle, x, y);
                GLFW.glfwGetWindowSize(handle, w, h);
                origX = x[0];
                origY = y[0];
                origW = w[0];
                origH = h[0];
                haveOrig = true;
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED,
                        GLFW.GLFW_FALSE);
                long monitor = GLFW.glfwGetPrimaryMonitor();
                GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode != null) {
                    GLFW.glfwSetWindowPos(handle, 0, 0);
                    GLFW.glfwSetWindowSize(handle, mode.width(),
                            mode.height());
                }
            } else {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED,
                        GLFW.GLFW_TRUE);
                if (haveOrig) {
                    GLFW.glfwSetWindowPos(handle, origX, origY);
                    GLFW.glfwSetWindowSize(handle, origW, origH);
                }
            }
        } catch (Throwable ignored) {
        }
        ClientMod.settings.borderlessApplied = want;
    }
}
