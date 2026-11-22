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

    private Vec3 lastPos;
    private int stuckTicks = 0;
    private double detourAngleDeg = 0;
    private int detourTicks = 0;

    public Navigator(Minecraft client, PigLureConfig cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    public void setTarget(Vec3 t, double dist) {
        this.target = t;
        this.arriveDist = dist;
        this.active = true;
        this.stuckTicks = 0;
        this.detourAngleDeg = 0;
    }

    public void cancel() {
        this.active = false;
        releaseKeys();
    }

    public boolean isActive() {
        return active;
    }

    public double distTo(LocalPlayer p) {
        if (target == null) return Double.MAX_VALUE;
        return horizontal(p.position(), target);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public void tick() {
        LocalPlayer p = client.player;
        if (p == null) return;
        applyLook(p);
        if (!active || target == null) return;

        if (horizontal(p.position(), target) <= arriveDist) {
            releaseKeys();
            return;
        }

        // ease toward the travel direction instead of snapping the view
        double wantYaw = yawTo(p.getEyePosition(), target) + detourAngleDeg;
        p.setYRot(stepAngle(p.getYRot(), (float) wantYaw, cfg.lookYawSpeedDeg));
        p.setXRot(stepAngle(p.getXRot(), 0f, cfg.lookPitchSpeedDeg));
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(sprint);

        boolean needJump = p.horizontalCollision
                || target.y > p.getY() + 0.5
                || p.isInWater();
        client.options.keyJump.setDown(needJump);

        // stuck detection -> detour sideways for a while, then straighten out
        Vec3 pos = p.position();
        if (lastPos == null || pos.distanceToSqr(lastPos) > 0.01) {
            stuckTicks = 0;
            lastPos = pos;
        } else if (++stuckTicks > 30) {
            detourAngleDeg = (detourAngleDeg <= 0 ? 45 : -60);
            detourTicks = 0;
            stuckTicks = 0;
        }
        if (detourAngleDeg != 0 && ++detourTicks > 50) {
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
        p.setXRot(stepAngle(p.getXRot(), wantPitch, cfg.lookPitchSpeedDeg));
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
