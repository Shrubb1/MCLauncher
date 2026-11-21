package com.mclauncher.pro.watch;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Social-awareness alerts, pure game data:
 *  - a tracked player whose view vector stays on you >= ~2s (with a
 *    collision raycast so walls block) shows "X is looking at you (Ns)"
 *  - any incoming chat line containing your IGN shows the line briefly
 * Both render top-center; each fires a soft ding when triggered.
 */
public final class Watcher {

    private static final int ALERT_TICKS = 40;          // ~2s of gaze
    private static final double COS_FOV = Math.cos(Math.toRadians(12.0));
    private static final double MAX_DIST = 48.0;
    private static final long MENTION_MS = 6000;

    private static final Map<String, Integer> watchers = new HashMap<>();
    private static String mentionMsg;
    private static long mentionAt;

    private Watcher() {
    }

    /** Per player tick while pro is enabled. */
    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            watchers.clear();
            return;
        }
        Vec3d me = mc.player.getEyePos();
        Set<String> seen = new HashSet<>();
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || !other.isAlive()) {
                continue;
            }
            Vec3d eye = other.getEyePos();
            double dist = eye.distanceTo(me);
            if (dist > MAX_DIST) {
                continue;
            }
            Vec3d toMe = me.subtract(eye).normalize();
            if (toMe.dotProduct(other.getRotationVector()) < COS_FOV
                    || !lineOfSight(mc, other, eye, me)) {
                continue;
            }
            String name = other.getName().getString();
            seen.add(name);
            int t = watchers.merge(name, 1, Integer::sum);
            if (t == ALERT_TICKS) {
                ding(1.2f);
            }
        }
        watchers.keySet().retainAll(seen);
    }

    /** A chat line arrived (local + server). */
    public static void onChat(String text) {
        ClientPlayerEntity me = MinecraftClient.getInstance().player;
        if (me == null) {
            return;
        }
        String name = me.getName().getString();
        String low = text.toLowerCase(Locale.ROOT);
        if (!low.contains(name.toLowerCase(Locale.ROOT))
                || text.startsWith("<" + name)) {   // my own message
            return;
        }
        mentionMsg = text.length() > 70 ? text.substring(0, 70) : text;
        mentionAt = System.currentTimeMillis();
        ding(1.6f);
    }

    /** Alert lines, top-center under the crosshair area. */
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        int y = 8;
        int cx = ctx.getScaledWindowWidth() / 2;
        for (var e : watchers.entrySet()) {
            if (e.getValue() < ALERT_TICKS) {
                continue;
            }
            String s = "\u00A7c" + e.getKey() + " is looking at you ("
                    + e.getValue() / 20 + "s)";
            ctx.drawCenteredTextWithShadow(mc.textRenderer, s, cx, y,
                    0xFFFF5555);
            y += 11;
        }
        if (mentionMsg != null) {
            if (System.currentTimeMillis() - mentionAt < MENTION_MS) {
                ctx.drawCenteredTextWithShadow(mc.textRenderer,
                        "\u00A7e" + mentionMsg, cx, y, 0xFFFFFF55);
            } else {
                mentionMsg = null;
            }
        }
    }

    private static boolean lineOfSight(MinecraftClient mc,
                                       PlayerEntity other,
                                       Vec3d eye, Vec3d me) {
        var ctx = new RaycastContext(eye, me,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, other);
        return mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }

    private static void ding(float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getSoundManager().play(PositionedSoundInstance.ui(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, pitch));
    }
}
