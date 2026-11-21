package com.mclauncher.pro;

import net.fabricmc.api.ClientModInitializer;

/**
 * MCLauncher client mod - PAID tier.
 * Zoom, fullbright, toggle sprint/sneak, borderless window.
 * Shares settings/state with the free mod (mclauncher-client) and only
 * activates when activation.json binds this session's Minecraft name.
 */
public class ProMod implements ClientModInitializer {

    /** Runtime gate: false unless the free mod is loaded AND
     * activation.json marks this session's Minecraft name as bound. */
    public static boolean isEnabled() {
        return Activation.isEnabled();
    }

    /** activation.json admin flag - unlocks the route recorder tab. */
    public static boolean isAdmin() {
        return Activation.isAdmin();
    }

    @Override
    public void onInitializeClient() {
        // activation is evaluated lazily once the session exists
    }
}
