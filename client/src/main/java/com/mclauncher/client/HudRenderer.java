package com.mclauncher.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

import java.util.Map;

/** Draws the draggable HUD widgets. Positions live in ClientSettings. */
public final class HudRenderer {

    private HudRenderer() {
    }

    public static void render(DrawContext ctx, MinecraftClient mc,
                              ClientSettings s, HudState st) {
        if (s.on("fps")) {
            int[] p = s.pos("fps", 4, 4);
            ctx.drawText(mc.textRenderer, mc.getCurrentFps() + " FPS",
                    p[0], p[1], 0xFFFFFFFF, true);
        }

        if (s.on("ping")) {
            int[] p = s.pos("ping", 4, 16);
            String txt = "Ping: --";
            if (mc.player != null && mc.getNetworkHandler() != null) {
                PlayerListEntry e = mc.getNetworkHandler()
                        .getPlayerListEntry(mc.player.getUuid());
                if (e != null) {
                    txt = "Ping: " + e.getLatency() + "ms";
                }
            }
            ctx.drawText(mc.textRenderer, txt, p[0], p[1], 0xFFFFFFFF, true);
        }

        if (s.on("coords") && mc.player != null) {
            int[] p = s.pos("coords", 4, 28);
            var pos = mc.player.getBlockPos();
            var dir = mc.player.getHorizontalFacing().asString();
            String txt = String.format("%d %d %d  (%s)",
                    pos.getX(), pos.getY(), pos.getZ(), dir);
            ctx.drawText(mc.textRenderer, txt, p[0], p[1], 0xFFFFFFFF, true);
        }

        if (s.on("armor") && mc.player != null) {
            int[] p = s.pos("armor", 4, 40);
            StringBuilder sb = new StringBuilder();
            for (var slot : new net.minecraft.entity.EquipmentSlot[]{
                    net.minecraft.entity.EquipmentSlot.HEAD,
                    net.minecraft.entity.EquipmentSlot.CHEST,
                    net.minecraft.entity.EquipmentSlot.LEGS,
                    net.minecraft.entity.EquipmentSlot.FEET}) {
                ItemStack stack = mc.player.getEquippedStack(slot);
                if (!stack.isEmpty() && stack.isDamageable()) {
                    int pct = MathHelper.ceil(
                            (1f - (float) stack.getDamage() / stack.getMaxDamage()) * 100);
                    sb.append(pct).append("% ");
                }
            }
            if (sb.length() > 0) {
                ctx.drawText(mc.textRenderer, "Armor: " + sb.toString().trim(),
                        p[0], p[1], 0xFFFFFFFF, true);
            }
        }

        if (s.on("potions") && mc.player != null) {
            int[] p = s.pos("potions", 4, 52);
            int y = p[1];
            for (StatusEffectInstance fx : mc.player.getStatusEffects()) {
                int secs = fx.getDuration() / 20;
                String txt = fx.getEffectType().value().getName().getString()
                        + " " + (secs / 60) + ":" + String.format("%02d", secs % 60);
                ctx.drawText(mc.textRenderer, txt, p[0], y, 0xFFAAFFAA, true);
                y += 10;
            }
        }

        if (s.on("keystrokes")) {
            int[] p = s.pos("keystrokes", 4, 80);
            renderKeystrokes(ctx, mc, st, p[0], p[1]);
        }

        if (st.sprintToggled && s.on("sprint")) {
            int[] p = s.pos("sprint", 4, 130);
            ctx.drawText(mc.textRenderer, "[Sprint: Toggled]",
                    p[0], p[1], 0xFF55FFFF, true);
        }
    }

    private static void renderKeystrokes(DrawContext ctx, MinecraftClient mc,
                                         HudState st, int x, int y) {
        var opt = mc.options;
        KeyBinding[] keys = {opt.forwardKey, opt.leftKey, opt.backKey,
                opt.rightKey, opt.jumpKey};
        String[] labels = {"W", "A", "S", "D", "SP"};
        for (int i = 0; i < keys.length; i++) {
            int kx = x + i * 22;
            boolean pressed = keys[i].isPressed();
            ctx.fill(kx, y, kx + 20, y + 14,
                    pressed ? 0xAAFFFFFF : 0x66000000);
            ctx.drawText(mc.textRenderer, labels[i], kx + 8 - (i == 4 ? 5 : 2),
                    y + 3, pressed ? 0xFF000000 : 0xFFFFFFFF, false);
        }
        ctx.drawText(mc.textRenderer,
                "LMB " + st.cps(0) + "cps  RMB " + st.cps(1) + "cps",
                x, y + 18, 0xFFFFFFFF, true);
    }

    /** Hit-test used by the config screen for dragging. */
    public static String widgetAt(ClientSettings s, MinecraftClient mc,
                                  double mx, double my) {
        Map<String, int[]> defaults = Map.of(
                "fps", new int[]{4, 4}, "ping", new int[]{4, 16},
                "coords", new int[]{4, 28}, "armor", new int[]{4, 40},
                "potions", new int[]{4, 52}, "keystrokes", new int[]{4, 80},
                "sprint", new int[]{4, 130});
        for (var e : defaults.entrySet()) {
            if (!s.on(e.getKey())) {
                continue;
            }
            int[] p = s.pos(e.getKey(), e.getValue()[0], e.getValue()[1]);
            int w = e.getKey().equals("keystrokes") ? 110 : 80;
            int h = e.getKey().equals("keystrokes") ? 32 : 10;
            if (mx >= p[0] && mx <= p[0] + w && my >= p[1] && my <= p[1] + h) {
                return e.getKey();
            }
        }
        return null;
    }
}
