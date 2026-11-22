package com.piglurer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class PigLureStateMachine {
    public enum S {
        DISABLED, CHECK_ORBS, RESTOCKING, IDLE_SCAN,
        APPROACHING_PIG, HERDING_TO_GOAL, INTERACTING_GOAL, COOLDOWN
    }

    private static final int APPROACH_TIMEOUT_TICKS = 600;

    public S state = S.DISABLED;
    private final PigLureConfig cfg;
    private Navigator nav;

    // context
    private Pig pig;
    private Vec3 goalPos;
    private Vec3 orbAnchor;
    private int enteredAt;        // tick counter when the state was entered
    private int tick;             // global tick counter
    private int lastScanTick = -999;
    private Vec3 stallRef;
    private int stallCheckedAt;
    private Vec3 herdAim;
    private Vec3 herdAimPigPos;
    private int herdAimNudges = -1;
    private int herdAimAt;
    private int stallNudges;
    private int lastPunchTick;
    private int shopAttempts;
    private int shopBusyUntil;
    private boolean launchPending;
    private boolean orbClicked;
    private int orbConfirmUntil;
    private int orbClicks;          // click bursts fired at this orb
    private boolean tokenReceived;  // "SHINY! You received a shiny token" seen
    private boolean orbCharged;     // "SHINY! The orb is charged" seen (info)
    private long lastWarnMs;

    // tiny tick scheduler for delayed window clicks / verifies
    private final java.util.List<int[]> delayed = new java.util.ArrayList<>();
    private final java.util.Map<Integer, Runnable> delayedTasks = new java.util.HashMap<>();

    private void schedule(int delayTicks, Runnable r) {
        int id = System.identityHashCode(r);
        delayed.add(new int[] { tick + jittered(delayTicks), id });
        delayedTasks.put(id, r);
    }

    // +/- clickJitterTicks around the base delay so every timed action
    // lands somewhere in a window rather than a fixed tick
    private int jittered(int ticks) {
        int j = cfg.clickJitterTicks;
        if (j <= 0) return ticks;
        return Math.max(1, ticks - j
                + (int) (Math.random() * (2 * j + 1)));
    }

    private void runDelayed() {
        // collect due tasks and remove them BEFORE running - a task may
        // schedule() more work, and mutating during iteration crashes
        java.util.List<Runnable> due = null;
        for (java.util.Iterator<int[]> it = delayed.iterator(); it.hasNext();) {
            int[] t = it.next();
            if (t[0] <= tick) {
                Runnable r = delayedTasks.remove(t[1]);
                if (r != null) {
                    if (due == null) due = new java.util.ArrayList<>();
                    due.add(r);
                }
                it.remove();
            }
        }
        if (due != null) {
            for (Runnable r : due) r.run();
        }
    }

    // ---------- buy-sequence recorder ----------
    // /piglure recordbuy captures your real clicks (block/entity right
    // clicks + GUI slot clicks) with the pauses between them; restock
    // replays the captured script instead of blind slot numbers

    // 0 = not recording, 1 = buy script, 2 = hub script - both capture the
    // same inputs, they just save to different step lists
    private int recordTarget;
    private boolean sawContainer;
    private int recordLastTick;
    private final java.util.List<PigLureConfig.BuyStep> pendingSteps = new java.util.ArrayList<>();

    public boolean isRecording() {
        return recordTarget != 0;
    }

    public void toggleRecording(Minecraft c) {
        if (recordTarget != 0) finishRecording(c); else startRecording(c, 1);
    }

    public void toggleHubRecording(Minecraft c) {
        if (recordTarget != 0) finishRecording(c); else startRecording(c, 2);
    }

    private void startRecording(Minecraft c, int target) {
        disable(c); // don't have the bot acting while you're demonstrating
        recordingPath = false; // one recorder at a time
        pendingPath.clear();
        pendingSteps.clear();
        sawContainer = false;
        recordLastTick = tick;
        recordTarget = target;
        say(c, "§arecording " + (target == 1 ? "buy" : "hub")
                + " - perform the actions; it saves when the GUI closes");
    }

    private void finishRecording(Minecraft c) {
        java.util.List<PigLureConfig.BuyStep> store =
                recordTarget == 2 ? cfg.hub.steps : cfg.shop.steps;
        String which = recordTarget == 2 ? "hub" : "buy";
        recordTarget = 0;
        if (pendingSteps.isEmpty()) {
            say(c, "recording ended - nothing captured");
            return;
        }
        store.clear();
        store.addAll(pendingSteps);
        pendingSteps.clear();
        cfg.save();
        say(c, "§a" + which + " script saved - " + store.size() + " steps");
    }

    public void cancelRecording(Minecraft c, boolean wipeSaved,
            java.util.List<PigLureConfig.BuyStep> which) {
        recordTarget = 0;
        pendingSteps.clear();
        if (wipeSaved && !which.isEmpty()) {
            which.clear();
            cfg.save();
            say(c, "script cleared - that flow falls back to defaults");
        } else {
            say(c, "recording cancelled");
        }
    }

    // clamp each captured gap to 0.2s..4s - long pauses (walk time, typing)
    // would otherwise bake dead time into every restock
    private int nextDelta() {
        int d = tick - recordLastTick;
        recordLastTick = tick;
        return Math.min(Math.max(d, 4), 80);
    }

    public void recordBlockClick(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face) {
        if (recordTarget == 0) return;
        PigLureConfig.BuyStep s = new PigLureConfig.BuyStep();
        s.type = "block";
        s.delta = nextDelta();
        s.x = pos.getX(); s.y = pos.getY(); s.z = pos.getZ();
        s.face = face.name();
        pendingSteps.add(s);
        say(Minecraft.getInstance(), "rec #" + pendingSteps.size()
                + " block " + pos.toShortString() + " (+" + s.delta + "t)");
    }

    public void recordEntityClick(net.minecraft.world.entity.Entity e) {
        if (recordTarget == 0) return;
        PigLureConfig.BuyStep s = new PigLureConfig.BuyStep();
        s.type = "entity";
        s.delta = nextDelta();
        s.x = (int) Math.floor(e.getX());
        s.y = (int) Math.floor(e.getY());
        s.z = (int) Math.floor(e.getZ());
        s.entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(e.getType()).toString();
        pendingSteps.add(s);
        say(Minecraft.getInstance(), "rec #" + pendingSteps.size()
                + " entity " + s.entityType + " (+" + s.delta + "t)");
    }

    public void recordGuiClick(int slot, int button, boolean shift) {
        if (recordTarget == 0 || slot < 0) return;
        PigLureConfig.BuyStep s = new PigLureConfig.BuyStep();
        s.type = "gui";
        s.delta = nextDelta();
        s.slot = slot;
        s.button = button;
        s.shift = shift;
        pendingSteps.add(s);
        say(Minecraft.getInstance(), "rec #" + pendingSteps.size()
                + " gui slot " + slot + (shift ? " (shift)" : "") + " (+" + s.delta + "t)");
    }

    // closing the container = buy finished -> auto-save the script
    private void recordingTick(Minecraft c) {
        var scr = c.gui.screen();
        if (scr instanceof AbstractContainerScreen) {
            sawContainer = true;
        } else if (sawContainer && scr == null) {
            finishRecording(c);
        }
    }

    // ---------- patrol + path recorder ----------
    // while IDLE_SCAN, walk the recorded waypoints in a loop (pausing for
    // pig jobs); a full lap finding nothing -> /hub for a fresh lobby

    private int patrolIndex = -1;      // -1 = snap to nearest waypoint
    private int waypointStuckAt;       // tick we set off for the current wp
    private int lapStartTick;
    private int fastLaps;              // sub-5s laps (degenerate tight loop)

    // post-/hub routine, run inside tickPatrol while it owns the loop:
    // 1 wait for transfer | 2 walk to hub.pos | 3 replay hub.steps |
    // 4 player-count gate | 5 stand and scan before resuming patrol
    private int hubPhase;
    private int hubPhaseUntil;
    private int relobbyCount;
    private boolean pigFoundThisLap;   // any pig job during the current lap
    // a forced relobby (start-gate, panic escape) must run to completion -
    // a pig sighting mid-swap cannot cancel it like a patrol-lap relobby
    private boolean relobbyRequired;

    private boolean recordingPath;
    private Vec3 lastPathPoint;
    private final java.util.List<double[]> pendingPath = new java.util.ArrayList<>();

    // enderchest stash flow, driven inside IDLE_SCAN like the hub flow:
    // 1 send next /ec page | 2 wait for container | 3 clicks queued |
    // 4 page done -> next
    private int stashPhase;
    private int stashPage;
    private int stashPhaseUntil;
    private int lastInvCheck;

    // panic: someone in chat says our IGN or a bot keyword. IGN -> quip
    // then leave, keyword -> leave instantly. Both bail via /hub + the
    // recorded hub-change routine
    private int panicCooldownUntil;
    private final java.util.ArrayDeque<Integer> recentResponses = new java.util.ArrayDeque<>();

    // min gap between any chat line the bot sends - quips and shoo lines
    // share it so it can never chatter back-to-back
    private int lastTalkAt = -999999;
    private boolean tryTalk() {
        if (tick - lastTalkAt < cfg.panic.chatCooldownTicks) return false;
        lastTalkAt = tick;
        return true;
    }

    // someone spoke our name - respond (if the talk cooldown allows),
    // then escape
    public void panicMention(Minecraft client) {
        if (!cfg.panic.enabled || tick < panicCooldownUntil || state == S.DISABLED) return;
        panicCooldownUntil = tick + cfg.panic.cooldownTicks;
        if (tryTalk()) {
            String response = pickResponse();
            say(client, "§cIGN mentioned - responding then leaving");
            schedule(2, () -> {
                if (client.player != null) client.player.connection.sendChat(response);
            });
        } else {
            say(client, "§cIGN mentioned - talk on cooldown, just leaving");
        }
        schedule(2 + cfg.panic.responseDelayTicks, () -> panicEscape(client, "said our name"));
    }

    // a bot keyword showed up in chat - silence, just leave
    public void panicKeyword(Minecraft client, String word) {
        if (!cfg.panic.enabled || tick < panicCooldownUntil || state == S.DISABLED) return;
        panicCooldownUntil = tick + cfg.panic.cooldownTicks;
        panicEscape(client, "'" + word + "' in chat");
    }

    // instant bail: /hub then the recorded hub-change routine, exactly
    // like a no-pig lap - drops whatever pig/stash/nav work was running
    private void panicEscape(Minecraft client, String reason) {
        if (client.player == null) return;
        say(client, "§cpanic: " + reason + " - leaving");
        clearCtx();
        pigFoundThisLap = false;
        patrolIndex = -1;
        stashPhase = 0;
        client.player.connection.sendCommand("hub");
        transition(client, S.IDLE_SCAN, "panic escape");
        relobbyRequired = true;
        hubPhase = 1;
        hubPhaseUntil = tick + cfg.hubWaitTicks;
    }

    // random line from config/piglurer_responses.txt; never repeats one
    // already used within the last ~100 picks
    private String pickResponse() {
        java.util.List<String> list = PigLurerClient.panicResponses;
        if (list == null || list.isEmpty()) return "yo";
        int cap = Math.max(1, Math.min(100, list.size() - 1));
        java.util.List<Integer> ok = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!recentResponses.contains(i)) ok.add(i);
        }
        if (ok.isEmpty()) {
            // fewer lines than the window - drop old picks and reuse
            while (recentResponses.size() > cap / 2) recentResponses.poll();
            return pickResponse();
        }
        int pick = ok.get((int) (Math.random() * ok.size()));
        recentResponses.addLast(pick);
        while (recentResponses.size() > cap) recentResponses.poll();
        return list.get(pick);
    }

    // ---------- proximity harassment ----------
    // a player parked in the bot's face is almost always a person checking
    // whether it's a bot. first sustained contact -> a shoo line; if they
    // linger -> freeze, stare at them a couple seconds, then probably bail
    private Player closePlayer;
    private int closeTicks;
    private boolean proxWarned;
    private int proxWarnedAt;
    private int proxPauseUntil;
    private int proxCooldownUntil;

    // returns true while it's holding the stare pause (patrol stays frozen)
    private boolean tickProximity(Minecraft client) {
        LocalPlayer p = client.player;
        if (!cfg.proximity.enabled || tick < proxCooldownUntil) {
            closeTicks = 0; closePlayer = null; proxWarned = false;
            return false;
        }
        Player near = null;
        double bd = cfg.proximity.radius * cfg.proximity.radius;
        for (Player pl : client.level.players()) {
            if (pl == p) continue;
            double d = pl.distanceToSqr(p);
            if (d < bd) { bd = d; near = pl; }
        }
        if (near != closePlayer) { // different person stepped in, restart the clock
            closePlayer = near;
            closeTicks = 0;
            proxWarned = false;
        }
        if (near == null) return false;
        closeTicks++;

        if (proxPauseUntil > 0) { // frozen mid-stare
            Navigator.lookAt(p, near.getEyePosition());
            if (tick >= proxPauseUntil) {
                proxPauseUntil = 0;
                proxCooldownUntil = tick + cfg.proximity.cooldownTicks;
                if (Math.random() < cfg.proximity.leaveChance) {
                    panicEscape(client, near.getName().getString() + " wouldn't back off");
                } else {
                    say(client, "proximity: stared them down, carrying on");
                }
            }
            return true; // stay paused
        }
        if (!proxWarned && closeTicks > cfg.proximity.warnAfterTicks) {
            proxWarned = true;
            proxWarnedAt = tick;
            if (tryTalk()) {
                String line = pickShoo();
                schedule(2, () -> {
                    if (client.player != null) client.player.connection.sendChat(line);
                });
                say(client, "proximity: " + near.getName().getString() + " too close - told to move");
            } else {
                say(client, "proximity: " + near.getName().getString()
                        + " too close - talk on cooldown, not responding");
            }
        } else if (proxWarned && tick - proxWarnedAt > cfg.proximity.escalateAfterTicks) {
            // still standing there: stop everything and hold eye contact
            nav.cancel();
            proxPauseUntil = tick + cfg.proximity.stareTicks;
            say(client, "proximity: " + near.getName().getString()
                    + " still there - pausing to stare");
        }
        return proxPauseUntil > 0;
    }

    private String pickShoo() {
        java.util.List<String> list = PigLurerClient.shooResponses;
        if (list == null || list.isEmpty()) return "go away";
        return list.get((int) (Math.random() * list.size()));
    }

    public boolean isRecordingPath() {
        return recordingPath;
    }

    public void togglePathRecording(Minecraft c) {
        if (recordingPath) finishPathRecording(c); else startPathRecording(c);
    }

    private void startPathRecording(Minecraft c) {
        disable(c);
        recordTarget = 0; // one recorder at a time
        pendingSteps.clear();
        pendingPath.clear();
        lastPathPoint = null;
        recordingPath = true;
        say(c, "§arecording path - run your patrol route, /piglure recordpath again to save");
    }

    private void finishPathRecording(Minecraft c) {
        recordingPath = false;
        if (pendingPath.isEmpty()) {
            say(c, "recordpath ended - nothing recorded");
            return;
        }
        cfg.path.waypoints.clear();
        cfg.path.waypoints.addAll(pendingPath);
        pendingPath.clear();
        cfg.save();
        patrolIndex = -1;
        say(c, "§apath saved - " + cfg.path.waypoints.size() + " waypoints");
    }

    public void cancelPathRecording(Minecraft c, boolean wipeSaved) {
        recordingPath = false;
        pendingPath.clear();
        if (wipeSaved && !cfg.path.waypoints.isEmpty()) {
            cfg.path.waypoints.clear();
            cfg.save();
            patrolIndex = -1;
            say(c, "patrol path cleared");
        } else {
            say(c, "path recording cancelled");
        }
    }

    // add your current position as a single waypoint (precise picking)
    public void addPathPoint(Minecraft c) {
        if (c.player == null) return;
        Vec3 pos = c.player.position();
        cfg.path.waypoints.add(new double[] { pos.x, pos.y, pos.z });
        cfg.save();
        say(c, "waypoint #" + cfg.path.waypoints.size() + " " + fmt(pos));
    }

    private void pathRecordingTick(Minecraft c) {
        if (c.player == null) return;
        Vec3 pos = c.player.position();
        // keep a point every ~4 blocks moved - smooths the route without
        // flooding it with a point per tick
        if (lastPathPoint == null || pos.distanceTo(lastPathPoint) >= 4.0) {
            lastPathPoint = pos;
            pendingPath.add(new double[] { pos.x, pos.y, pos.z });
            say(c, "path #" + pendingPath.size() + " " + fmt(pos));
        }
    }

    private void tickPatrol(Minecraft client) {
        LocalPlayer p = client.player;
        if (hubPhase != 0) {
            tickHubFlow(client);
            return;
        }
        java.util.List<double[]> wps = cfg.path.waypoints;
        if (!cfg.path.enabled || wps.isEmpty()) return;

        if (patrolIndex < 0 || patrolIndex >= wps.size()) {
            patrolIndex = nearestWaypoint(p, wps);
            waypointStuckAt = tick;
            lapStartTick = tick;
        }
        double[] w = wps.get(patrolIndex);
        Vec3 wp = new Vec3(w[0], w[1], w[2]);

        if (p.position().distanceTo(wp) < 2.0) {
            advancePatrol(client, wps.size());
            return;
        }
        if (tick - waypointStuckAt > 160) { // ~8s - unreachable, skip it
            say(client, "patrol: waypoint " + (patrolIndex + 1) + " unreachable - skipping");
            advancePatrol(client, wps.size());
            return;
        }
        nav.setTarget(wp.add(0, 0.001, 0), 1.5);
    }

    private void advancePatrol(Minecraft client, int n) {
        patrolIndex++;
        if (patrolIndex >= n) {
            patrolIndex = 0;
            boolean realLap = tick - lapStartTick >= 100; // >=5s spent patrolling
            if (realLap) {
                fastLaps = 0;
                onPatrolLap(client);
            } else if (++fastLaps >= 3) {
                // degenerate path (points too close) still counts after 3
                fastLaps = 0;
                onPatrolLap(client);
            }
            lapStartTick = tick;
        }
        waypointStuckAt = tick;
    }

    private void onPatrolLap(Minecraft client) {
        if (pigFoundThisLap) {
            // saw at least one pig this lap - lobby's alive, run it again
            pigFoundThisLap = false;
            say(client, "patrol lap done - pig seen, running another lap");
            return;
        }
        if (!cfg.hubRelobby || client.player == null) {
            say(client, "patrol lap done, no pig - looping");
            return;
        }
        beginRelobby(client, "no pig this lap");
    }

    // the recorded hub script is the lobby change - /hub only fires when
    // there's no script to run
    private void beginRelobby(Minecraft client, String reason) {
        relobbyCount++;
        pigFoundThisLap = false;
        if (cfg.hub.pos != null || !cfg.hub.steps.isEmpty()) {
            say(client, reason + " - running hub change script");
            if (cfg.hub.pos != null) {
                hubPhase = 2; // walk to the hub spot first
                hubPhaseUntil = tick + 300;
            } else {
                startHubSteps(client);
            }
        } else {
            say(client, reason + " - /hub for a fresh lobby");
            client.player.connection.sendCommand("hub");
            hubPhase = 1;
            hubPhaseUntil = tick + cfg.hubWaitTicks;
        }
    }

    private void tickHubFlow(Minecraft client) {
        LocalPlayer p = client.player;
        switch (hubPhase) {
            case 1 -> { // lobby swap in progress
                if (tick < hubPhaseUntil) return;
                if (cfg.hub.pos != null) {
                    hubPhase = 2;
                    hubPhaseUntil = tick + 300; // walk timeout
                } else {
                    startHubSteps(client);
                }
            }
            case 2 -> { // walk to the saved hub spot
                Vec3 hp = new Vec3(cfg.hub.pos[0], cfg.hub.pos[1], cfg.hub.pos[2]);
                if (p.position().distanceTo(hp) < 2.5 || tick >= hubPhaseUntil) {
                    nav.cancel();
                    startHubSteps(client);
                } else {
                    nav.setTarget(hp.add(0, 0.001, 0), 1.5);
                }
            }
            case 3 -> { // hub script still replaying
                if (tick >= hubPhaseUntil) hubPhase = 4;
            }
            case 4 -> { // only stay if the lobby is empty enough
                int n = hubPlayerCount(client);
                if (n >= 0 && n > cfg.hub.maxPlayers && relobbyCount < cfg.hub.maxRelobbies) {
                    say(client, "Players (" + n + ") > " + cfg.hub.maxPlayers + " - relobbying");
                    beginRelobby(client, "Players (" + n + ") in lobby");
                    return;
                }
                if (n < 0) {
                    say(client, "couldn't read player count - staying");
                } else {
                    say(client, "Players (" + n + ")"
                            + (n > cfg.hub.maxPlayers ? " - hit relobby cap, staying anyway" : " - lobby ok"));
                }
                hubPhase = 5;
                hubPhaseUntil = tick + cfg.hubScanTicks;
            }
            case 5 -> { // stationary scan of the fresh lobby
                if (tick >= hubPhaseUntil) {
                    hubPhase = 0;
                    relobbyCount = 0;
                    relobbyRequired = false;
                }
            }
        }
    }

    private void startHubSteps(Minecraft client) {
        if (cfg.hub.steps.isEmpty()) {
            hubPhase = 4;
            return;
        }
        int at = 0;
        for (PigLureConfig.BuyStep s : cfg.hub.steps) {
            at += Math.min(Math.max(s.delta, 4), 80);
            final PigLureConfig.BuyStep step = s;
            schedule(at, () -> replayStep(client, step, 0));
        }
        say(client, "replaying hub script (" + cfg.hub.steps.size() + " steps)");
        hubPhase = 3;
        // script end + transfer settle time before the player-count check -
        // counting mid-swap would read the old lobby
        hubPhaseUntil = tick + at + cfg.hubWaitTicks;
    }

    // "Players (N)" appears as a row in the SkyBlock tab list - read every
    // listed entry's display name and pull the number out; -1 = unreadable
    private int hubPlayerCount(Minecraft client) {
        if (client.player == null || client.player.connection == null) return -1;
        java.util.regex.Pattern re = java.util.regex.Pattern.compile(
                "players\\s*\\((\\d+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE);
        int best = -1;
        for (net.minecraft.client.multiplayer.PlayerInfo info
                : client.player.connection.getListedOnlinePlayers()) {
            var dn = info.getTabListDisplayName();
            if (dn == null) continue;
            var m = re.matcher(dn.getString());
            if (m.find()) best = Math.max(best, Integer.parseInt(m.group(1)));
        }
        return best;
    }

    private int nearestWaypoint(LocalPlayer p, java.util.List<double[]> wps) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < wps.size(); i++) {
            double[] w = wps.get(i);
            double d = p.position().distanceToSqr(w[0], w[1], w[2]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    public PigLureStateMachine(PigLureConfig cfg) {
        this.cfg = cfg;
    }

    // ---------- plumbing ----------

    private void say(Minecraft c, String msg) {
        if (c.player != null) c.player.sendSystemMessage(Component.literal("§7[PigLure]§r " + msg));
        System.out.println("[PigLure] " + msg);
    }

    private void transition(Minecraft c, S next, String reason) {
        if (state == next) return;
        say(c, "§e" + state + " -> " + next + "§r " + reason);
        state = next;
        enteredAt = tick;
        if (nav != null && (next == S.CHECK_ORBS || next == S.IDLE_SCAN || next == S.COOLDOWN || next == S.DISABLED)) {
            nav.cancel();
        }
    }

    private void clearCtx() {
        pig = null;
        goalPos = null;
        orbAnchor = null;
        stallRef = null;
        stallNudges = 0;
        herdAim = null;
        herdAimPigPos = null;
        herdAimNudges = -1;
        shopAttempts = 0;
        launchPending = false;
        orbClicked = false;
        orbClicks = 0;
        tokenReceived = false;
        orbCharged = false;
    }

    // chat feed: SHINY! lines are the only reliable confirm - "orb is
    // charged" means the pig went in, "received a shiny token" means the
    // orb click actually landed (the orb plate swallows interact() and
    // says nothing otherwise)
    public void onGameChat(String text) {
        if (!enabled()) return;
        // our own status lines loop back through this listener - they
        // contain trigger words ("orb expired", "hub change script") and
        // re-firing on them is an infinite recursion crash
        if (text.startsWith("[PigLure]")) return;
        String l = text.toLowerCase();
        // "the orb expired" (or despawned/disappeared) while we still have
        // a pending orb - bail the lobby and start over
        if ((state == S.INTERACTING_GOAL || orbAnchor != null)
                && l.contains("orb")
                && (l.contains("expired") || l.contains("despawned") || l.contains("disappeared"))) {
            expireOrb(Minecraft.getInstance());
            return;
        }
        if (!text.contains("SHINY")) return;
        if (l.contains("received a shiny token")) {
            tokenReceived = true;
        } else if (l.contains("orb is charged") && !orbCharged) {
            orbCharged = true;
            say(Minecraft.getInstance(), "§d" + text.trim());
        }
    }

    // the orb timed out before the token landed - /hub for a fresh lobby,
    // then patrol picks up from the nearest waypoint like a no-pig lap
    private void expireOrb(Minecraft client) {
        // clear context BEFORE speaking - the say line carries trigger
        // words and must never be able to re-enter this path
        clearCtx();
        patrolIndex = -1;
        say(client, "§corb expired - /hub and restarting");
        transition(client, S.IDLE_SCAN, "orb expired");
        beginRelobby(client, "orb expired");
    }

    public void enable(Minecraft c) {
        clearCtx();
        patrolIndex = -1;
        hubPhase = 0;
        relobbyCount = 0;
        fastLaps = 0;
        pigFoundThisLap = false;
        panicCooldownUntil = 0;
        stashPhase = 0;
        relobbyRequired = false;
        // don't run in a populated lobby - check the tab count first and
        // re-lobby before doing anything else
        int n = hubPlayerCount(c);
        if (n > cfg.hub.maxPlayers) {
            transition(c, S.IDLE_SCAN, "enabled");
            relobbyRequired = true;
            beginRelobby(c, "Players (" + n + ") in lobby - changing before starting");
            return;
        }
        if (n >= 0) say(c, "Players (" + n + ") - lobby ok");
        transition(c, S.CHECK_ORBS, "enabled");
    }

    public void disable(Minecraft c) {
        clearCtx();
        delayed.clear();
        delayedTasks.clear();
        if (nav != null) nav.cancel();
        transition(c, S.DISABLED, "disabled");
    }

    public boolean enabled() {
        return state != S.DISABLED;
    }

    // ---------- main loop ----------

    public void tick(Minecraft client) {
        tick++;
        if (nav == null) nav = new Navigator(client, cfg);
        if (recordTarget != 0) recordingTick(client);
        if (recordingPath) pathRecordingTick(client);
        if (state == S.DISABLED) return;

        runDelayed();
        nav.tick();

        switch (state) {
            case CHECK_ORBS -> {
                int n = Sense.countOrbs(client.player.getInventory(), cfg.orbItemName);
                if (n >= cfg.restockThreshold) {
                    transition(client, S.IDLE_SCAN, n + " orb(s)");
                } else {
                    clearCtx();
                    transition(client, S.RESTOCKING, "orbs=" + n + " < threshold");
                }
            }
            case RESTOCKING -> tickRestocking(client);
            case IDLE_SCAN -> tickScan(client);
            case APPROACHING_PIG -> tickApproach(client);
            case HERDING_TO_GOAL -> tickHerd(client);
            case INTERACTING_GOAL -> tickOrb(client);
            case COOLDOWN -> {
                if (tick - enteredAt >= cfg.loopDelayTicks) {
                    transition(client, S.CHECK_ORBS, "cooldown done");
                }
            }
            default -> { }
        }
    }

    private void tickScan(Minecraft client) {
        LocalPlayer p = client.player;
        // orb check is held while a lobby change is in flight - it resumes
        // once the hub flow finishes
        if (hubPhase == 0
                && Sense.countOrbs(p.getInventory(), cfg.orbItemName) < cfg.restockThreshold) {
            transition(client, S.CHECK_ORBS, "orbs ran out");
            return;
        }
        // enderchest stash - a full inventory pauses patrol while each ec
        // page gets filled; keep-named items (orbs/tokens) stay behind
        if (hubPhase == 0 && stashPhase != 0) {
            tickStash(client);
            return;
        }
        if (hubPhase == 0 && cfg.stash.enabled && tick - lastInvCheck >= 20) {
            lastInvCheck = tick;
            if (inventoryFull(p)) {
                say(client, "inventory full - stashing into enderchest");
                nav.cancel();
                stashPage = 0;
                stashPhase = 1;
                return;
            }
        }
        // someone parked in our face - shoo/stare logic; a true return
        // means we're holding the freeze, so patrol stays paused too
        if (hubPhase == 0 && stashPhase == 0 && tickProximity(client)) return;
        // a forced relobby (start-gate/panic) suppresses pig scanning until
        // it finishes - except phase 5, whose whole job is scanning the new
        // lobby, where a find also clears the requirement
        boolean scanBlocked = relobbyRequired && hubPhase != 0 && hubPhase != 5;
        if (!scanBlocked && tick - lastScanTick >= cfg.scanIntervalTicks) {
            lastScanTick = tick;
            String ourName = p.getName().getString();
            Pig found = Sense.findShinyPig(client.level, p.position(), cfg, ourName);
            if (found != null) {
                // pause the patrol for the pig job; resume from the nearest
                // waypoint afterwards (herding may have dragged us elsewhere).
                // a pig means this lobby is fine - drop any relobby flow
                patrolIndex = -1;
                hubPhase = 0;
                relobbyCount = 0;
                relobbyRequired = false;
                pigFoundThisLap = true;
                clearCtx();
                pig = found;
                transition(client, S.APPROACHING_PIG,
                        "pig @" + fmt(found.position()) + " dist=" + String.format("%.1f", found.position().distanceTo(p.position())));
                return;
            }
        }
        tickPatrol(client);
    }

    private void tickRestocking(Minecraft client) {
        LocalPlayer p = client.player;
        if (cfg.shop.pos == null) {
            nav.cancel();
            warnThrottled(client, "shop.pos not set — stand at the shop and run /piglure setshop");
            return;
        }
        Vec3 target = new Vec3(cfg.shop.pos[0], cfg.shop.pos[1], cfg.shop.pos[2]);
        double dist = p.position().distanceTo(target);

        if (dist > 2.5) {
            if (!nav.isActive() || nav.distTo(p) > 3) {
                nav.setTarget(target.add(0, 0.001, 0), 1.5);
            }
            return;
        }
        if (tick < shopBusyUntil) return;
        nav.cancel();

        shopAttempts++;
        if (!cfg.shop.steps.isEmpty()) {
            // recorded buy script wins over the fixed-slot flow - replay
            // the captured clicks with their original pauses
            replayBuy(client);
            return;
        }

        activateShop(client, target);

        // multi-step GUI flow: click navSlot in the first screen to reach
        // the trade screen, then buy - either the stack whose count matches
        // buyCount (the bulk option) or a fixed slot. Delays count from the
        // activation click, which fires after the head turn settles
        int off = cfg.lookSettleTicks;
        int extra = off;
        if (cfg.shop.navSlot != null) {
            schedule(off + cfg.shop.navDelayTicks, () -> clickNav(client));
            extra = Math.max(extra, off + cfg.shop.navDelayTicks);
        }
        if (cfg.shop.buyCount != null || cfg.shop.buySlot != null) {
            int base = off + (cfg.shop.navSlot != null ? cfg.shop.navDelayTicks : 0)
                    + cfg.shop.buyDelayTicks;
            int clicks = Math.max(1, cfg.shop.buyClicks);
            for (int i = 0; i < clicks; i++) {
                schedule(base + i * 6, () -> clickBuy(client));
            }
            extra = Math.max(extra, base + (clicks - 1) * 6);
        }
        scheduleVerify(client, extra);
    }

    // aims first, fires after lookSettleTicks so the head turn lands before
    // the click instead of snap-aim-click
    private void activateShop(Minecraft client, Vec3 target) {
        LocalPlayer p = client.player;
        Vec3 aim = null;
        Runnable fire = null;
        if (cfg.shop.click != null) {
            BlockPos bp = new BlockPos(cfg.shop.click[0], cfg.shop.click[1], cfg.shop.click[2]);
            Vec3 hit = Vec3.atCenterOf(bp);
            aim = hit;
            fire = () -> {
                client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, Direction.UP, bp, false));
                say(client, "activateBlock @" + bp.toShortString());
            };
        } else {
            Entity shopkeeper = Sense.nearestShopInteractable(client.level, target, cfg.shop.clickRadius);
            if (shopkeeper != null) {
                aim = Sense.center(shopkeeper);
                fire = () -> {
                    client.gameMode.interact(p, shopkeeper,
                            new EntityHitResult(shopkeeper), InteractionHand.MAIN_HAND);
                    say(client, "activateEntity " + shopkeeper.getName().getString());
                };
            }
        }
        if (fire == null) {
            say(client, "no shop.click and no entity nearby — swinging");
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }
        Navigator.lookAt(p, aim);
        schedule(cfg.lookSettleTicks, fire);
    }

    // replay the recorded buy script step-by-step; if the recording didn't
    // include a right-click to open the shop, open it first via setshop's
    // saved target so a GUI is up before the gui steps run
    private void replayBuy(Minecraft client) {
        boolean opensShop = cfg.shop.steps.stream().anyMatch(
                s -> "block".equals(s.type) || "entity".equals(s.type));
        int at = 0;
        if (!opensShop) {
            activateShop(client, new Vec3(cfg.shop.pos[0], cfg.shop.pos[1], cfg.shop.pos[2]));
            at += cfg.lookSettleTicks + cfg.shop.navDelayTicks;
        }
        for (PigLureConfig.BuyStep s : cfg.shop.steps) {
            at += Math.min(Math.max(s.delta, 4), 80);
            final PigLureConfig.BuyStep step = s;
            schedule(at, () -> replayStep(client, step, 0));
        }
        say(client, "replaying buy script (" + cfg.shop.steps.size() + " steps)");
        scheduleVerify(client, at);
    }

    private void replayStep(Minecraft client, PigLureConfig.BuyStep s, int retry) {
        // buy scripts replay in RESTOCKING, hub scripts in IDLE_SCAN
        if (state != S.RESTOCKING && state != S.IDLE_SCAN) return;
        LocalPlayer p = client.player;
        if (p == null) return;
        switch (s.type) {
            case "gui" -> {
                if (client.gui.screen() instanceof AbstractContainerScreen<?> hs) {
                    try {
                        client.gameMode.handleContainerInput(
                                hs.getMenu().containerId, s.slot, s.button,
                                s.shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, p);
                        say(client, "replay gui slot " + s.slot + (s.shift ? " (shift)" : ""));
                    } catch (Exception e) {
                        say(client, "replay gui click failed: " + e.getMessage());
                    }
                } else if (retry < 10) {
                    // screen is still swapping - poll until it appears
                    schedule(4, () -> replayStep(client, s, retry + 1));
                } else {
                    say(client, "§creplay: no GUI open for slot " + s.slot);
                }
            }
            case "block" -> {
                BlockPos bp = new BlockPos(s.x, s.y, s.z);
                Direction face;
                try {
                    face = Direction.valueOf(s.face);
                } catch (Exception e) {
                    face = Direction.UP;
                }
                Vec3 hit = Vec3.atCenterOf(bp);
                Direction f = face;
                Navigator.lookAt(p, hit);
                waitAimThen(client, hit, () -> {
                    client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                            new BlockHitResult(hit, f, bp, false));
                    say(client, "replay block click @" + bp.toShortString());
                }, 0);
            }
            case "entity" -> {
                Entity e = findRecordedEntity(client, s);
                if (e != null) {
                    Vec3 aim = Sense.center(e);
                    Navigator.lookAt(p, aim);
                    final Entity target = e;
                    waitAimThen(client, aim, () -> {
                        client.gameMode.interact(p, target,
                                new EntityHitResult(target), InteractionHand.MAIN_HAND);
                        say(client, "replay entity " + target.getName().getString());
                    }, 0);
                } else {
                    say(client, "§creplay: no " + s.entityType + " near recorded spot");
                }
            }
        }
    }

    // fires only once the head turn has actually landed on the aim point
    // (~1s max so an unreachable aim can't hang the script) - replaces
    // flat lookSettleTicks so big turns can't click while still swinging
    private void waitAimThen(Minecraft client, Vec3 aim, Runnable fire, int tries) {
        LocalPlayer p = client.player;
        if (p == null) return;
        if (Navigator.aimedAt(p, aim) || tries > 20) {
            fire.run();
        } else {
            Navigator.lookAt(p, aim);
            schedule(2, () -> waitAimThen(client, aim, fire, tries + 1));
        }
    }

    // entity clicks are recorded as type + position - on replay find the
    // closest entity of the same type near where it was clicked
    private Entity findRecordedEntity(Minecraft client, PigLureConfig.BuyStep s) {
        Entity best = null;
        double bd = 16; // must be within 4 blocks of the recorded spot
        for (Entity e : client.level.entitiesForRendering()) {
            if (e == client.player) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (key == null || !key.toString().equals(s.entityType)) continue;
            double d = e.position().distanceToSqr(s.x + 0.5, s.y + 0.5, s.z + 0.5);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private void scheduleVerify(Minecraft client, int extraTicks) {
        // busy window covers the script plus the worst-case jittered delay
        shopBusyUntil = tick + extraTicks + cfg.shop.verifyDelayTicks + cfg.clickJitterTicks;
        schedule(extraTicks + cfg.shop.verifyDelayTicks, () -> verifyRestock(client));
    }

    private void verifyRestock(Minecraft client) {
        if (state != S.RESTOCKING) return;
        int n = Sense.countOrbs(client.player.getInventory(), cfg.orbItemName);
        if (n > 0) {
            say(client, "restock ok (" + n + " orbs)");
            clearCtx();
            transition(client, S.IDLE_SCAN, "restocked");
        } else if (shopAttempts >= cfg.shop.maxAttempts) {
            say(client, "§cpurchase not confirmed after " + cfg.shop.maxAttempts + " tries — pausing 10s");
            shopAttempts = 0;
            shopBusyUntil = tick + 200;
        } else {
            say(client, "buy attempt " + shopAttempts + "/" + cfg.shop.maxAttempts);
        }
    }

    private void clickNav(Minecraft client) {
        if (client.gui.screen() instanceof AbstractContainerScreen<?> hs) {
            try {
                client.gameMode.handleContainerInput(
                        hs.getMenu().containerId, cfg.shop.navSlot, 0,
                        ContainerInput.PICKUP, client.player);
                say(client, "nav click slot " + cfg.shop.navSlot);
            } catch (Exception e) {
                say(client, "nav click failed: " + e.getMessage());
            }
        } else {
            say(client, "§cno GUI open for nav click - wrong shop target?");
        }
    }

    // buy by stack count first (the bulk option e.g. x64 orbs), buySlot
    // as fallback; name match preferred but count alone wins if needed
    private void clickBuy(Minecraft client) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> hs)) {
            say(client, "§cno GUI open for buy click");
            return;
        }
        if (cfg.shop.buyCount != null) {
            if (clickStackByCount(client, hs, true)) return;
            clickStackByCount(client, hs, false);
            return;
        }
        clickBuySlot(client, cfg.shop.buySlot);
    }

    private boolean clickStackByCount(Minecraft client,
            AbstractContainerScreen<?> hs, boolean requireName) {
        net.minecraft.world.inventory.AbstractContainerMenu menu = hs.getMenu();
        String needle = cfg.orbItemName.toLowerCase();
        for (net.minecraft.world.inventory.Slot s : menu.slots) {
            ItemStack st = s.getItem();
            if (st.isEmpty() || st.getCount() != cfg.shop.buyCount) continue;
            if (requireName && !st.getHoverName().getString()
                    .toLowerCase().contains(needle)) continue;
            int idx = menu.slots.indexOf(s);
            client.gameMode.handleContainerInput(
                    menu.containerId, idx, 0, ContainerInput.PICKUP, client.player);
            say(client, "clicked buy x" + cfg.shop.buyCount + " '"
                    + st.getHoverName().getString() + "' in slot " + idx);
            return true;
        }
        if (!requireName) {
            say(client, "§cno stack of " + cfg.shop.buyCount + " in the open GUI");
        }
        return false;
    }

    private void clickBuySlot(Minecraft client, int slot) {
        if (client.gui.screen() instanceof AbstractContainerScreen<?> hs) {
            try {
                client.gameMode.handleContainerInput(
                        hs.getMenu().containerId, slot, 0, ContainerInput.PICKUP, client.player);
            } catch (Exception e) {
                say(client, "buySlot click failed: " + e.getMessage());
            }
        }
    }

    private void tickApproach(Minecraft client) {
        LocalPlayer p = client.player;
        if (launchPending) return;
        if (pig == null || !pig.isAlive()) {
            clearCtx();
            transition(client, S.IDLE_SCAN, "pig lost before approach");
            return;
        }
        if (tick - enteredAt > APPROACH_TIMEOUT_TICKS) {
            boolean routed = nav.hasPath();
            clearCtx();
            transition(client, S.IDLE_SCAN,
                    routed ? "approach timeout - stuck" : "approach timeout - no route");
            return;
        }

        nav.setTarget(Sense.center(pig), cfg.interactRange - 0.4);
        Navigator.lookAt(p, Sense.center(pig));

        if (p.position().distanceTo(Sense.center(pig)) <= cfg.interactRange) {
            clickPig(client, p);
        }
    }

    private void clickPig(Minecraft client, LocalPlayer p) {
        nav.cancel();
        if (cfg.equipOrbBeforeClick) equipOrb(client, p);
        final Pig target = pig;
        Navigator.lookAt(p, Sense.center(target));

        goalPos = target.position(); // pig's position at right-click time = herd target
        // same as /piglure setgoal - persists it so mode=fixed and
        // later sessions use this spot too
        cfg.goal.pos = new double[] { target.getX(), target.getY(), target.getZ() };
        PigLurerClient.config.save();

        launchPending = true;
        // let the head turn land before the click goes out
        schedule(cfg.lookSettleTicks, () -> {
            client.gameMode.interact(p, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
            if (cfg.useInteractAt) {
                client.gameMode.interact(p, target,
                        new EntityHitResult(target, Sense.center(target)), InteractionHand.MAIN_HAND);
            }
            say(client, "activated pig @" + fmt(target.position()) + " goal=" + fmt(goalPos));
        });
        schedule(cfg.lookSettleTicks + cfg.pigLaunchDelayTicks, () -> {
            launchPending = false;
            if (state != S.APPROACHING_PIG) return;
            if (!target.isAlive()) {
                orbAnchor = goalPos;
                transition(client, S.INTERACTING_GOAL, "pig despawned instantly");
                return;
            }
            stallRef = target.position();
            stallCheckedAt = tick;
            stallNudges = 0;
            transition(client, S.HERDING_TO_GOAL, "pig launched — herding");
        });
    }

    private void equipOrb(Minecraft client, LocalPlayer p) {
        Inventory inv = p.getInventory();
        int slot = Sense.findOrbInvSlot(inv, cfg.orbItemName);
        if (slot < 0) return;
        if (slot <= 8) {
            inv.setSelectedSlot(slot);
            return;
        }
        // move it into the current hotbar slot via a swap click on the player
        // screen handler (containerId 0): main-inv screen slots are 9-35
        try {
            client.gameMode.handleContainerInput(0, slot, inv.getSelectedSlot(), ContainerInput.SWAP, p);
        } catch (Exception e) {
            say(client, "orb swap failed: " + e.getMessage());
        }
    }

    private Vec3 resolveGoalPos(Minecraft client) {
        if ("fixed".equals(cfg.goal.mode) && cfg.goal.pos != null) {
            return new Vec3(cfg.goal.pos[0], cfg.goal.pos[1], cfg.goal.pos[2]);
        }
        if ("entity".equals(cfg.goal.mode)) {
            java.util.regex.Pattern re = java.util.regex.Pattern.compile(
                    cfg.goal.entityMatch, java.util.regex.Pattern.CASE_INSENSITIVE);
            Vec3 best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : client.level.entitiesForRendering()) {
                if (e == pig || e == client.player || e instanceof Player) continue;
                if (!re.matcher(Sense.entityText(e)).find()) continue;
                double d = e.position().distanceTo(pig.position());
                if (d < bestDist) { bestDist = d; best = e.position(); }
            }
            if (best != null) return best;
        }
        return goalPos; // lastClickPos
    }

    private void tickHerd(Minecraft client) {
        LocalPlayer p = client.player;
        if (pig == null || !pig.isAlive()) {
            nav.cancel();
            orbAnchor = goalPos != null ? goalPos : (pig != null ? pig.position() : p.position());
            transition(client, S.INTERACTING_GOAL, "pig despawned — looking for orb");
            return;
        }
        if (tick - enteredAt > cfg.herdTimeoutTicks) {
            clearCtx();
            transition(client, S.COOLDOWN, "herd timeout");
            return;
        }

        Vec3 goal = resolveGoalPos(client);
        if (goal == null) {
            transition(client, S.COOLDOWN, "no goal position");
            return;
        }

        // stall detection
        if (tick - stallCheckedAt > cfg.stallCheckTicks) {
            double moved = stallRef == null ? 0 : pig.position().distanceTo(stallRef);
            if (moved < cfg.stallMinDisplacement) {
                stallNudges++;
                say(client, "pig stalled (" + String.format("%.2f", moved) + "b) — nudge #" + stallNudges);
            } else {
                stallNudges = 0;
            }
            stallRef = pig.position();
            stallCheckedAt = tick;
        }
        double angle = stallNudges == 0 ? 0
                : (stallNudges % 2 == 1 ? 1 : -1) * Math.ceil(stallNudges / 2.0) * Math.toRadians(cfg.stallAngleDeg);

        // unit vector goal -> pig on XZ, rotated by stall offset
        double dx = pig.getX() - goal.x, dz = pig.getZ() - goal.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.01) { dx = 1; dz = 0; len = 1; }
        dx /= len; dz /= len;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double rx = dx * cos - dz * sin;
        double rz = dx * sin + dz * cos;

        // Repel mechanics: the pig flees directly away from the player within
        // ~2-3 blocks. Hold the standoff point on the far side of the pig from
        // the goal — "away from the bot" then points at the goal. Off-axis
        // slides the pig sideways; perfectly behind = straight.
        double distToGoal = pig.position().distanceTo(goal);
        double stagingDist = distToGoal <= cfg.goalDoneRadius + 0.5 ? 1.2 : cfg.herdStagingDistance;
        Vec3 staging = pig.position().add(rx * stagingDist, 0, rz * stagingDist);
        double repelR = Math.max(3.2, cfg.herdStagingDistance + 0.7);

        // Where we WANT to stand is geometry; where we CAN stand is the
        // map. Score a fan of spots around the pig's repel circle by
        // whether A* can actually route there and how much it has to
        // detour - this is the "see where to go before moving" step that
        // stops it aiming at spots inside walls or wandering into rooms.
        // Rescored ~once a second, on each stall nudge, or when the pig
        // moved since the last pick.
        if (herdAim == null || stallNudges != herdAimNudges
                || tick - herdAimAt > 25
                || pig.position().distanceToSqr(herdAimPigPos) > 4.0
                || p.position().distanceToSqr(herdAim) < 0.7) {
            herdAim = pickHerdAim(client, p, pig.position(),
                    Math.atan2(rz, rx), repelR, staging,
                    crossesCircle(p.position(), staging, pig.position(), repelR));
            herdAimNudges = stallNudges;
            herdAimAt = tick;
            herdAimPigPos = pig.position();
        }
        Vec3 navTarget = herdAim;

        Navigator.lookAt(p, Sense.center(pig));

        if ("punch".equals(cfg.herdStyle)
                && p.position().distanceTo(pig.position()) <= cfg.attackRange
                && tick - lastPunchTick >= cfg.punchCooldownTicks) {
            lastPunchTick = tick;
            client.gameMode.attack(p, pig);
            say(client, "punched pig toward goal");
        }

        nav.setTarget(navTarget, 0.4);
    }

    // fan of stand-spot candidates around the pig's repel circle, in
    // degrees off the ideal behind-direction - nearer the ideal wins ties
    private static final double[] HERD_OFFS = {
            0, 25, -25, 50, -50, 75, -75, 100, -100, 135, -135, 160, -160, 180
    };

    // Pick a stand spot by reachability: each candidate on the repel
    // circle gets an A* dry-run; score = angular error + route detour
    // ratio. A spot inside a wall gets no route; one reachable only by
    // leaving the area scores badly. Falls back to the ideal point when
    // nothing routes (old bump behavior).
    private Vec3 pickHerdAim(Minecraft client, LocalPlayer p, Vec3 pigPos,
                           double baseAng, double repelR, Vec3 ideal, boolean needArc) {
        if (!needArc) {
            // straight shot to the staging point wouldn't cross the repel
            // ring - use it when a route actually exists
            java.util.List<Vec3> direct = Pathfinder.find(client.level, p.position(), ideal);
            if (direct != null && !direct.isEmpty()) return ideal;
        }
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (double off : HERD_OFFS) {
            double a = baseAng + Math.toRadians(off);
            Vec3 cand = pigPos.add(Math.cos(a) * repelR, 0, Math.sin(a) * repelR);
            java.util.List<Vec3> route = Pathfinder.find(client.level, p.position(), cand);
            if (route == null || route.isEmpty()) continue;
            double score = Math.abs(off) * 0.03
                    + route.size() / Math.max(1.0, cand.distanceTo(p.position()));
            if (score < bestScore) { bestScore = score; best = cand; }
        }
        return best != null ? best : ideal;
    }

    // does the straight path a->b pass within r blocks of center (XZ)?
    private static boolean crossesCircle(Vec3 a, Vec3 b, Vec3 center, double r) {
        double abx = b.x - a.x, abz = b.z - a.z;
        double len2 = abx * abx + abz * abz;
        if (len2 < 1e-6) return false;
        double t = ((center.x - a.x) * abx + (center.z - a.z) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = a.x + abx * t - center.x;
        double cz = a.z + abz * t - center.z;
        return cx * cx + cz * cz < r * r;
    }

    private void tickOrb(Minecraft client) {
        LocalPlayer p = client.player;
        Vec3 anchor = orbAnchor != null ? orbAnchor : goalPos;
        if (anchor == null) {
            transition(client, S.COOLDOWN, "no orb anchor");
            return;
        }
        // the token line is the real success signal - a client-side
        // "click" on a display plate means nothing
        if (tokenReceived) {
            clearCtx();
            transition(client, S.COOLDOWN, "token received");
            return;
        }
        if (orbClicked) {
            if (tick >= orbConfirmUntil) {
                if (orbClicks >= 3) {
                    clearCtx();
                    transition(client, S.COOLDOWN, "orb clicks unconfirmed - giving up");
                } else {
                    orbClicked = false;
                    say(client, "no token line - clicking again");
                }
            }
            return;
        }
        if (tick - enteredAt > cfg.orbSearchTimeoutTicks + 600) {
            clearCtx();
            transition(client, S.COOLDOWN, "orb window expired");
            return;
        }

        Entity orb = Sense.findOrbEntity(client.level, anchor, cfg);
        if (orb == null) {
            if (tick - enteredAt > cfg.orbSearchTimeoutTicks) {
                say(client, "no orb entity found near goal");
                if (cfg.orbFallbackToBlock) {
                    clickGoalBlock(client, anchor);
                } else {
                    transition(client, S.COOLDOWN, "orb not found");
                }
            }
            return;
        }

        // stand under the floating SHINY ORB - it hovers ~head height, so
        // the interactable is best reached looking up from beneath it.
        // nav targets a GROUND-level point under it - aiming the nav at
        // the plate's own y would just make it jump
        Vec3 orbPos = Sense.center(orb);
        double dxz = Math.hypot(orbPos.x - p.getX(), orbPos.z - p.getZ());
        if (dxz > 1.4) {
            // orbs sit in hedge pits half the time - if the approach has
            // stalled a while, fire from wherever we managed to reach
            if (!(tick - enteredAt > 80 && dxz <= cfg.orbInteractRange + 1.5)) {
                nav.setTarget(new Vec3(orbPos.x, p.getY(), orbPos.z), 1.2);
                return;
            }
        }
        nav.cancel();
        Navigator.lookAt(p, orbPos);
        if (!Navigator.aimedAt(p, orbPos)) return; // land the look, click next tick
        fireOrbClick(client, orb, p, orbPos);
        orbClicked = true;
        orbConfirmUntil = tick + 70; // ~3.5s for the token line to arrive
    }

    // the "orb" entity is usually a floating text/display plate where
    // interact() is a silent no-op - so fire a bare right-click toward it
    // too and let the server ray-trace the real target, then sweep the
    // aim downward with follow-up clicks in case the clickable sits lower
    private void fireOrbClick(Minecraft client, Entity orb, LocalPlayer p, Vec3 at) {
        orbClicks++;
        client.gameMode.interact(p, orb, new EntityHitResult(orb), InteractionHand.MAIN_HAND);
        client.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        for (int k = 1; k <= 4; k++) {
            final int i = k;
            schedule(i * 3, () -> {
                Navigator.lookAt(p, at.add(0, -0.15 * i, 0));
                client.gameMode.interact(p, orb, new EntityHitResult(orb), InteractionHand.MAIN_HAND);
                client.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            });
        }
        say(client, "orb click burst #" + orbClicks + " @" + fmt(at));
    }

    private void clickGoalBlock(Minecraft client, Vec3 anchor) {
        nav.setTarget(Vec3.atCenterOf(BlockPos.containing(anchor)), 2.0);
        Navigator.lookAt(client.player, Vec3.atCenterOf(BlockPos.containing(anchor)));
        schedule(30, () -> {
            if (orbClicked || state != S.INTERACTING_GOAL) return;
            BlockPos bp = BlockPos.containing(anchor);
            Vec3 hit = Vec3.atCenterOf(bp);
            Navigator.lookAt(client.player, hit);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, bp, false));
            say(client, "activateBlock fallback @" + bp.toShortString());
            orbClicked = true;
            orbClicks++;
            orbConfirmUntil = tick + 70;
        });
    }

    // ---------- enderchest stash ----------

    private void tickStash(Minecraft client) {
        LocalPlayer p = client.player;
        if (p == null) { stashPhase = 0; return; }
        switch (stashPhase) {
            case 1 -> { // open the next ec page
                if (stashPage >= cfg.stash.pages.size()) {
                    stashPhase = 0;
                    say(client, "stash done");
                    return;
                }
                String cmd = cfg.stash.pages.get(stashPage).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                p.connection.sendCommand(cmd);
                say(client, "stash: /" + cmd);
                stashPhase = 2;
                stashPhaseUntil = tick + cfg.stash.guiWaitTicks;
            }
            case 2 -> { // wait for the enderchest container
                if (client.gui.screen() instanceof AbstractContainerScreen<?> hs) {
                    stashPhase = 3;
                    stashPhaseUntil = queueStashClicks(client, hs);
                } else if (tick >= stashPhaseUntil) {
                    say(client, "stash: no GUI opened - skipping page " + (stashPage + 1));
                    stashPhase = 4;
                    stashPhaseUntil = tick + cfg.stash.closeDelayTicks;
                }
            }
            case 3 -> { // shift-clicks still draining from the queue
                if (tick >= stashPhaseUntil) {
                    client.gui.setScreen(null); // closes the container properly
                    stashPhase = 4;
                    stashPhaseUntil = tick + cfg.stash.closeDelayTicks;
                }
            }
            case 4 -> { // settle, then next page
                if (tick >= stashPhaseUntil) {
                    stashPage++;
                    stashPhase = 1;
                }
            }
        }
    }

    // shift-click every player-inventory slot that isn't a keep-name.
    // returns the tick the click queue should be done by
    private int queueStashClicks(Minecraft client, AbstractContainerScreen<?> hs) {
        LocalPlayer p = client.player;
        var menu = hs.getMenu();
        int cid = menu.containerId;
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            var s = menu.slots.get(i);
            if (s.container != p.getInventory()) continue; // only OUR slots
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().toLowerCase();
            if (keepStashItem(name)) continue;
            targets.add(i);
        }
        if (targets.isEmpty()) {
            say(client, "stash: nothing to move on this page");
            return tick + 2;
        }
        int at = 0;
        for (int slot : targets) {
            at += cfg.stash.clickDelayTicks;
            schedule(at, () -> {
                try {
                    client.gameMode.handleContainerInput(cid, slot,
                            cfg.stash.button, ContainerInput.QUICK_MOVE, p);
                } catch (Exception e) {
                    say(client, "stash click failed: " + e.getMessage());
                }
            });
        }
        say(client, "stash: moving " + targets.size() + " stack(s)");
        return tick + at + cfg.clickJitterTicks + 8;
    }

    private boolean keepStashItem(String lowerName) {
        if (lowerName.contains(cfg.orbItemName.toLowerCase())) return true;
        for (String k : cfg.stash.keepNames) {
            if (!k.isEmpty() && lowerName.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    private boolean inventoryFull(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) { // main inventory + hotbar
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // /piglure stash - force a stash run now (test)
    public void forceStash(Minecraft c) {
        if (c.player == null) return;
        say(c, "stash test - inventory is "
                + (inventoryFull(c.player) ? "full" : "not full, running anyway"));
        stashPage = 0;
        stashPhase = 1;
    }

    // /piglure panic - simulate a keyword bail (test)
    public void testPanic(Minecraft c) {
        panicEscape(c, "panic test");
    }

    private void warnThrottled(Minecraft c, String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 10000) {
            lastWarnMs = now;
            say(c, "§c" + msg);
        }
    }

    private static String fmt(Vec3 v) {
        return String.format("%.0f,%.0f,%.0f", v.x, v.y, v.z);
    }
}
