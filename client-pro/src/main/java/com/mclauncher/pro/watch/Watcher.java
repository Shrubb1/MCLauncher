package com.mclauncher.pro.watch;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
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
 *  - players within NEAR_DIST show "X nearby (Nd)"
 *  - entities with isInvisible set still exist client-side - we flag
 *    them even though nothing renders
 *  - a player whose entity suddenly despawns while their name stays in
 *    the server player list is flagged "vanished (still online)"
 * Both render top-center; each fires a soft ding when triggered.
 */
public final class Watcher {

    private static final int ALERT_TICKS = 40;          // ~2s of gaze
    private static final double COS_FOV = Math.cos(Math.toRadians(12.0));
    private static final double GAZE_DIST = 96.0;
    private static final double NEAR_DIST = 16.0;
    private static final long MENTION_MS = 6000;
    private static final long VANISH_WINDOW_MS = 3000;  // entity must
        // have been spawned within this window to call a despawn sudden
    private static final long SEEN_TTL_MS = 30000;

    private static final Map<String, Integer> watchers = new HashMap<>();
    private static final Map<String, Long> lastSeen = new HashMap<>();
    private static final Map<String, Double> lastDist = new HashMap<>();
    private static final Set<String> invisible = new HashSet<>();
    private static final Map<String, Double> invisDist = new HashMap<>();
    private static final Map<String, Double> nearDist = new HashMap<>();
    private static final Set<String> vanished = new HashSet<>();
    private static String mentionMsg;
    private static long mentionAt;

    private Watcher() {
    }

    /** Per player tick while pro is enabled. */
    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null
                || mc.getNetworkHandler() == null) {
            watchers.clear();
            lastSeen.clear();
            lastDist.clear();
            invisible.clear();
            invisDist.clear();
            nearDist.clear();
            vanished.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Vec3d me = mc.player.getEyePos();
        String self = mc.player.getName().getString();
        Set<String> gazeSeen = new HashSet<>();
        Set<String> present = new HashSet<>();

        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || !other.isAlive()) {
                continue;
            }
            Vec3d eye = other.getEyePos();
            double dist = eye.distanceTo(me);
            String name = other.getName().getString();
            present.add(name);
            lastSeen.put(name, now);
            lastDist.put(name, dist);
            vanished.remove(name);  // entity exists -> not vanished

            // invisible entities are still in world data, just not
            // rendered - flag them
            if (other.isInvisible()) {
                if (invisible.add(name)) {
                    ding(0.9f);
                }
                invisDist.put(name, dist);
            } else {
                invisible.remove(name);
                invisDist.remove(name);
            }

            // proximity alert
            if (dist <= NEAR_DIST) {
                if (nearDist.put(name, dist) == null) {
                    ding(1.4f);
                }
            } else {
                nearDist.remove(name);
            }

            // gaze
            if (dist > GAZE_DIST) {
                continue;
            }
            Vec3d toMe = me.subtract(eye).normalize();
            if (toMe.dotProduct(other.getRotationVector()) < COS_FOV
                    || !lineOfSight(mc, other, eye, me)) {
                continue;
            }
            gazeSeen.add(name);
            int t = watchers.merge(name, 1, Integer::sum);
            if (t == ALERT_TICKS) {
                ding(1.2f);
            }
        }
        watchers.keySet().retainAll(gazeSeen);
        invisible.retainAll(present);
        invisDist.keySet().retainAll(invisible);
        nearDist.keySet().retainAll(present);

        // vanish check: name in the player list but the entity we saw
        // seconds ago is suddenly gone -> vanished/death/teleport; the
        // list entry staying means they did not log out
        Set<String> listed = new HashSet<>();
        for (PlayerListEntry e
                : mc.getNetworkHandler().getPlayerList()) {
            listed.add(e.getProfile().name());
        }
        for (String n : listed) {
            if (n.equals(self) || vanished.contains(n)) {
                continue;
            }
            Long t = lastSeen.get(n);
            Double d = lastDist.get(n);
            if (t != null && d != null && now - t < VANISH_WINDOW_MS
                    && d < 64.0 && !hasEntity(mc, n)) {
                vanished.add(n);
                ding(0.7f);
            }
        }
        // stop flagging once they actually leave the list
        vanished.retainAll(listed);
        // prune stale sightings
        lastSeen.entrySet().removeIf(
                e -> now - e.getValue() > SEEN_TTL_MS
                        || !listed.contains(e.getKey()));
        lastDist.keySet().retainAll(lastSeen.keySet());
    }

    private static boolean hasEntity(MinecraftClient mc, String name) {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equals(name)) {
                return true;
            }
        }
        return false;
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
            String s = "§c" + e.getKey() + " is looking at you ("
                    + e.getValue() / 20 + "s)";
            ctx.drawCenteredTextWithShadow(mc.textRenderer, s, cx, y,
                    0xFFFF5555);
            y += 11;
        }
        for (String n : vanished) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    "§5" + n + " vanished - still online", cx, y,
                    0xFFAA00FF);
            y += 11;
        }
        for (var e : invisDist.entrySet()) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    "§d" + e.getKey() + " is invisible ("
                            + (int) (double) e.getValue() + "m)",
                    cx, y, 0xFFFF55FF);
            y += 11;
        }
        for (var e : nearDist.entrySet()) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    "§b" + e.getKey() + " nearby ("
                            + (int) (double) e.getValue() + "m)",
                    cx, y, 0xFF55FFFF);
            y += 11;
        }
        if (mentionMsg != null) {
            if (System.currentTimeMillis() - mentionAt < MENTION_MS) {
                ctx.drawCenteredTextWithShadow(mc.textRenderer,
                        "§e" + mentionMsg, cx, y, 0xFFFFFF55);
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
