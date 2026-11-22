package com.piglurer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// Steering-based mover: real client input (W + jump), real physics packets.
// Fine for flat-ish arenas; swap for Baritone if your map needs real pathfinding.
public class Navigator {
    private final Minecraft client;
    private Vec3 target;
    private double arriveDist = 0.8;
    private boolean active = false;
    public boolean sprint = true;

    private Vec3 lastPos;
    private int stuckTicks = 0;
    private double detourAngleDeg = 0;
    private int detourTicks = 0;

    public Navigator(Minecraft client) {
        this.client = client;
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
        if (!active || p == null || target == null) return;

        if (horizontal(p.position(), target) <= arriveDist) {
            releaseKeys();
            return;
        }

        // face the target (with the current detour offset) and hold W
        double yaw = yawTo(p.getEyePosition(), target) + detourAngleDeg;
        p.setYRot((float) yaw);
        p.setXRot(0f);
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

    public static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        p.setYRot((float) yawTo(eye, target));
        p.setXRot((float) pitchTo(eye, target));
    }
}
