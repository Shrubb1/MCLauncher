package com.mclauncher.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * MCLauncher built-in client mod.
 * Pure client-side QoL: HUD widgets, zoom, fullbright, toggle sprint/sneak,
 * CPS counter, borderless window. Sends nothing to the server that a normal
 * client wouldn't send.
 */
public class ClientMod implements ClientModInitializer {

    public static ClientSettings settings;
    public static HudState state;

    /** Runtime gate: false unless activation.json marks this session's
     * Minecraft name as bound and enabled. A copied jar does nothing. */
    public static boolean isEnabled() {
        return Activation.isEnabled();
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
        if (!isEnabled()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen != null) {
            return;
        }
        HudRenderer.render(ctx, mc, settings, state);
    }
}
