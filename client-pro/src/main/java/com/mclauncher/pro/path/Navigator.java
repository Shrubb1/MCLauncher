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
    /** horizontal distance walked since the last plan - periodic
     * re-optimization trigger */
    private static double sincePlan;
    private static double prevX, prevZ;
    /** positions where we got stuck - fed back into repaths so the
     * next plan pays extra to route near them */
    private static final List<BlockPos> stuckSpots = new ArrayList<>();
    /** when path == null but goal != null: retry planning at this time */
    private static long retryAt;
    /** goal distance at the last waypoint - detects fruitless marches */
    private static double lastGoalDist;
    /** a screen is currently holding the nav in pause */
    private static boolean screenPaused;

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
        nav(true);
        screenPaused = false;
        repaths = 0;
        nodesDone = 0;
        retryAt = 0;
        sincePlan = 0;
        lastGoalDist = Double.MAX_VALUE;
        stuckSpots.clear();
        plan(mc, true);
    }

    public static void cancel(String why) {
        if (goal == null) {
            return;
        }
        goal = null;
        nav(false);
        screenPaused = false;
        path = null;
        release(MinecraftClient.getInstance());
        say(why);
    }

    private static void plan(MinecraftClient mc, boolean first) {
        plan(mc, first, false);
    }

    private static void plan(MinecraftClient mc, boolean first,
                             boolean quiet) {
        BlockPos feet = mc.player.getBlockPos();
        List<BlockPos> found =
                new Pathfinder(mc.world).find(feet, goal, stuckSpots);
        if (found == null) {
            path = null;
            release(mc);
            // truly boxed in? flood-fill the reachable region through
            // the same movement edges - a small pocket means there is
            // no route out and retrying forever is pointless
            if (new Pathfinder(mc.world).enclosed(feet, 500)) {
                goal = null;
                nav(false);
                say("§c[path] enclosed - no way out, stopping");
                return;
            }
            retryAt = System.currentTimeMillis() + 4000;
            say("§c[path] can't find a way forward - retrying");
            return;
        }
        path = found;
        idx = 0;
        stillTicks = 0;
        noGain = 0;
        sincePlan = 0;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
        prevX = mc.player.getX();
        prevZ = mc.player.getZ();
        bestHd = Double.MAX_VALUE;
        if (quiet) {
            return;
        }
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
        // any open screen (chat, inventory, pause) = pause: hands off
        // the keys until it closes, then pick up where we left off
        if (mc.currentScreen != null) {
            if (!screenPaused) {
                screenPaused = true;
                say("§7[path] paused - Right Shift to stop");
            }
            release(mc);
            return;
        }
        if (screenPaused) {
            screenPaused = false;
            say("§7[path] resumed");
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
        if (hd < 1.8 && idx + 1 < path.size()) {
            BlockPos n = path.get(idx + 1);
            if (Math.abs(n.getY() - t.getY()) <= 1) {
                sx = n.getX() + 0.5;
                sz = n.getZ() + 0.5;
                sdy = n.getY() - p.getY();
            }
        }
        double sdx = sx - p.getX(), sdz = sz - p.getZ();
        double shd = Math.hypot(sdx, sdz);

        // bearing to the steer target relative to facing - the key
        // decomposition uses this, so rear targets can be reached by
        // back+strafe instead of a pirouette
        double bearing = wrap((float) (Math.toDegrees(
                Math.atan2(sdz, sdx)) - 90.0 - p.getYaw()));

        // steer yaw toward the target at an error-scaled rate - but a
        // target nearly underfoot has no stable bearing (atan2 of ~0
        // jitters into a random direction every tick), and one directly
        // behind within walking range is closer to shuffle back to than
        // to spin for. Hold yaw in both cases; the keys do the work
        if (shd > 0.3 && !(Math.abs(bearing) > 100 && shd < 4.0)) {
            float want = (float) (Math.toDegrees(Math.atan2(sdz, sdx))
                    - 90.0);
            p.setYaw(approach(p.getYaw(), want, 25f));
        }
        double err = Math.toRadians(bearing);
        double fwd = Math.cos(err), strafe = Math.sin(err);
        mc.options.forwardKey.setPressed(fwd > 0.2);
        mc.options.backKey.setPressed(fwd < -0.2);
        mc.options.rightKey.setPressed(strafe > 0.2);   // D
        mc.options.leftKey.setPressed(strafe < -0.2);   // A

        boolean onClimbable = mc.world.getBlockState(p.getBlockPos())
                .isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE);
        boolean inWater = p.isTouchingWater();
        boolean lowAir = p.getAir() < 120;

        // in water, vertical motion follows the camera - pitch toward
        // the target's depth to dive or surface deliberately. Low air
        // overrides: look up and get to the surface. Pitch changes are
        // rate-limited like yaw - while bobbing at a bank the target's
        // sdy oscillates every cycle and instant pitch-set nods the
        // head. On land ease back to level rather than snapping
        if (inWater) {
            float wantPitch = lowAir ? -80f
                    : (float) -Math.toDegrees(Math.atan2(sdy,
                            Math.max(0.5, Math.hypot(sdx, sdz))));
            wantPitch = Math.max(-60f, Math.min(60f, wantPitch));
            p.setPitch(approach(p.getPitch(), wantPitch, 35f));
        } else {
            p.setPitch(p.getPitch() * 0.6f);
        }

        // sprint: underwater it engages the fast swim pose even at
        // gentler headings; on land use hysteresis so the flag doesn't
        // flicker when the heading hovers near the cutoff
        boolean sprint = p.isSprinting();
        if ((inWater && fwd > 0.15) || fwd > 0.45) {
            sprint = true;
        } else if (fwd < 0.25) {
            sprint = false;
        }
        p.setSprinting(sprint);

        // jump for step-ups and walls, and bhop on straights - but only
        // when roughly on-heading, so turns don't hop sideways into
        // walls. Never on a ladder/vine - jumping dismounts it
        if (fwd > 0.5 && p.isOnGround() && !onClimbable && !inWater
                && ((dy > 0.4 && hd < 2.0)
                    || (p.horizontalCollision && hd < 1.4)
                    || (hd > 1.5 && Math.abs(err) < 0.45))) {
            p.jump();
        }

        // swim control: hold jump to rise - bobbing out onto a bank is
        // the same input, and forced ascent when air runs out.
        // getAir is in ticks; <120 = ~4s of air left
        mc.options.jumpKey.setPressed(inWater && (sdy > -0.5 || lowAir));

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

        // periodic re-optimization: every ~15 blocks traveled, quietly
        // re-plan from the new position - a shorter/cleaner route may
        // open up as the vantage changes. Grounded only - mid-air feet
        // positions aren't valid A* starts. A* is a few ms at these
        // budgets, this costs nothing.
        sincePlan += Math.hypot(p.getX() - prevX, p.getZ() - prevZ);
        prevX = p.getX();
        prevZ = p.getZ();
        if (sincePlan > 15 && p.isOnGround()
                && idx < path.size() - 2) {
            plan(mc, false, true);
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
        // after several failed repaths, check whether we're actually
        // enclosed - a partial path inside a box would otherwise make
        // us march in place forever without ever reporting it
        if (repaths > 3
                && new Pathfinder(mc.world)
                        .enclosed(mc.player.getBlockPos(), 500)) {
            goal = null;
            nav(false);
            say("§c[path] enclosed - no way out, stopping");
            return;
        }
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
        double hdGoal = Math.hypot(dx, dz);
        // end of a partial (waypoint) path, not the goal itself:
        // replan immediately and keep marching - but if the march
        // didn't actually get us closer, blacklist the endpoint so
        // the next plan explores a different frontier instead of
        // ping-ponging between the same two vantage points
        if (hdGoal > 2.5) {
            if (lastGoalDist - hdGoal < 1.0) {
                stuckSpots.add(mc.player.getBlockPos());
                if (stuckSpots.size() > 8) {
                    stuckSpots.remove(0);
                }
            }
            lastGoalDist = hdGoal;
            say("§7[path] waypoint reached - replanning");
            plan(mc, false);
            return;
        }
        release(mc);
        say("§7[path] arrived at " + shortPos(goal));
        goal = null;
        nav(false);
    }

    /** Tells the free mod a bot task owns the controls, so Right Shift
     * stops the task instead of opening the config screen. */
    private static void nav(boolean on) {
        if (ClientMod.state != null) {
            ClientMod.state.navActive = on;
        }
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
