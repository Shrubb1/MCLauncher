package com.piglurer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.player.LocalPlayer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.regex.Pattern;

public final class Sense {
    private Sense() { }

    // ---------- text extraction ----------

    public static String entityText(Entity e) {
        StringBuilder sb = new StringBuilder();
        Component custom = e.getCustomName();
        if (custom != null) sb.append(custom.getString()).append(' ');
        sb.append(e.getName().getString());
        // text_display entities carry their content in a render-state Text
        if (e instanceof Display.TextDisplay td) {
            Display.TextDisplay.TextRenderState data = td.textRenderState();
            if (data != null && data.text() != null) sb.append(' ').append(data.text().getString());
        }
        return sb.toString().trim();
    }

    public static boolean isFloatingText(Entity e) {
        if (!(e instanceof ArmorStand || e instanceof Display.TextDisplay)) return false;
        return !entityText(e).isEmpty();
    }

    public static Vec3 center(Entity e) {
        return e.getBoundingBox().getCenter();
    }

    static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------- pig scanning ----------

    // A floating tag is an owner tag when its text isn't just the pig's own name.
    // Returns the tag entity, or null if the pig is unclaimed.
    public static Entity findOwnerTag(ClientLevel world, Pig pig, String pigName) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.entitiesForRendering()) {
            if (e == pig || !isFloatingText(e)) continue;
            if (horizontalDist(e.position(), pig.position()) > 3.0) continue; // uses cfg at call site
            double dy = e.getY() - pig.getY();
            if (dy < -0.5) continue;
            String text = entityText(e);
            if (text.toLowerCase().contains(pigName.toLowerCase())) continue; // just the name label
            double d = e.position().distanceTo(pig.position());
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    // SkyBlock-style nameplates live on a floating text entity above the
    // mob, not the mob's own custom name - accept either as the pig's name
    private static boolean isNamedPig(ClientLevel world, Pig pig, String pigName) {
        if (entityText(pig).toLowerCase().contains(pigName)) return true;
        for (Entity e : world.entitiesForRendering()) {
            if (e == pig || !isFloatingText(e)) continue;
            if (horizontalDist(e.position(), pig.position()) > 3.0) continue;
            double dy = e.getY() - pig.getY();
            if (dy < -0.5 || dy > 3.5) continue;
            if (entityText(e).toLowerCase().contains(pigName)) return true;
        }
        return false;
    }

    // /piglure pigdebug - dump nearby entities with their type + text so we
    // can see what the "pigs" actually are when matching fails
    public static void debugEntities(ClientLevel world, LocalPlayer p, FabricClientCommandSource src) {
        int shown = 0;
        for (Entity e : world.entitiesForRendering()) {
            double d = e.position().distanceTo(p.position());
            if (d > 24) continue;
            String text = entityText(e);
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            src.sendFeedback(Component.literal(
                    (e instanceof Pig ? ">> " : "   ") + type.replace("minecraft:", "")
                    + " @" + String.format("%.1f", d) + "b " + e.blockPosition().toShortString()
                    + (text.isEmpty() ? "" : " '" + text.substring(0, Math.min(40, text.length())) + "'")));
            if (++shown >= 20) {
                src.sendFeedback(Component.literal("...(truncated)"));
                break;
            }
        }
        if (shown == 0) src.sendFeedback(Component.literal("no entities within 24b"));
    }

    // Nearest usable pig: named pigName (own name or its nameplate), and
    // either unclaimed or tagged with our IGN.
    public static Pig findShinyPig(ClientLevel world, Vec3 from, PigLureConfig cfg, String ourName) {
        String pigName = cfg.pigName.toLowerCase();
        String ownerTag = cfg.targetOwnerTag.isEmpty() ? ourName.toLowerCase() : cfg.targetOwnerTag.toLowerCase();
        Pig best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.entitiesForRendering()) {
            if (!(e instanceof Pig pig)) continue;
            if (e.position().distanceTo(from) > cfg.scanRange) continue;
            if (!isNamedPig(world, pig, pigName)) continue;
            Entity tag = findOwnerTag(world, pig, cfg.pigName);
            if (tag != null) {
                double dy = tag.getY() - pig.getY();
                if (dy > cfg.tagHeightMax) { /* too high, ignore tag */ }
                else {
                    String tagText = entityText(tag).toLowerCase();
                    if (!tagText.contains(ownerTag)) continue; // claimed by someone else
                }
            }
            double d = e.position().distanceTo(from);
            if (d < bestDist) { bestDist = d; best = pig; }
        }
        return best;
    }

    // ---------- inventory ----------

    static String stackText(ItemStack s) {
        return s.isEmpty() ? "" : s.getHoverName().getString().toLowerCase();
    }

    public static int countOrbs(Inventory inv, String orbName) {
        String needle = orbName.toLowerCase();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && stackText(s).contains(needle)) n += s.getCount();
        }
        return n;
    }

    // returns Inventory index (0-8 hotbar, 9-35 main) or -1
    public static int findOrbInvSlot(Inventory inv, String orbName) {
        String needle = orbName.toLowerCase();
        for (int i = 0; i <= 35; i++) {
            if (stackText(inv.getItem(i)).contains(needle)) return i;
        }
        return -1;
    }

    // ---------- the "huge orb" ----------

    static boolean isOrbCandidateType(Entity e) {
        return e instanceof ArmorStand
                || e instanceof Display
                || e instanceof Interaction
                || e instanceof EndCrystal
                || e instanceof Slime
                || e instanceof MagmaCube
                || e instanceof ItemFrame;
    }

    // Locate the interactable orb near `pos`. The orb's own label says
    // "SHINY ORB" - prefer text containing "orb" over the generic orbMatch
    // ("click") which NPC nameplates like the Auction Agent also carry.
    // Nearest match wins, then an Interaction hitbox near the anchor, then
    // the nearest candidate-type entity.
    public static Entity findOrbEntity(ClientLevel world, Vec3 pos, PigLureConfig cfg) {
        Pattern orbRe = Pattern.compile(cfg.orbMatch, Pattern.CASE_INSENSITIVE);
        Entity orbText = null;   double orbTextDist = Double.MAX_VALUE;
        Entity matchText = null; double matchDist = Double.MAX_VALUE;
        Entity typedBest = null; double typedDist = Double.MAX_VALUE;
        for (Entity e : world.entitiesForRendering()) {
            if (e instanceof Player || e instanceof Pig) continue;
            double d = e.position().distanceTo(pos);
            if (d > cfg.orbSearchRadius) continue;
            String text = entityText(e).toLowerCase();
            if (text.contains("orb")) {
                if (d < orbTextDist) { orbTextDist = d; orbText = e; }
            } else if (orbRe.matcher(text).find()) {
                if (d < matchDist) { matchDist = d; matchText = e; }
            }
            if (isOrbCandidateType(e) && d < typedDist) { typedDist = d; typedBest = e; }
        }
        Entity anchor = orbText != null ? orbText
                : matchText != null ? matchText : typedBest;
        if (anchor == null) return null;

        // an `interaction` entity near the anchor is the proper click hitbox
        Entity hitbox = null;
        double hitDist = Double.MAX_VALUE;
        for (Entity e : world.entitiesForRendering()) {
            if (!(e instanceof Interaction)) continue;
            double d = e.position().distanceTo(anchor.position());
            if (d <= 3.0 && d < hitDist) { hitDist = d; hitbox = e; }
        }
        return hitbox != null ? hitbox : anchor;
    }

    // every plausible clickable entity in the orb cluster - the real
    // interactable is usually an invisible Interaction hitbox while the
    // text plate that findOrbEntity returns is a server-side no-op
    public static java.util.List<Entity> findOrbClickables(ClientLevel world, Vec3 pos, PigLureConfig cfg) {
        java.util.List<Entity> out = new java.util.ArrayList<>();
        for (Entity e : world.entitiesForRendering()) {
            if (e instanceof Player || e instanceof Pig) continue;
            if (e.position().distanceToSqr(pos) > cfg.orbSearchRadius * cfg.orbSearchRadius) continue;
            if (isOrbCandidateType(e)) out.add(e);
        }
        return out;
    }

    public static Entity nearestShopInteractable(ClientLevel world, Vec3 pos, double radius) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.entitiesForRendering()) {
            if (e instanceof Player || e instanceof Pig) continue;
            double d = e.position().distanceTo(pos);
            if (d <= radius && d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }
}
