package com.mclauncher.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * MCLauncher built-in client mod - FREE tier.
 * Read-only HUD widgets (FPS, ping, coords, armor, potions, keystrokes/CPS)
 * and the config screen. The paid tier (zoom, fullbright, toggle
 * sprint/sneak, borderless) lives in mclauncher-client-pro.
 */
public class ClientMod implements ClientModInitializer {

    public static ClientSettings settings;
    public static HudState state;

    /** Free tier is ungated - the pro jar carries the activation check. */
    public static boolean isEnabled() {
        return true;
    }

    @Override
    public void onInitializeClient() {
        state = new HudState();
        settings = ClientSettings.load();
        Runtime.getRuntime().addShutdownHook(new Thread(ClientMod::saveQuiet));
    }

    public static void saveQuiet() {
        try {
            settings.save();
        } catch (Throwable ignored) {
        }
    }

    /** Called from InGameHudMixin after vanilla HUD draws. */
    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen != null) {
            return;
        }
        HudRenderer.render(ctx, mc, settings, state);
    }
}
