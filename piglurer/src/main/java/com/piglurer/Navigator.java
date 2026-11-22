package com.piglurer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// Steering-based mover: real client input (W + jump), real physics packets.
// Turns are rate-limited so the camera sweeps instead of snapping.
public class Navigator {
    private final Minecraft client;
    private final PigLureConfig cfg;
    private Vec3 target;
    private double arriveDist = 0.8;
    private boolean active = false;
    public boolean sprint = true;

    // pending aim point set by lookAt() - applied gradually each tick
    private static Vec3 pendingLook;

    private double lastSteerDist = Double.MAX_VALUE;
    private int progressTicks = 0;
    private int blockedTicks = 0;
    private int nextHopIn = 30 + (int) (Math.random() * 40);
    private double detourAngleDeg = 0;
    private int detourTicks = 0;

    // A* path state - repathed on interval, on dest change, or on failure retry
    private java.util.List<Vec3> path;
    private int pathIdx;
    private int repathIn;

    public Navigator(Minecraft client, PigLureConfig cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    public void setTarget(Vec3 t, double dist) {
        boolean destChanged = target == null || t.distanceTo(target) > 2.5;
        this.target = t;
        this.arriveDist = dist;
        this.active = true;
        this.progressTicks = 0;
        this.blockedTicks = 0;
        this.lastSteerDist = Double.MAX_VALUE;
        this.detourAngleDeg = 0;
        if (destChanged || repathIn <= 0) repath();
    }

    private void repath() {
        repath(false);
    }

    private void repath(boolean force) {
        if (client.level == null || client.player == null || target == null) return;
        // short hops steer straight - a path is worse than useless up
        // close, unless we're wedged: a wall can sit one block away
        if (!force && horizontal(client.player.position(), target) < 4.0) {
            path = null;
            repathIn = 15;
            return;
        }
        java.util.List<Vec3> found = Pathfinder.find(
                client.level, client.player.position(), target);
        if (found != null && !found.isEmpty()) {
            path = found;
            pathIdx = 0;
            repathIn = 25;
        } else {
            path = null;
            repathIn = 60; // retry failed searches less often
        }
    }

    public void cancel() {
        this.active = false;
        this.path = null;
        releaseKeys();
    }

    public boolean isActive() {
        return active;
    }

    // true while an A* route is being followed - false means either
    // close-range straight steering or "couldn't find a way through"
    public boolean hasPath() {
        return path != null;
    }

    public double distTo(LocalPlayer p) {
        if (target == null) return Double.MAX_VALUE;
        return horizontal(p.position(), target);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // is the node behind the player's current facing? (dot with look vec)
    private static boolean behindPlayer(LocalPlayer p, Vec3 node) {
        Vec3 eye = p.getEyePosition();
        double yaw = Math.toRadians(p.getYRot());
        return (node.x - eye.x) * -Math.sin(yaw) + (node.z - eye.z) * Math.cos(yaw) < 0;
    }

    public void tick() {
        LocalPlayer p = client.player;
        if (p == null) return;
        applyLook(p);
        if (!active || target == null) return;
        if (--repathIn <= 0) repath();

        if (horizontal(p.position(), target) <= arriveDist) {
            releaseKeys();
            return;
        }

        // walk the computed path a node at a time; no path -> steer straight
        Vec3 pos = p.position();
        Vec3 steer = target;
        if (path != null) {
            // drop nodes we've reached or already walked past - steering
            // back to a passed node is what produced the spin-turns
            while (pathIdx < path.size()) {
                Vec3 n = path.get(pathIdx);
                double hd = horizontal(pos, n);
                if (hd < 0.7) {
                    pathIdx++;
                } else if (hd < 1.4 && behindPlayer(p, n) && pathIdx + 1 < path.size()) {
                    pathIdx++;
                } else {
                    break;
                }
            }
            if (pathIdx < path.size()) {
                // pure pursuit: aim ~1.8b ahead ALONG the path (accumulated
                // segment length, not straight-line - a lookahead measured
                // from the player can aim through a wall on tight bends)
                int look = pathIdx;
                Vec3 prev = path.get(look);
                double ahead = horizontal(pos, prev);
                while (look + 1 < path.size() && ahead < 1.8) {
                    look++;
                    ahead += horizontal(prev, path.get(look));
                    prev = path.get(look);
                }
                steer = path.get(look);
            }
        }

        // ease toward the travel direction at the tracking rate - walking
        // sweeps, doesn't flick
        double wantYaw = yawTo(p.getEyePosition(), steer) + detourAngleDeg;
        p.setYRot(stepAngle(p.getYRot(), (float) wantYaw, cfg.trackYawSpeedDeg));
        // only level the pitch while nothing's asking the head to look -
        // otherwise steering and applyLook fight every tick = head jitter
        if (pendingLook == null) {
            p.setXRot(stepAngle(p.getXRot(), 0f, cfg.trackPitchSpeedDeg));
        }
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(sprint);

        // blockedTicks counts collisions even while sliding along a wall
        // (position keeps changing so movement-based checks never catch
        // it) - hop when genuinely blocked a moment, swimming, or when
        // the steer point's up a ledge
        if (p.horizontalCollision) blockedTicks++;
        else blockedTicks = 0;
        boolean needJump = blockedTicks > 5
                || steer.y > p.getY() + 0.5
                || p.isInWater();
        // humans sprint-jump constantly on skyblock - a hop every ~1-3s
        // reads as a player AND pops over lips that never register as a
        // real collision (stair edges, parapets, odd half blocks)
        if (nextHopIn > 0) nextHopIn--;
        boolean hop = nextHopIn <= 0 && p.onGround();
        if (hop) nextHopIn = 20 + (int) (Math.random() * 40);
        client.options.keyJump.setDown(needJump || hop);

        // stuck = distance to the steer point isn't closing. Sliding
        // sideways along a wall still "moves", which is why this never
        // fired against the ledge while a wall sat one block away
        double dSteer = horizontal(pos, steer);
        if (dSteer < lastSteerDist - 0.02) {
            lastSteerDist = dSteer;
            progressTicks = 0;
        } else if (++progressTicks > 12) {
            repath(true); // force A* even up close - route around the wall
            if (path == null) {
                detourAngleDeg = (detourAngleDeg <= 0 ? 20 : -20);
                detourTicks = 0;
            }
            progressTicks = 0;
            lastSteerDist = dSteer;
        }
        if (detourAngleDeg != 0 && ++detourTicks > 25) {
            detourAngleDeg = 0;
            detourTicks = 0;
        }
    }

    // gradually turn toward pendingLook; while walking, steering owns yaw
    // and the look only tilts the head (pitch)
    private void applyLook(LocalPlayer p) {
        if (pendingLook == null) return;
        Vec3 eye = p.getEyePosition();
        float wantPitch = (float) pitchTo(eye, pendingLook);
        p.setXRot(stepAngle(p.getXRot(), wantPitch,
                active ? cfg.trackPitchSpeedDeg : cfg.lookPitchSpeedDeg));
        if (active) return;
        float wantYaw = (float) yawTo(eye, pendingLook);
        p.setYRot(stepAngle(p.getYRot(), wantYaw, cfg.lookYawSpeedDeg));
        if (Math.abs(Mth.wrapDegrees(wantYaw - p.getYRot())) < 2
                && Math.abs(wantPitch - p.getXRot()) < 2) {
            pendingLook = null;
        }
    }

    // move current toward want by at most maxStep degrees, wrapping angles
    private static float stepAngle(float cur, float want, float maxStep) {
        float diff = Mth.wrapDegrees(want - cur);
        if (diff > maxStep) diff = maxStep;
        else if (diff < -maxStep) diff = -maxStep;
        return cur + diff;
    }

    private void releaseKeys() {
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }

    // Minecraft yaw: -Z = 0, +X = -90
    public static double yawTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    public static double pitchTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        return -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    // true once the view is already within ~6 degrees of the target - use
    // to gate clicks so they fire after the head turn lands
    public static boolean aimedAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        return Math.abs(Mth.wrapDegrees(yawTo(eye, target) - p.getYRot())) < 6
                && Math.abs(pitchTo(eye, target) - p.getXRot()) < 6;
    }

    public static void lookAt(LocalPlayer p, Vec3 target) {
        pendingLook = target;
    }

    public static void clearLook() {
        pendingLook = null;
    }
}
