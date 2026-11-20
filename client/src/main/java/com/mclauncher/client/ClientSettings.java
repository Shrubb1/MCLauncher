package com.mclauncher.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted at %APPDATA%/MCLauncher/client_settings.json so the HUD layout and
 * toggles follow the player across every launcher instance.
 */
public class ClientSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** widget id -> [x, y] on-screen position */
    public final Map<String, int[]> positions = new LinkedHashMap<>();
    /** module id -> enabled */
    public final Map<String, Boolean> modules = new LinkedHashMap<>();

    public double zoomFactor = 4.0;
    public boolean borderlessApplied = false;

    public boolean on(String id) {
        // borderless changes the actual window - opt-in, off by default
        return modules.getOrDefault(id, !"borderless".equals(id));
    }

    public void toggle(String id) {
        modules.put(id, !on(id));
    }

    public int[] pos(String id, int defX, int defY) {
        return positions.computeIfAbsent(id, k -> new int[]{defX, defY});
    }

    public static Path file() {
        String appdata = System.getenv("APPDATA");
        Path base = appdata != null
                ? Paths.get(appdata)
                : Paths.get(System.getProperty("user.home"));
        return base.resolve("MCLauncher").resolve("client_settings.json");
    }

    public static ClientSettings load() {
        ClientSettings s = new ClientSettings();
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return s;
        }
        try (Reader r = Files.newBufferedReader(f)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("modules")) {
                for (var e : root.getAsJsonObject("modules").entrySet()) {
                    s.modules.put(e.getKey(), e.getValue().getAsBoolean());
                }
            }
            if (root.has("positions")) {
                for (var e : root.getAsJsonObject("positions").entrySet()) {
                    var arr = e.getValue().getAsJsonArray();
                    s.positions.put(e.getKey(),
                            new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt()});
                }
            }
            if (root.has("zoomFactor")) {
                s.zoomFactor = root.get("zoomFactor").getAsDouble();
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    public void save() throws IOException {
        JsonObject root = new JsonObject();
        JsonObject mods = new JsonObject();
        modules.forEach(mods::addProperty);
        root.add("modules", mods);
        JsonObject pos = new JsonObject();
        positions.forEach((k, v) -> {
            var arr = new com.google.gson.JsonArray();
            arr.add(v[0]);
            arr.add(v[1]);
            pos.add(k, arr);
        });
        root.add("positions", pos);
        root.addProperty("zoomFactor", zoomFactor);
        Path f = file();
        Files.createDirectories(f.getParent());
        try (Writer w = Files.newBufferedWriter(f)) {
            GSON.toJson(root, w);
        }
    }
}
