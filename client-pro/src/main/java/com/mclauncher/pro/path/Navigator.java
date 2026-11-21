package com.mclauncher.pro.path;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Walks a Pathfinder-produced route: re-anchors every client tick,
 * steers yaw toward the next node, presses forward/jump via the vanilla
 * key state, detects no-progress and repaths (twice, then gives up).
 */
public final class Navigator {

    private static List<BlockPos> path;
    private static int idx;
    private static BlockPos goal;
    private static int repaths;
    private static double lastX, lastZ;
    private static int stillTicks;

    private Navigator() {
    }

    public static boolean active() {
        return path != null;
    }

    public static void start(BlockPos target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        goal = target;
        repaths = 0;
        plan(mc, true);
    }

    public static void cancel(String why) {
        if (path == null) {
            return;
        }
        path = null;
        release(MinecraftClient.getInstance());
        say(why);
    }

    private static void plan(MinecraftClient mc, boolean first) {
        BlockPos feet = mc.player.getBlockPos();
        List<BlockPos> found = new Pathfinder(mc.world).find(feet, goal);
        if (found == null) {
            path = null;
            release(mc);
            say("§c[path] no route to " + shortPos(goal));
            return;
        }
        path = found;
        idx = 0;
        stillTicks = 0;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
        if (first) {
            say("§7[path] heading to " + shortPos(goal)
                    + " - " + found.size() + " nodes");
        } else {
            say("§7[path] repathed (" + found.size() + " nodes)");
        }
    }

    /** Called every player tick while the pro tier is enabled. */
    public static void tick(MinecraftClient mc) {
        if (path == null || mc.player == null || mc.world == null) {
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
            idx++;
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

        // steer toward the node; move when roughly facing it
        float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYaw(approach(p.getYaw(), want, 25f));
        boolean aligned = Math.abs(wrap(want - p.getYaw())) < 50;
        mc.options.forwardKey.setPressed(aligned);
        p.setSprinting(false);

        // jump when the node is above us or we're nose-into a wall
        if (aligned && p.isOnGround()
                && ((dy > 0.4 && hd < 2.0)
                    || (p.horizontalCollision && hd < 1.4))) {
            p.jump();
        }

        // stuck detection: no horizontal progress for ~2.5s -> repath
        if (++stillTicks % 50 == 0) {
            if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.6) {
                if (++repaths > 2) {
                    cancel("§c[path] stuck - giving up");
                    return;
                }
                plan(mc, false);
                return;
            }
            lastX = p.getX();
            lastZ = p.getZ();
        }
    }

    private static void finish(MinecraftClient mc) {
        path = null;
        release(mc);
        say("§7[path] arrived at " + shortPos(goal));
    }

    private static void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
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

    private static float approach(float cur, float want, float maxTurn) {
        float diff = wrap(want - cur);
        if (Math.abs(diff) <= maxTurn) {
            return want;
        }
        return cur + Math.signum(diff) * maxTurn;
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
