package com.mclauncher.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static boolean noticeChecked;

    /** Called from InGameHudMixin after vanilla HUD draws. */
    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen != null) {
            return;
        }
        if (!noticeChecked) {
            noticeChecked = true;
            checkUpdateNotice(mc);
        }
        UpdateChecker.kick(mc);
        UpdateChecker.maybeAnnounce(mc);
        HudRenderer.render(ctx, mc, settings, state);
    }

    /** First world frame: if activation.json carries a launcher version
     * we haven't announced yet, say so in chat once and remember it. */
    private static void checkUpdateNotice(MinecraftClient mc) {
        try {
            Path f = ClientSettings.file().getParent()
                    .resolve("activation.json");
            if (mc.player == null || !Files.isRegularFile(f)) {
                return;
            }
            JsonObject root;
            try (Reader r = Files.newBufferedReader(f)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            String ver = root.has("version")
                    ? root.get("version").getAsString() : "";
            if (ver.isEmpty() || ver.equals(settings.lastSeenVersion)) {
                return;
            }
            String note = root.has("note")
                    ? root.get("note").getAsString() : "";
            mc.player.sendMessage(Text.literal(
                    "§6MCLauncher §fupdated to §ev" + ver
                            + (note.isEmpty() ? "" : " §7- " + note)),
                    false);
            settings.lastSeenVersion = ver;
            saveQuiet();
        } catch (Throwable ignored) {
        }
    }
}
