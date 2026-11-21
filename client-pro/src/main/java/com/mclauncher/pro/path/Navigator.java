package com.mclauncher.pro.path;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a Pathfinder-produced route: re-anchors every client tick,
 * steers yaw toward the next node, presses forward/jump via the vanilla
 * key state, tracks approach progress and repaths (with a memory of
 * where it last failed) when stuck, off-route, or circling.
 */
public final class Navigator {

    private static List<BlockPos> path;
    private static int idx;
    private static BlockPos goal;
    private static int repaths;
    private static double lastX, lastZ;
    private static int stillTicks;
    /** best distance so far to the current node - if it stops
     * improving we're circling/being pushed, not walking */
    private static double bestHd;
    private static int noGain;
    private static int nodesDone;
    /** positions where we got stuck - fed back into repaths so the
     * next plan pays extra to route near them */
    private static final List<BlockPos> stuckSpots = new ArrayList<>();
    /** when path == null but goal != null: retry planning at this time */
    private static long retryAt;

    private Navigator() {
    }

    public static boolean active() {
        return goal != null;
    }

    public static void start(BlockPos target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        goal = target;
        repaths = 0;
        nodesDone = 0;
        retryAt = 0;
        stuckSpots.clear();
        plan(mc, true);
    }

    public static void cancel(String why) {
        if (goal == null) {
            return;
        }
        goal = null;
        path = null;
        release(MinecraftClient.getInstance());
        say(why);
    }

    private static void plan(MinecraftClient mc, boolean first) {
        BlockPos feet = mc.player.getBlockPos();
        List<BlockPos> found =
                new Pathfinder(mc.world).find(feet, goal, stuckSpots);
        if (found == null) {
            path = null;
            release(mc);
            retryAt = System.currentTimeMillis() + 4000;
            say("§c[path] can't find a way forward - retrying");
            return;
        }
        path = found;
        idx = 0;
        stillTicks = 0;
        noGain = 0;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
        bestHd = Double.MAX_VALUE;
        if (first) {
            say("§7[path] heading to " + shortPos(goal)
                    + " - " + found.size() + " nodes");
        } else {
            say("§7[path] repathed (" + found.size() + " nodes)");
        }
    }

    /** Called every player tick while the pro tier is enabled. */
    public static void tick(MinecraftClient mc) {
        if (goal == null || mc.player == null || mc.world == null) {
            return;
        }
        if (path == null) {
            // between attempts: wait for the backoff, then replan
            if (System.currentTimeMillis() >= retryAt) {
                plan(mc, false);
            }
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (idx >= path.size()) {
            finish(mc);
            return;
        }
        BlockPos t = path.get(idx);
        double tx = t.getX() + 0.5, tz = t.getZ() + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double hd = Math.hypot(dx, dz);
        double dy = t.getY() - p.getY();

        if (hd < 0.35 && dy > -1.6 && dy < 1.6) {
            advance();
            if (idx >= path.size()) {
                finish(mc);
                return;
            }
            t = path.get(idx);
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            dx = tx - p.getX();
            dz = tz - p.getZ();
            hd = Math.hypot(dx, dz);
            dy = t.getY() - p.getY();
        }

        // overshot a node? adopt a nearer later node instead of
        // walking back
        while (idx + 1 < path.size()) {
            BlockPos n = path.get(idx + 1);
            double nd = Math.hypot(n.getX() + 0.5 - p.getX(),
                    n.getZ() + 0.5 - p.getZ());
            double ndy = n.getY() - p.getY();
            if (nd < hd - 0.2 && ndy > -1.6 && ndy < 1.6) {
                advance();
                t = n;
                tx = t.getX() + 0.5;
                tz = t.getZ() + 0.5;
                dx = tx - p.getX();
                dz = tz - p.getZ();
                hd = nd;
                dy = ndy;
            } else {
                break;
            }
        }

        // steering target: the current node, or the next one when we
        // are close - turning early arcs corners instead of pivoting
        // in place on node centers
        double sx = tx, sz = tz, sdy = dy;
        if (hd < 1.4 && idx + 1 < path.size()) {
            BlockPos n = path.get(idx + 1);
            if (Math.abs(n.getY() - t.getY()) <= 1) {
                sx = n.getX() + 0.5;
                sz = n.getZ() + 0.5;
                sdy = n.getY() - p.getY();
            }
        }
        double sdx = sx - p.getX(), sdz = sz - p.getZ();

        // steer yaw toward the steer target at an error-scaled rate,
        // then decompose motion into forward + strafe keys relative to
        // facing error so we keep moving while turning
        float want = (float) (Math.toDegrees(Math.atan2(sdz, sdx)) - 90.0);
        p.setYaw(approach(p.getYaw(), want, 25f));
        double err = Math.toRadians(wrap(want - p.getYaw()));
        double fwd = Math.cos(err), strafe = Math.sin(err);
        mc.options.forwardKey.setPressed(fwd > 0.2);
        mc.options.backKey.setPressed(fwd < -0.2);
        mc.options.rightKey.setPressed(strafe > 0.2);   // D
        mc.options.leftKey.setPressed(strafe < -0.2);   // A
        p.setSprinting(fwd > 0.3);

        // jump for step-ups and walls, and bhop on straights - moving
        // toward a distant node on the ground covers ground faster
        if (fwd > 0.5 && p.isOnGround()
                && ((dy > 0.4 && hd < 2.0)
                    || (p.horizontalCollision && hd < 1.4)
                    || hd > 1.5)) {
            p.jump();
        }

        // progress toward the node, not just raw movement - circling
        // or being pushed around counts as stuck. Skip while airborne:
        // a long fall makes no horizontal gain but is doing fine
        if (!p.isOnGround()) {
            noGain = 0;
        } else if (hd < bestHd - 0.05) {
            bestHd = hd;
            noGain = 0;
        } else {
            noGain++;
        }

        // off-route recovery: fell BELOW the node's y-plane or got
        // knocked/thrown far off it - replan now rather than grind.
        // (node below us = descending a planned drop, not off-route)
        if (dy > 2.6 || hd > 7.0 || noGain > 45) {
            stuck(mc);
            return;
        }

        // still-in-place fallback for slow drift under the gain floor
        if (++stillTicks % 50 == 0) {
            if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.6) {
                stuck(mc);
                return;
            }
            lastX = p.getX();
            lastZ = p.getZ();
        }
    }

    private static void advance() {
        idx++;
        // real progress pays back repath budget so long routes with
        // several bad spots don't exhaust retries on the first one
        if (++nodesDone % 10 == 0 && repaths > 0) {
            repaths--;
        }
    }

    private static void stuck(MinecraftClient mc) {
        stuckSpots.add(mc.player.getBlockPos());
        if (stuckSpots.size() > 8) {
            stuckSpots.remove(0);
        }
        repaths++;
        path = null;
        release(mc);
        // never give up - escalating backoff so repeated failures at
        // the same spot don't spin the pathfinder every tick
        retryAt = System.currentTimeMillis()
                + Math.min(5000, 500 + repaths * 500L);
        say("§c[path] stuck - retrying (attempt " + repaths + ")");
    }

    private static void finish(MinecraftClient mc) {
        path = null;
        double dx = goal.getX() + 0.5 - mc.player.getX();
        double dz = goal.getZ() + 0.5 - mc.player.getZ();
        // end of a partial (waypoint) path, not the goal itself:
        // replan immediately and keep marching
        if (Math.hypot(dx, dz) > 2.5) {
            say("§7[path] waypoint reached - replanning");
            plan(mc, false);
            return;
        }
        release(mc);
        say("§7[path] arrived at " + shortPos(goal));
        goal = null;
    }

    private static void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    private static void say(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) {
            p.sendMessage(Text.literal(s), false);
        }
    }

    private static String shortPos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Turn rate scales with error: big turns snap, small corrections
     * ease in - smooths the constant hard-rate spinning. */
    private static float approach(float cur, float want, float maxTurn) {
        float diff = wrap(want - cur);
        float rate = Math.min(maxTurn, 6f + Math.abs(diff) * 0.4f);
        if (Math.abs(diff) <= rate) {
            return want;
        }
        return cur + Math.signum(diff) * rate;
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg > 180f) {
            deg -= 360f;
        } else if (deg < -180f) {
            deg += 360f;
        }
        return deg;
    }
}
