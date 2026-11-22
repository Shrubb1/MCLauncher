package com.piglurer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class PigLureConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("piglurer.json");

    // inventory item the bot must hold to trigger the pig
    public String orbItemName = "Shiny Orb";
    // required custom name on the pig
    public String pigName = "Shiny Pig";
    // owner-tag text marking a pig as ours; empty = own username
    public String targetOwnerTag = "";

    public double scanRange = 48;
    public int scanIntervalTicks = 20;
    public double interactRange = 2.5;
    public double tagPairRadius = 3.0;
    public double tagHeightMax = 3.0;

    public boolean equipOrbBeforeClick = true;
    public int pigLaunchDelayTicks = 18; // ~0.9s for the pig to fly off before herding starts
    public boolean useInteractAt = false;

    // goal mode: lastClickPos | fixed | entity
    public GoalCfg goal = new GoalCfg();
    public double goalDoneRadius = 1.5;
    public String herdStyle = "push";     // push | punch
    public double herdStagingDistance = 2.5; // inside the ~3b repel radius
    public int punchCooldownTicks = 12;   // ~0.6s between punches
    public double attackRange = 3.0;
    public int herdTimeoutTicks = 2400;
    public int stallCheckTicks = 60;        // 3s
    public double stallMinDisplacement = 0.5;
    public double stallAngleDeg = 30;

    // the "huge orb" that appears when the pig despawns
    public double orbSearchRadius = 8;
    public String orbMatch = "click";
    public double orbInteractRange = 3.0;
    public int orbSearchTimeoutTicks = 300; // 15s
    public boolean orbFallbackToBlock = true;

    public ShopCfg shop = new ShopCfg();
    public int restockThreshold = 1;
    public int loopDelayTicks = 60;         // 3s
    // random +- spread on every timed action so clicks don't land on the
    // exact same tick each loop - 0 disables
    public int clickJitterTicks = 5;

    // head/camera turning is rate-limited (deg per tick) instead of
    // instant snaps; clicks wait lookSettleTicks for the turn to land
    public float lookYawSpeedDeg = 22;
    public float lookPitchSpeedDeg = 16;
    public int lookSettleTicks = 8;

    // patrol: while scanning, walk the recorded waypoints in a loop; a full
    // lap with no pig found triggers /hub for a fresh lobby
    public PathCfg path = new PathCfg();
    public boolean hubRelobby = true;
    public int hubWaitTicks = 160;          // ~8s for the lobby swap
    public int hubScanTicks = 200;          // ~10s stationary scan after re-lobby

    public static class PathCfg {
        public boolean enabled = true;
        public java.util.List<double[]> waypoints = new java.util.ArrayList<>(); // [x,y,z]
    }

    public static class GoalCfg {
        public String mode = "lastClickPos";
        public double[] pos = null;         // [x,y,z] for mode=fixed
        public String entityMatch = "coin|orb";
    }

    public static class ShopCfg {
        public double[] pos = null;         // walk-to position
        public int[] click = null;          // block xyz to right-click, or null -> nearest entity
        public double clickRadius = 3.5;
        public int verifyDelayTicks = 24;
        public int maxAttempts = 5;
        public Integer navSlot = null;      // first GUI slot that opens the trade screen
        public Integer buySlot = null;      // final GUI slot to click (fallback if buyCount unset)
        public Integer buyCount = null;     // click the stack with this count (e.g. 64 = bulk orbs)
        public int buyClicks = 1;
        public int navDelayTicks = 10;      // wait for first GUI before nav click
        public int buyDelayTicks = 12;      // wait for trade GUI before buy click
        // recorded buy script - /piglure recordbuy captures your real clicks
        // and replays them; when non-empty it wins over nav/buySlot config
        public java.util.List<BuyStep> steps = new java.util.ArrayList<>();
    }

    public static class BuyStep {
        public String type;        // block | entity | gui
        public int delta;          // ticks to wait after the previous step
        public int x, y, z;        // block pos / entity position
        public String face;        // Direction name for block clicks
        public String entityType;  // entity type id for entity clicks
        public int slot;           // gui slot index
        public int button;         // mouse button for gui clicks
        public boolean shift;      // shift held -> QUICK_MOVE click
    }

    public static PigLureConfig load() {
        if (Files.exists(PATH)) {
            try (Reader r = Files.newBufferedReader(PATH)) {
                PigLureConfig c = GSON.fromJson(r, PigLureConfig.class);
                if (c != null) return c;
            } catch (Exception ignored) { }
        }
        PigLureConfig c = new PigLureConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            System.err.println("[PigLurer] failed to save config: " + e.getMessage());
        }
    }
}
