package com.mclauncher.pro.path;

import com.mclauncher.client.ClientMod;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "#mine <block>" - scans the loaded world for matching blocks and
 * harvests them: etherwarp (sneak + right-click ability) toward the
 * target when it's out of reach, swaps to a pickaxe found by name in
 * the hotbar or inventory, then aims and breaks the block. Nearby
 * matches are clustered so the cursor lands on different points of the
 * vein instead of the same pixel every time, and END_ROD markers show
 * exactly which blocks are queued.
 */
public final class Miner {

    private static final int SCAN_RADIUS = 32;
    private static final double REACH = 4.4;
    private static final long TARGET_MS = 12000;
    private static final long RESCAN_MS = 1500;
    private static final long WARP_CD_MS = 1500;

    private static String query;
    private static BlockPos target;
    private static List<BlockPos> cluster = List.of();
    private static double aimX, aimY, aimZ;
    private static final Set<BlockPos> failed = new HashSet<>();
    private static long rescanAt;
    private static long warpReadyAt;
    private static int warpAimTicks;
    private static long deadline;
    private static boolean warping;
    private static int warpTicks;
    private static int mineTicks;
    private static boolean screenPaused;
    private static int markerTicks;
    /** last tick's active() + blocks broken - dashboard task logging */
    private static boolean wasActive;
    private static int broken;

    private Miner() {
    }

    public static boolean active() {
        return query != null;
    }

    public static void start(String ore) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        query = ore.toLowerCase().replace(' ', '_');
        if (query.contains(":")) {
            query = query.substring(query.indexOf(':') + 1);
        }
        failed.clear();
        target = null;
        cluster = List.of();
        rescanAt = 0;
        warpReadyAt = 0;
        warpAimTicks = 0;
        screenPaused = false;
        nav(true);
        say("§7[mine] mining " + query + " - Right Shift to stop");
    }

    public static void cancel(String why) {
        if (query == null) {
            return;
        }
        query = null;
        target = null;
        nav(false);
        screenPaused = false;
        release(MinecraftClient.getInstance());
        say(why);
    }

    /** Called every player tick while the pro tier is enabled. */
    public static void tick(MinecraftClient mc) {
        boolean a = query != null;
        if (a != wasActive) {
            if (a) {
                broken = 0;
                ClientMod.stats.begin("mine", query);
            } else {
                ClientMod.stats.end(broken);
            }
            wasActive = a;
        }
        if (query == null || mc.player == null || mc.world == null) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (mc.currentScreen != null) {
            if (!screenPaused) {
                screenPaused = true;
                say("§7[mine] paused - Right Shift to stop");
            }
            release(mc);
            return;
        }
        if (screenPaused) {
            screenPaused = false;
            say("§7[mine] resumed");
        }

        // ride out the etherwarp press - sneak+use needs a few ticks
        // held for the ability to register
        if (warping) {
            if (++warpTicks >= 4) {
                mc.options.useKey.setPressed(false);
                mc.options.sneakKey.setPressed(false);
                warping = false;
            }
            return;
        }

        if (target != null
                && !matches(mc.world.getBlockState(target))) {
            target = null; // mined
            broken++;
        }
        long now = System.currentTimeMillis();
        if (target == null) {
            if (now < rescanAt) {
                return;
            }
            pickTarget(mc);
            if (target == null) {
                rescanAt = now + RESCAN_MS;
                return;
            }
        }
        if (now > deadline) {
            failed.add(target);
            say("§c[mine] unreachable - skipping this spot");
            target = null;
            rescanAt = now;
            return;
        }

        if (++markerTicks % 8 == 0) {
            mark(mc);
        }

        double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        double dx = aimX - ex, dy = aimY - ey, dz = aimZ - ez;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH) {
            warpStep(mc, p, now);
        } else {
            mineStep(mc, p);
        }
    }

    // -- etherwarp approach -------------------------------------------

    /** Aim at a standable spot next to the ore for a couple ticks so
     * the look packet lands, then sneak+right-click to etherwarp. */
    private static void warpStep(MinecraftClient mc,
                                 ClientPlayerEntity p, long now) {
        BlockPos land = landingSpot(mc);
        aimAt(p, land.getX() + 0.5, land.getY() + 1.0,
                land.getZ() + 0.5, 45f);
        if (now < warpReadyAt) {
            return;
        }
        if (++warpAimTicks < 3) {
            return; // let the look settle before firing the ability
        }
        warpAimTicks = 0;
        if (!selectItem(mc, Miner::isWarpItem)) {
            say("§c[mine] no etherwarp item found - put one in the "
                    + "hotbar or walk closer");
            warpReadyAt = now + 4000;
            return;
        }
        mc.options.sneakKey.setPressed(true);
        mc.options.useKey.setPressed(true);
        warping = true;
        warpTicks = 0;
        warpReadyAt = now + WARP_CD_MS;
    }

    /** The block to look at for the warp: a solid neighbour of the
     * target whose top is open, so we land beside the ore rather than
     * on top of it (where it couldn't be seen to be mined). */
    private static BlockPos landingSpot(MinecraftClient mc) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            BlockPos b = target.add(d[0], 0, d[1]);
            BlockPos pos = new BlockPos(b.getX(), b.getY(), b.getZ());
            if (!mc.world.getBlockState(pos)
                    .getCollisionShape(mc.world, pos).isEmpty()
                    && open(mc, pos.up()) && open(mc, pos.up(2))) {
                return pos;
            }
        }
        return target;
    }

    private static boolean open(MinecraftClient mc, BlockPos pos) {
        return mc.world.getBlockState(pos)
                .getCollisionShape(mc.world, pos).isEmpty()
                && mc.world.getFluidState(pos).isEmpty();
    }

    // -- mining --------------------------------------------------------

    private static void mineStep(MinecraftClient mc,
                                 ClientPlayerEntity p) {
        if (!selectItem(mc, Miner::isPickaxe)) {
            cancel("§c[mine] no pickaxe in inventory - stopped");
            return;
        }
        aimAt(p, aimX, aimY, aimZ, 35f);
        // punch once to start + periodically for the swing anim, and
        // hold-break progress every tick - same calls vanilla input
        // makes when holding attack on a block
        if (++mineTicks % 15 == 1) {
            mc.interactionManager.attackBlock(target, Direction.UP);
        } else {
            mc.interactionManager.updateBlockBreakingProgress(
                    target, Direction.UP);
        }
    }

    // -- inventory -----------------------------------------------------

    /** Find an item matching {@code m}: select it if it's on the
     * hotbar, swap it into the selected slot if it's in the main
     * inventory. */
    private static boolean selectItem(MinecraftClient mc,
                                      Predicate<ItemStack> m) {
        ClientPlayerEntity p = mc.player;
        int sel = p.getInventory().getSelectedSlot();
        if (m.test(p.getInventory().getStack(sel))) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (m.test(p.getInventory().getStack(i))) {
                p.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (m.test(p.getInventory().getStack(i))) {
                // screen slot index == storage index for main inventory;
                // SWAP's button is the hotbar slot to trade with
                mc.interactionManager.clickSlot(
                        p.playerScreenHandler.syncId, i, sel,
                        SlotActionType.SWAP, p);
                return true;
            }
        }
        return false;
    }

    private static boolean isPickaxe(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        String id = Registries.ITEM.getId(s.getItem()).getPath();
        String name = s.getName().getString().toLowerCase();
        return id.contains("pickaxe") || id.contains("drill")
                || name.contains("pickaxe") || name.contains("drill");
    }

    private static boolean isWarpItem(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        String name = s.getName().getString().toLowerCase();
        return name.contains("aspect of the") || name.contains("ether");
    }

    // -- targeting ------------------------------------------------------

    /** Nearest matching unscanned block within SCAN_RADIUS, then group
     * the vein it belongs to and pick an aim point inside it. */
    private static void pickTarget(MinecraftClient mc) {
        BlockPos feet = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int r = SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) {
                        continue;
                    }
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (failed.contains(pos)
                            || !matches(mc.world.getBlockState(pos))) {
                        continue;
                    }
                    double d = pos.getSquaredDistance(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = pos;
                    }
                }
            }
        }
        if (best == null) {
            say("§7[mine] no " + query + " within " + r
                    + " blocks - keep looking");
            return;
        }
        target = best;
        cluster = vein(mc, best);
        // aim point inside the cluster, not pinned to one pixel
        aimX = best.getX() + 0.5 + (Math.random() - 0.5) * 0.6;
        aimY = best.getY() + 0.5 + (Math.random() - 0.5) * 0.6;
        aimZ = best.getZ() + 0.5 + (Math.random() - 0.5) * 0.6;
        deadline = System.currentTimeMillis() + TARGET_MS;
        mineTicks = 0;
    }

    /** Flood-fill the matching blocks touching {@code seed} - the vein
     * gets highlighted and mined as a group. */
    private static List<BlockPos> vein(MinecraftClient mc,
                                       BlockPos seed) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        List<BlockPos> out = new ArrayList<>();
        q.add(seed);
        while (!q.isEmpty() && out.size() < 32) {
            BlockPos b = q.poll();
            if (!seen.add(b) || !matches(mc.world.getBlockState(b))) {
                continue;
            }
            out.add(b);
            for (Direction d : Direction.values()) {
                q.add(b.offset(d));
            }
        }
        return out;
    }

    private static boolean matches(BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (query.contains("_")) {
            // explicit id like diamond_ore - covers the deepslate
            // variant too since it contains the same substring
            return path.contains(query);
        }
        // bare name like iron: the ore forms, not iron_bars/iron_door
        return path.endsWith(query + "_ore") || path.equals(query);
    }

    /** Visual queue: END_ROD on each vein block, FLAME on the exact
     * aim point - client-side only, no packets. */
    private static void mark(MinecraftClient mc) {
        int shown = 0;
        for (BlockPos b : cluster) {
            if (shown++ >= 10) {
                break;
            }
            mc.world.addParticleClient(ParticleTypes.END_ROD,
                    b.getX() + 0.5, b.getY() + 0.6, b.getZ() + 0.5,
                    0, 0.01, 0);
        }
        mc.world.addParticleClient(ParticleTypes.FLAME,
                aimX, aimY + 0.3, aimZ, 0, 0.01, 0);
    }

    // -- shared bits ----------------------------------------------------

    private static void aimAt(ClientPlayerEntity p, double x, double y,
                              double z, float rate) {
        double dx = x - p.getX(), dy = y - p.getEyeY(), dz = z - p.getZ();
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx))
                - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy,
                Math.max(0.2, Math.hypot(dx, dz))));
        p.setYaw(turn(p.getYaw(), wantYaw, rate));
        p.setPitch(turn(p.getPitch(),
                Math.max(-90f, Math.min(90f, wantPitch)), rate));
    }

    private static float turn(float cur, float want, float maxTurn) {
        float diff = want - cur;
        diff %= 360f;
        if (diff > 180f) {
            diff -= 360f;
        } else if (diff < -180f) {
            diff += 360f;
        }
        if (Math.abs(diff) <= maxTurn) {
            return want;
        }
        return cur + Math.signum(diff) * maxTurn;
    }

    private static void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.useKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    private static void nav(boolean on) {
        if (ClientMod.state != null) {
            ClientMod.state.navActive = on;
        }
    }

    private static void say(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) {
            p.sendMessage(Text.literal(s), false);
        }
    }
}
