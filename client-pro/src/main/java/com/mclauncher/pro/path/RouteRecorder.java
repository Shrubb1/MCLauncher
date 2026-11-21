package com.mclauncher.pro.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.client.ClientMod;
import com.mclauncher.client.ClientSettings;
import com.mclauncher.pro.ProMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin route builder. While recording, press B in-game to append one
 * spot: your feet coords + the block under your crosshair as the ore +
 * the dwell seconds set on the Admin tab. Everything saves live to
 * %APPDATA%/MCLauncher/route.json - the same file RouteRunner prefers
 * over the relay copy, so the route button runs what you just recorded.
 * Deploy that file as server/route.json to publish it to everyone.
 *
 * Admin-gated two ways: the tab only renders when activation.json says
 * admin, and every action re-checks Activation.isAdmin().
 */
public final class RouteRecorder {

    private static final List<JsonObject> spots = new ArrayList<>();
    private static boolean on, loaded;
    private static String name = "recorded route", ore = "";

    private RouteRecorder() {
    }

    public static boolean active() {
        return on && admin();
    }

    public static int count() {
        return spots.size();
    }

    private static boolean admin() {
        return ProMod.isAdmin();
    }

    /** Every player tick: lazily load the local file once, then keep
     * the shared-state hooks in sync for the Admin tab. */
    public static void tick(MinecraftClient mc) {
        if (!loaded) {
            loaded = true;
            load();
        }
        if (ClientMod.state == null) {
            return;
        }
        boolean adm = admin();
        ClientMod.state.admin = adm;
        if (adm) {
            if (ClientMod.state.recordToggle == null) {
                ClientMod.state.recordToggle = RouteRecorder::toggle;
                ClientMod.state.recordClear = RouteRecorder::clear;
            }
            ClientMod.state.recordOn = on;
            ClientMod.state.recordSpots = spots.size();
        } else {
            on = false;
            ClientMod.state.recordOn = false;
        }
    }

    public static void toggle() {
        if (!admin()) {
            return;
        }
        on = !on;
        if (ClientMod.state != null) {
            ClientMod.state.recordOn = on;
        }
        say(on ? "§a[route] recording - press B on a spot to add it"
                : "§7[route] recording off");
    }

    /** B in-game: capture feet coords + looked-at block + dwell. */
    public static void capture() {
        if (!active()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return;
        }
        String block = ore; // keep last if not aiming at a block
        if (mc.crosshairTarget instanceof BlockHitResult h
                && h.getType() == HitResult.Type.BLOCK) {
            block = net.minecraft.registry.Registries.BLOCK.getId(
                    mc.world.getBlockState(h.getBlockPos()).getBlock())
                    .getPath();
        }
        BlockPos pos = p.getBlockPos();
        int secs = ClientMod.state != null
                ? Math.max(1, Math.min(600, ClientMod.state.recordDwell))
                : 10;
        JsonObject s = new JsonObject();
        s.addProperty("x", pos.getX());
        s.addProperty("y", pos.getY());
        s.addProperty("z", pos.getZ());
        s.addProperty("seconds", secs);
        if (block != null && !block.isEmpty()) {
            s.addProperty("ore", block);
            ore = block;
        }
        spots.add(s);
        save();
        if (ClientMod.state != null) {
            ClientMod.state.recordSpots = spots.size();
        }
        say("§7[route] spot #" + spots.size() + " §f" + pos.getX() + " "
                + pos.getY() + " " + pos.getZ() + " §7("
                + (block == null || block.isEmpty() ? "?" : block)
                + ", " + secs + "s)");
    }

    public static void clear() {
        if (!admin()) {
            return;
        }
        int n = spots.size();
        spots.clear();
        save();
        if (ClientMod.state != null) {
            ClientMod.state.recordSpots = 0;
        }
        say("§7[route] cleared " + n + " spot"
                + (n == 1 ? "" : "s"));
    }

    // -- persistence ---------------------------------------------------

    private static Path file() {
        return ClientSettings.file().getParent().resolve("route.json");
    }

    private static void load() {
        spots.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(f)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("name")) {
                name = o.get("name").getAsString();
            }
            if (o.has("ore")) {
                ore = o.get("ore").getAsString();
            }
            if (o.has("spots")) {
                for (var e : o.getAsJsonArray("spots")) {
                    spots.add(e.getAsJsonObject());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("ore", ore);
        JsonArray arr = new JsonArray();
        for (JsonObject s : spots) {
            arr.add(s);
        }
        o.add("spots", arr);
        try (Writer w = Files.newBufferedWriter(file())) {
            w.write(o.toString());
        } catch (Throwable t) {
            say("§c[route] couldn't save route.json");
        }
    }

    private static void say(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) {
            p.sendMessage(Text.literal(s), false);
        }
    }
}
