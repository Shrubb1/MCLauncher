package com.mclauncher.pro;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Runtime gate. The launcher writes %APPDATA%/MCLauncher/activation.json
 * ({enabled, names:[bound MC usernames]}) on every launch and when the
 * global toggle flips, so a jar copied into any mods folder only
 * activates for a bound Minecraft name - and only while enabled is on.
 * Also requires the free mod so its settings/state exist.
 *
 * Evaluated lazily: the session isn't guaranteed during client init,
 * so the first call with a live session caches the verdict.
 */
final class Activation {

    private static volatile Boolean cached;
    private static volatile Boolean adminCached;

    private Activation() {
    }

    /** Launcher accounts flagged admin get the route recorder tab. Only
     * gates the in-game tools - publishing still lives server-side. */
    static boolean isAdmin() {
        Boolean c = adminCached;
        if (c != null) {
            return c;
        }
        boolean ok = false;
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    JsonObject root = JsonParser.parseReader(r)
                            .getAsJsonObject();
                    ok = root.has("admin") && root.get("admin")
                            .getAsBoolean();
                }
            }
        } catch (Throwable ignored) {
        }
        adminCached = ok;
        return ok;
    }

    static boolean isEnabled() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        if (!FabricLoader.getInstance().isModLoaded("mclauncher-client")) {
            cached = Boolean.FALSE;
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSession() == null) {
            return false; // too early in startup - retry on next call
        }
        boolean ok = false;
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    JsonObject root = JsonParser.parseReader(r)
                            .getAsJsonObject();
                    if (root.has("enabled")
                            && root.get("enabled").getAsBoolean()
                            && root.has("names")) {
                        String who = mc.getSession().getUsername()
                                .toLowerCase(Locale.ROOT);
                        for (var e : root.getAsJsonArray("names")) {
                            if (who.equals(e.getAsString()
                                    .toLowerCase(Locale.ROOT))) {
                                ok = true;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cached = ok;
        return ok;
    }

    private static Path file() {
        String appdata = System.getenv("APPDATA");
        Path base = appdata != null
                ? Paths.get(appdata)
                : Paths.get(System.getProperty("user.home"));
        return base.resolve("MCLauncher").resolve("activation.json");
    }
}
