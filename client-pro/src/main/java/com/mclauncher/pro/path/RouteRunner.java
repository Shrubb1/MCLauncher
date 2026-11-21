package com.mclauncher.pro.path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.client.ClientMod;
import com.mclauncher.client.ClientSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * "#route" - runs the admin-published mining route as one repeating
 * loop: navigator to a spot, mine there for its dwell seconds, then
 * head to the next and wrap around. The preset comes from the relay's
 * GET /route so one edit reaches every client; a local route.json next
 * to the launcher data wins for testing.
 */
public final class RouteRunner {

    private static final String ROUTE_URL =
            "https://mclauncher-relay.fly.dev/route";

    /** One leg of the loop. Plain class, not a record - ProGuard's
     * preverifier can't resolve java.lang.Record. */
    private static final class Spot {
        final int x, y, z;
        final double seconds;
        final String ore;

        Spot(int x, int y, int z, double seconds, String ore) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.seconds = seconds;
            this.ore = ore;
        }
    }

    private static List<Spot> spots = List.of();
    private static String routeName = "", ore = "";
    private static volatile JsonObject pending;
    private static boolean running, loading, navigating;
    /** last tick's running state - dashboard task logging */
    private static boolean wasActive;
    private static int idx;
    private static long mineUntil;

    private RouteRunner() {
    }

    public static boolean active() {
        return running;
    }

    public static void toggle() {
        if (running) {
            cancel("§7[route] stopped");
        } else {
            start();
        }
    }

    public static void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || running) {
            return;
        }
        running = true;
        loading = true;
        spots = List.of();
        nav(true);
        routeFlag(true);
        say("§7[route] loading route...");
        // local file wins - lets the admin iterate without redeploying
        Path f = ClientSettings.file().getParent().resolve("route.json");
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                pending = JsonParser.parseReader(r).getAsJsonObject();
            } catch (Throwable t) {
                pending = error("route.json unreadable: "
                        + t.getMessage());
            }
            return;
        }
        Thread t = new Thread(() -> {
            try {
                HttpClient c = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(ROUTE_URL))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<InputStream> resp = c.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());
                JsonObject o = JsonParser.parseReader(
                        new java.io.InputStreamReader(resp.body()))
                        .getAsJsonObject();
                pending = resp.statusCode() == 200 ? o
                        : error("relay has no route yet");
            } catch (Throwable e) {
                pending = error("couldn't fetch route: "
                        + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public static void cancel(String why) {
        if (!running) {
            return;
        }
        running = false;
        loading = false;
        pending = null;
        spots = List.of();
        if (Navigator.active()) {
            Navigator.cancel("§7[path] stopped");
        }
        if (Miner.active()) {
            Miner.cancel("§7[mine] stopped");
        }
        nav(false);
        routeFlag(false);
        say(why);
    }

    /** Called every player tick while the pro tier is enabled. */
    public static void tick(MinecraftClient mc) {
        // dashboard: a route is itself a task, on top of the goto/mine
        // legs it drives
        boolean a = running;
        if (a != wasActive) {
            if (a) {
                ClientMod.stats.begin("route", "loading...");
            } else {
                ClientMod.stats.end(0);
            }
            wasActive = a;
        }
        if (!running) {
            return;
        }
        // free-mod config button hook - registered lazily once the
        // shared state object exists
        if (ClientMod.state != null
                && ClientMod.state.routeAction == null) {
            ClientMod.state.routeAction = RouteRunner::toggle;
        }
        JsonObject p = pending;
        if (p != null) {
            pending = null;
            applyRoute(mc, p);
            if (!running) {
                return; // applyRoute bailed on an error
            }
        }
        if (spots.isEmpty()) {
            return; // still loading
        }
        // hold the busy flag for the whole loop - Navigator/Miner clear
        // it each time they finish a phase
        nav(true);
        long now = System.currentTimeMillis();
        if (navigating) {
            if (!Navigator.active()) {
                // arrived (or nav gave up here - mine wherever it ended)
                navigating = false;
                Spot s = spots.get(idx);
                Miner.start(s.ore != null ? s.ore : ore);
                mineUntil = now + (long) (s.seconds * 1000);
                say("§7[route] spot " + (idx + 1) + "/" + spots.size()
                        + " - mining " + (int) s.seconds + "s");
                ClientMod.stats.activeDetail = routeName + " - spot "
                        + (idx + 1) + "/" + spots.size() + " mining";
            }
        } else if (!Miner.active() || now >= mineUntil) {
            if (Miner.active()) {
                Miner.cancel("§7[mine] spot done");
            }
            idx = (idx + 1) % spots.size();
            Spot s = spots.get(idx);
            Navigator.start(new BlockPos(s.x, s.y, s.z));
            navigating = true;
            say("§7[route] heading to spot " + (idx + 1) + "/"
                    + spots.size());
            ClientMod.stats.activeDetail = routeName + " - spot "
                    + (idx + 1) + "/" + spots.size() + " traveling";
        }
    }

    // -- route loading -------------------------------------------------

    private static void applyRoute(MinecraftClient mc, JsonObject o) {
        loading = false;
        if (o.has("error")) {
            cancel("§c[route] " + o.get("error").getAsString());
            return;
        }
        List<Spot> out = new ArrayList<>();
        ore = o.has("ore") ? o.get("ore").getAsString() : "";
        routeName = o.has("name") ? o.get("name").getAsString() : "route";
        if (o.has("spots")) {
            for (var e : o.getAsJsonArray("spots")) {
                JsonObject s = e.getAsJsonObject();
                out.add(new Spot(
                        s.get("x").getAsInt(),
                        s.has("y") ? s.get("y").getAsInt() : 64,
                        s.get("z").getAsInt(),
                        s.has("seconds") ? s.get("seconds").getAsDouble()
                                : 10.0,
                        s.has("ore") ? s.get("ore").getAsString()
                                : null));
            }
        }
        if (out.isEmpty() || ore.isEmpty()) {
            cancel("§c[route] bad route - needs ore + spots");
            return;
        }
        spots = out;
        idx = 0;
        navigating = true;
        Spot s = spots.get(0);
        Navigator.start(new BlockPos(s.x, s.y, s.z));
        say("§7[route] " + routeName + " - " + spots.size()
                + " spots, looping. Right Shift to stop");
        ClientMod.stats.activeDetail = routeName;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private static void nav(boolean on) {
        if (ClientMod.state != null) {
            ClientMod.state.navActive = on;
        }
    }

    private static void routeFlag(boolean on) {
        if (ClientMod.state != null) {
            ClientMod.state.routeActive = on;
        }
    }

    private static void say(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) {
            p.sendMessage(Text.literal(s), false);
        }
    }
}
