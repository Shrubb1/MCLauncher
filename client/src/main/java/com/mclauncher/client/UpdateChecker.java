package com.mclauncher.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Live update broadcast: polls the relay's /version on a daemon thread
 * every ~10 minutes and announces a newer launcher release in chat once
 * per session, so players mid-game hear about updates they were not
 * launched with. Reads the running launcher's version from
 * activation.json - nothing is written back.
 */
public final class UpdateChecker {

    private static final String URL =
            "https://mclauncher-relay.fly.dev/version";
    private static final long PERIOD_MS = 10 * 60 * 1000;

    private static volatile String pending;
    private static volatile String announced;
    private static long nextCheck;
    private static Thread thread;

    private UpdateChecker() {
    }

    /** Called every HUD frame; self-throttled to PERIOD_MS. */
    public static void kick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now < nextCheck || (thread != null && thread.isAlive())) {
            return;
        }
        nextCheck = now + PERIOD_MS;
        thread = new Thread(UpdateChecker::fetch, "mcl-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Print the pending announcement on the render thread. */
    public static void maybeAnnounce(MinecraftClient mc) {
        String v = pending;
        if (v == null || mc.player == null) {
            return;
        }
        pending = null;
        announced = v;
        mc.player.sendMessage(Text.literal(
                "§6MCLauncher §fv" + v
                        + " §favailable §7- restart the launcher to update"),
                false);
    }

    private static void fetch() {
        try {
            String local = localVersion();
            if (local.isEmpty()) {
                return;
            }
            HttpURLConnection c =
                    (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (Reader r = new InputStreamReader(c.getInputStream())) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                String remote = o.has("version")
                        ? o.get("version").getAsString() : "";
                if (!remote.isEmpty() && !remote.equals(announced)
                        && newer(remote, local)) {
                    pending = remote;
                }
            } finally {
                c.disconnect();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String localVersion() {
        try {
            Path f = ClientSettings.file().getParent()
                    .resolve("activation.json");
            if (!Files.isRegularFile(f)) {
                return "";
            }
            try (Reader r = Files.newBufferedReader(f)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                return o.has("version")
                        ? o.get("version").getAsString() : "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** semver-ish compare: is remote newer than local? */
    private static boolean newer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = {0, 0, 0};
        String[] p = v.split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
