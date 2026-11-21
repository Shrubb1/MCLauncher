package com.mclauncher.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.Map;

/**
 * Right-Shift config screen: module toggles on the left, drag the widget
 * placeholders on the right to reposition them.
 */
public class ConfigScreen extends Screen {

    private static final List<String> MODULES = List.of(
            "fps", "ping", "coords", "armor", "potions",
            "keystrokes", "sprint", "zoom", "fullbright", "borderless");

    private String dragging;
    private int dragOffX, dragOffY;

    public ConfigScreen() {
        super(Text.literal("MCLauncher Client"));
    }

    @Override
    protected void init() {
        int y = 20;
        for (String id : MODULES) {
            final String mid = id;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(label(mid)), b -> toggle(mid, b))
                    .dimensions(10, y, 110, 18).build());
            y += 22;
        }
        addDrawableChild(ButtonWidget.builder(
                        Text.literal(routeLabel()), b -> runRoute(b))
                .dimensions(10, y, 110, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
                        b -> close())
                .dimensions(10, y + 24, 110, 20).build());
    }

    private static String routeLabel() {
        if (ClientMod.state == null || ClientMod.state.routeAction == null) {
            return "mining route: pro only";
        }
        return "mining route: "
                + (ClientMod.state.routeActive ? "RUNNING" : "start");
    }

    private void runRoute(ButtonWidget b) {
        if (ClientMod.state != null && ClientMod.state.routeAction != null) {
            ClientMod.state.routeAction.run();
            b.setMessage(Text.literal(routeLabel()));
        }
    }

    private String label(String id) {
        return id + ": " + (ClientMod.settings.on(id) ? "ON" : "off");
    }

    private void toggle(String id, ButtonWidget b) {
        ClientMod.settings.toggle(id);
        if ("borderless".equals(id) && !ClientMod.settings.on(id)) {
            ClientMod.settings.borderlessApplied = false;
        }
        b.setMessage(Text.literal(label(id)));
        ClientMod.saveQuiet();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawText(this.textRenderer,
                "Drag widget boxes to reposition:", 130, 20, 0xFFAAAAAA, false);

        Map<String, int[]> defs = Map.of(
                "fps", new int[]{140, 30}, "ping", new int[]{140, 42},
                "coords", new int[]{140, 54}, "armor", new int[]{140, 66},
                "potions", new int[]{140, 78}, "keystrokes", new int[]{140, 96},
                "sprint", new int[]{140, 146});
        for (var e : defs.entrySet()) {
            String id = e.getKey();
            int[] p = ClientMod.settings.pos(id, e.getValue()[0], e.getValue()[1]);
            int w = id.equals("keystrokes") ? 110 : 80;
            int h = id.equals("keystrokes") ? 32 : 10;
            boolean on = ClientMod.settings.on(id);
            ctx.fill(p[0] - 2, p[1] - 2, p[0] + w + 2, p[1] + h + 2,
                    on ? 0x88336699 : 0x55444444);
            ctx.drawText(this.textRenderer,
                    on ? id : id + " (off)", p[0], p[1] + 1,
                    on ? 0xFFFFFFFF : 0xFF888888, false);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        String hit = HudRenderer.widgetAt(ClientMod.settings,
                net.minecraft.client.MinecraftClient.getInstance(), mx, my);
        if (hit == null) {
            // also allow grabbing disabled widgets' placeholders on the right
            Map<String, int[]> defs = Map.of(
                    "fps", new int[]{140, 30}, "ping", new int[]{140, 42},
                    "coords", new int[]{140, 54}, "armor", new int[]{140, 66},
                    "potions", new int[]{140, 78},
                    "keystrokes", new int[]{140, 96},
                    "sprint", new int[]{140, 146});
            for (var e : defs.entrySet()) {
                int[] p = ClientMod.settings.pos(e.getKey(),
                        e.getValue()[0], e.getValue()[1]);
                int w = e.getKey().equals("keystrokes") ? 110 : 80;
                int h = e.getKey().equals("keystrokes") ? 32 : 10;
                if (mx >= p[0] && mx <= p[0] + w && my >= p[1] && my <= p[1] + h) {
                    hit = e.getKey();
                    break;
                }
            }
        }
        if (hit != null) {
            dragging = hit;
            int[] p = ClientMod.settings.pos(hit, 0, 0);
            dragOffX = (int) mx - p[0];
            dragOffY = (int) my - p[1];
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) {
            int[] p = ClientMod.settings.pos(dragging, 0, 0);
            p[0] = MathHelper.clamp((int) click.x() - dragOffX, 0,
                    this.width - 20);
            p[1] = MathHelper.clamp((int) click.y() - dragOffY, 0,
                    this.height - 10);
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            ClientMod.saveQuiet();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        ClientMod.saveQuiet();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
