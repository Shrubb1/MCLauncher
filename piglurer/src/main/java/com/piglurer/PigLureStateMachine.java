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
    private int stallNudges;
    private int lastPunchTick;
    private int shopAttempts;
    private int shopBusyUntil;
    private boolean launchPending;
    private boolean orbClicked;
    private int orbCooldownUntil;
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
        delayed.removeIf(t -> {
            if (t[0] <= tick) {
                Runnable r = delayedTasks.remove(t[1]);
                if (r != null) r.run();
                return true;
            }
            return false;
        });
    }

    // ---------- buy-sequence recorder ----------
    // /piglure recordbuy captures your real clicks (block/entity right
    // clicks + GUI slot clicks) with the pauses between them; restock
    // replays the captured script instead of blind slot numbers

    private boolean recording;
    private boolean sawContainer;
    private int recordLastTick;
    private final java.util.List<PigLureConfig.BuyStep> pendingSteps = new java.util.ArrayList<>();

    public boolean isRecording() {
        return recording;
    }

    public void toggleRecording(Minecraft c) {
        if (recording) finishRecording(c); else startRecording(c);
    }

    private void startRecording(Minecraft c) {
        disable(c); // don't have the bot acting while you're demonstrating
        pendingSteps.clear();
        sawContainer = false;
        recordLastTick = tick;
        recording = true;
        say(c, "§arecording buy - perform the purchase; it saves when the GUI closes");
    }

    private void finishRecording(Minecraft c) {
        recording = false;
        if (pendingSteps.isEmpty()) {
            say(c, "recordbuy ended - nothing recorded");
            return;
        }
        cfg.shop.steps.clear();
        cfg.shop.steps.addAll(pendingSteps);
        pendingSteps.clear();
        cfg.save();
        say(c, "§abuy script saved - " + cfg.shop.steps.size() + " steps");
    }

    public void cancelRecording(Minecraft c, boolean wipeSaved) {
        recording = false;
        pendingSteps.clear();
        if (wipeSaved && !cfg.shop.steps.isEmpty()) {
            cfg.shop.steps.clear();
            cfg.save();
            say(c, "buy script cleared - falls back to nav/buySlot config");
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
        if (!recording) return;
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
        if (!recording) return;
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
        if (!recording || slot < 0) return;
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
        shopAttempts = 0;
        launchPending = false;
        orbClicked = false;
    }

    public void enable(Minecraft c) {
        clearCtx();
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
        if (nav == null) nav = new Navigator(client);
        if (recording) recordingTick(client);
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
        if (Sense.countOrbs(p.getInventory(), cfg.orbItemName) < cfg.restockThreshold) {
            transition(client, S.CHECK_ORBS, "orbs ran out");
            return;
        }
        if (tick - lastScanTick < cfg.scanIntervalTicks) return;
        lastScanTick = tick;

        String ourName = p.getName().getString();
        Pig found = Sense.findShinyPig(client.level, p.position(), cfg, ourName);
        if (found == null) return;

        clearCtx();
        pig = found;
        transition(client, S.APPROACHING_PIG,
                "pig @" + fmt(found.position()) + " dist=" + String.format("%.1f", found.position().distanceTo(p.position())));
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
        // buyCount (the bulk option) or a fixed slot
        int extra = 0;
        if (cfg.shop.navSlot != null) {
            schedule(cfg.shop.navDelayTicks, () -> clickNav(client));
            extra = Math.max(extra, cfg.shop.navDelayTicks);
        }
        if (cfg.shop.buyCount != null || cfg.shop.buySlot != null) {
            int base = (cfg.shop.navSlot != null ? cfg.shop.navDelayTicks : 0)
                    + cfg.shop.buyDelayTicks;
            int clicks = Math.max(1, cfg.shop.buyClicks);
            for (int i = 0; i < clicks; i++) {
                schedule(base + i * 6, () -> clickBuy(client));
            }
            extra = Math.max(extra, base + (clicks - 1) * 6);
        }
        scheduleVerify(client, extra);
    }

    private void activateShop(Minecraft client, Vec3 target) {
        LocalPlayer p = client.player;
        if (cfg.shop.click != null) {
            BlockPos bp = new BlockPos(cfg.shop.click[0], cfg.shop.click[1], cfg.shop.click[2]);
            Vec3 hit = Vec3.atCenterOf(bp);
            Navigator.lookAt(p, hit);
            client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, bp, false));
            say(client, "activateBlock @" + bp.toShortString());
        } else {
            Entity shopkeeper = Sense.nearestShopInteractable(client.level, target, cfg.shop.clickRadius);
            if (shopkeeper != null) {
                Navigator.lookAt(p, Sense.center(shopkeeper));
                client.gameMode.interact(p, shopkeeper, new EntityHitResult(shopkeeper), InteractionHand.MAIN_HAND);
                say(client, "activateEntity " + shopkeeper.getName().getString());
            } else {
                say(client, "no shop.click and no entity nearby — swinging");
                p.swing(InteractionHand.MAIN_HAND);
            }
        }
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
            at += cfg.shop.navDelayTicks;
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
        if (state != S.RESTOCKING) return;
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
                Navigator.lookAt(p, hit);
                client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, face, bp, false));
                say(client, "replay block click @" + bp.toShortString());
            }
            case "entity" -> {
                Entity e = findRecordedEntity(client, s);
                if (e != null) {
                    Navigator.lookAt(p, Sense.center(e));
                    client.gameMode.interact(p, e, new EntityHitResult(e), InteractionHand.MAIN_HAND);
                    say(client, "replay entity " + e.getName().getString());
                } else {
                    say(client, "§creplay: no " + s.entityType + " near recorded spot");
                }
            }
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
            clearCtx();
            transition(client, S.IDLE_SCAN, "approach timeout");
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
        Navigator.lookAt(p, Sense.center(pig));

        goalPos = pig.position(); // pig's position at right-click time = herd target
        // same as /piglure setgoal - persists it so mode=fixed and
        // later sessions use this spot too
        cfg.goal.pos = new double[] { pig.getX(), pig.getY(), pig.getZ() };
        PigLurerClient.config.save();
        client.gameMode.interact(p, pig, new EntityHitResult(pig), InteractionHand.MAIN_HAND);
        if (cfg.useInteractAt) {
            client.gameMode.interact(p, pig,
                    new EntityHitResult(pig, Sense.center(pig)), InteractionHand.MAIN_HAND);
        }
        say(client, "activated pig @" + fmt(pig.position()) + " goal=" + fmt(goalPos));

        launchPending = true;
        schedule(cfg.pigLaunchDelayTicks, () -> {
            launchPending = false;
            if (state != S.APPROACHING_PIG) return;
            if (pig == null || !pig.isAlive()) {
                orbAnchor = goalPos;
                transition(client, S.INTERACTING_GOAL, "pig despawned instantly");
                return;
            }
            stallRef = pig.position();
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

        Navigator.lookAt(p, Sense.center(pig));

        if ("punch".equals(cfg.herdStyle)
                && p.position().distanceTo(pig.position()) <= cfg.attackRange
                && tick - lastPunchTick >= cfg.punchCooldownTicks) {
            lastPunchTick = tick;
            client.gameMode.attack(p, pig);
            say(client, "punched pig toward goal");
        }

        nav.setTarget(staging, 0.4);
    }

    private void tickOrb(Minecraft client) {
        LocalPlayer p = client.player;
        Vec3 anchor = orbAnchor != null ? orbAnchor : goalPos;
        if (anchor == null) {
            transition(client, S.COOLDOWN, "no orb anchor");
            return;
        }
        if (orbClicked) {
            if (tick >= orbCooldownUntil) {
                clearCtx();
                transition(client, S.COOLDOWN, "orb clicked");
            }
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

        double dist = p.position().distanceTo(Sense.center(orb));
        if (dist > cfg.orbInteractRange) {
            nav.setTarget(Sense.center(orb), cfg.orbInteractRange - 0.5);
            return;
        }
        nav.cancel();
        Navigator.lookAt(p, Sense.center(orb));
        client.gameMode.interact(p, orb, new EntityHitResult(orb), InteractionHand.MAIN_HAND);
        if (cfg.useInteractAt) {
            client.gameMode.interact(p, orb,
                    new EntityHitResult(orb, Sense.center(orb)), InteractionHand.MAIN_HAND);
        }
        say(client, "activated orb " + orb.getName().getString() + " @" + fmt(orb.position()));
        orbClicked = true;
        orbCooldownUntil = tick + 8;
    }

    private void clickGoalBlock(Minecraft client, Vec3 anchor) {
        nav.setTarget(Vec3.atCenterOf(BlockPos.containing(anchor)), 2.0);
        schedule(30, () -> {
            if (orbClicked || state != S.INTERACTING_GOAL) return;
            BlockPos bp = BlockPos.containing(anchor);
            Vec3 hit = Vec3.atCenterOf(bp);
            Navigator.lookAt(client.player, hit);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, bp, false));
            say(client, "activateBlock fallback @" + bp.toShortString());
            orbClicked = true;
            orbCooldownUntil = tick + 8;
        });
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
