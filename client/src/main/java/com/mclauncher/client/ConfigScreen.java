package com.mclauncher.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Right-Shift menu: a centered panel with a colored page sidebar,
 * launcher-style. Dashboard shows automation history pushed in by the
 * pro jar; Modules holds the widget toggles + drag-to-position; Route
 * holds the one-click mining loop.
 */
public class ConfigScreen extends Screen {

    private static final List<String> MODULES = List.of(
            "fps", "ping", "coords", "armor", "potions",
            "keystrokes", "sprint", "zoom", "fullbright", "borderless");

    private static final String[] PAGES = {"Dashboard", "Modules", "Route"};
    private static final int[] ACCENT = {0xFF4E8CFF, 0xFF35C76F, 0xFFB26BFF};
    private static final int PANEL_BG = 0xE60E0E16;
    private static final int LINE = 0xFF2E2E40;

    private int page;
    private String dragging;
    private int dragOffX, dragOffY;
    private final List<ButtonWidget> modBtns = new ArrayList<>();
    private ButtonWidget routeBtn, doneBtn;

    public ConfigScreen() {
        super(Text.literal("MCLauncher Client"));
    }

    private int[] panel() {
        int pw = Math.min(430, this.width - 40);
        int ph = Math.min(272, this.height - 40);
        return new int[]{(this.width - pw) / 2, (this.height - ph) / 2,
                pw, ph};
    }

    @Override
    protected void init() {
        modBtns.clear(); // init re-runs on resize - don't accumulate
        int[] p = panel();
        int cx = p[0] + 112, cy = p[1] + 40;
        int i = 0;
        for (String id : MODULES) {
            final String mid = id;
            ButtonWidget b = ButtonWidget.builder(
                            Text.literal(label(mid)), bt -> toggle(mid, bt))
                    .dimensions(cx + (i % 2) * 118, cy + (i / 2) * 23,
                            112, 18).build();
            modBtns.add(b);
            addDrawableChild(b);
            i++;
        }
        routeBtn = ButtonWidget.builder(
                        Text.literal(routeLabel()), this::runRoute)
                .dimensions(cx, cy + 8, 150, 20).build();
        addDrawableChild(routeBtn);
        doneBtn = ButtonWidget.builder(Text.literal("Done"),
                        b -> close())
                .dimensions(p[0] + p[2] - 70, p[1] + p[3] - 30,
                        56, 20).build();
        addDrawableChild(doneBtn);
        select(page);
    }

    private void select(int i) {
        page = i;
        for (ButtonWidget b : modBtns) {
            b.visible = i == 1;
        }
        if (routeBtn != null) {
            routeBtn.visible = i == 2;
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

    // ------------------------------------------------------------- render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];

        // panel + title + accent underline
        ctx.fill(px, py, px + pw, py + ph, PANEL_BG);
        ctx.fill(px, py, px + pw, py + 1, LINE);
        ctx.fill(px, py + ph - 1, px + pw, py + ph, LINE);
        ctx.fill(px, py, px + 1, py + ph, LINE);
        ctx.fill(px + pw - 1, py, px + pw, py + ph, LINE);
        ctx.drawText(this.textRenderer, "MCLauncher", px + 12, py + 10,
                0xFFFFFFFF, false);
        ctx.fill(px + 12, py + 21, px + 80, py + 22, ACCENT[page]);

        // sidebar
        for (int i = 0; i < PAGES.length; i++) {
            int sy = py + 36 + i * 30;
            boolean sel = i == page;
            ctx.fill(px + 8, sy, px + 98, sy + 22,
                    sel ? ((ACCENT[i] & 0xFFFFFF) | 0x66000000)
                            : 0x66222230);
            ctx.fill(px + 8, sy, px + 11, sy + 22, ACCENT[i]);
            ctx.drawText(this.textRenderer, PAGES[i], px + 17, sy + 7,
                    sel ? 0xFFFFFFFF : 0xFF9A9AA8, false);
        }
        ctx.fill(px + 104, py + 8, px + 105, py + ph - 8, LINE);

        int cx = px + 112;
        switch (page) {
            case 0 -> renderDashboard(ctx, cx, py + 34, pw - 122);
            case 1 -> renderModules(ctx, cx, py + 34);
            case 2 -> renderRoute(ctx, cx, py + 66, pw - 122);
            default -> {
            }
        }
    }

    private void renderDashboard(DrawContext ctx, int cx, int cy, int cw) {
        TaskStats s = ClientMod.stats;
        // live line
        if (s != null && s.active()) {
            ctx.drawText(this.textRenderer,
                    "NOW  " + s.activeKind + " " + s.activeDetail + "  ·  "
                            + fmtSecs(s.activeSeconds()),
                    cx, cy, 0xFF7BE38B, false);
        } else {
            ctx.drawText(this.textRenderer, "idle", cx, cy,
                    0xFF8A8A96, false);
        }
        // stat cards
        String[][] cards = {
                {"Tasks run", s == null ? "0" : String.valueOf(s.tasksRun)},
                {"Time automated", s == null ? "0s"
                        : fmtSecs((int) s.taskSeconds)},
                {"Blocks mined", s == null ? "0"
                        : String.valueOf(s.blocksMined)},
                {"Money earned", s != null && s.moneyEarned > 0
                        ? String.format("%.2f", s.moneyEarned) : "soon"}};
        for (int i = 0; i < cards.length; i++) {
            int bx = cx + i * (cw / 4 + 2), bw = cw / 4 - 4;
            ctx.fill(bx, cy + 16, bx + bw, cy + 50, 0x551B1B28);
            ctx.fill(bx, cy + 16, bx + bw, cy + 17, ACCENT[i % 3]);
            ctx.drawText(this.textRenderer, cards[i][1], bx + 5, cy + 21,
                    0xFFFFFFFF, false);
            ctx.drawText(this.textRenderer, cards[i][0], bx + 5, cy + 38,
                    0xFF8A8A96, false);
        }
        // recent list
        ctx.drawText(this.textRenderer, "Recent tasks", cx, cy + 62,
                0xFF9A9AA8, false);
        ctx.fill(cx, cy + 72, cx + cw, cy + 73, LINE);
        if (s == null || s.log.isEmpty()) {
            ctx.drawText(this.textRenderer,
                    "nothing yet - #goto, #mine or the route",
                    cx, cy + 80, 0xFF666672, false);
            return;
        }
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm");
        int rows = Math.min(9, s.log.size());
        for (int i = 0; i < rows; i++) {
            TaskStats.Event e = s.log.get(i);
            int ry = cy + 80 + i * 14;
            int col = e.kind.equals("mine") ? ACCENT[1]
                    : e.kind.equals("route") ? ACCENT[2] : ACCENT[0];
            ctx.drawText(this.textRenderer,
                    tf.format(new Date(e.ts)), cx, ry, 0xFF666672, false);
            ctx.fill(cx + 32, ry + 1, cx + 35, ry + 8, col);
            String line = e.kind + (e.detail.isEmpty() ? "" : " " + e.detail);
            ctx.drawText(this.textRenderer, line, cx + 40, ry,
                    0xFFD8D8E0, false);
            String tail = fmtSecs(e.seconds)
                    + (e.count > 0 ? "  ×" + e.count : "");
            ctx.drawText(this.textRenderer, tail,
                    cx + cw - this.textRenderer.getWidth(tail), ry,
                    0xFF8A8A96, false);
        }
    }

    private void renderModules(DrawContext ctx, int cx, int cy) {
        ctx.drawText(this.textRenderer,
                "Drag widget boxes to reposition:", cx, cy + 132,
                0xFF9A9AA8, false);
        Map<String, int[]> defs = Map.of(
                "fps", new int[]{cx, cy + 146}, "ping", new int[]{cx, cy + 158},
                "coords", new int[]{cx, cy + 170}, "armor", new int[]{cx, cy + 182},
                "potions", new int[]{cx + 90, cy + 146},
                "keystrokes", new int[]{cx + 90, cy + 164},
                "sprint", new int[]{cx + 216, cy + 146});
        for (var e : defs.entrySet()) {
            String id = e.getKey();
            int[] pt = ClientMod.settings.pos(id, e.getValue()[0],
                    e.getValue()[1]);
            int w = id.equals("keystrokes") ? 110 : 80;
            int h = id.equals("keystrokes") ? 32 : 10;
            boolean on = ClientMod.settings.on(id);
            ctx.fill(pt[0] - 2, pt[1] - 2, pt[0] + w + 2, pt[1] + h + 2,
                    on ? 0x88336699 : 0x55444444);
            ctx.drawText(this.textRenderer,
                    on ? id : id + " (off)", pt[0], pt[1] + 1,
                    on ? 0xFFFFFFFF : 0xFF888888, false);
        }
    }

    private void renderRoute(DrawContext ctx, int cx, int cy, int cw) {
        ctx.drawText(this.textRenderer,
                "Runs the admin-published loop:", cx, cy + 30,
                0xFF9A9AA8, false);
        ctx.drawText(this.textRenderer,
                "goto a spot -> mine -> next spot -> repeat.",
                cx, cy + 42, 0xFF666672, false);
        if (ClientMod.state != null && ClientMod.state.routeActive
                && ClientMod.stats != null && ClientMod.stats.active()) {
            ctx.drawText(this.textRenderer,
                    "running: " + ClientMod.stats.activeKind + " "
                            + ClientMod.stats.activeDetail,
                    cx, cy + 62, 0xFF7BE38B, false);
        }
    }

    private static String fmtSecs(int s) {
        if (s >= 3600) {
            return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        }
        if (s >= 60) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return s + "s";
    }

    // ------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        int[] p = panel();
        // sidebar page buttons
        if (mx >= p[0] + 8 && mx <= p[0] + 98) {
            for (int i = 0; i < PAGES.length; i++) {
                int sy = p[1] + 36 + i * 30;
                if (my >= sy && my <= sy + 22) {
                    select(i);
                    return true;
                }
            }
        }
        // drag handles only exist on the Modules page
        if (page == 1) {
            int cx = p[0] + 112, cy = p[1] + 34;
            String hit = HudRenderer.widgetAt(ClientMod.settings,
                    net.minecraft.client.MinecraftClient.getInstance(),
                    mx, my);
            if (hit == null) {
                Map<String, int[]> defs = Map.of(
                        "fps", new int[]{cx, cy + 146},
                        "ping", new int[]{cx, cy + 158},
                        "coords", new int[]{cx, cy + 170},
                        "armor", new int[]{cx, cy + 182},
                        "potions", new int[]{cx + 90, cy + 146},
                        "keystrokes", new int[]{cx + 90, cy + 164},
                        "sprint", new int[]{cx + 216, cy + 146});
                for (var e : defs.entrySet()) {
                    int[] pt = ClientMod.settings.pos(e.getKey(),
                            e.getValue()[0], e.getValue()[1]);
                    int w = e.getKey().equals("keystrokes") ? 110 : 80;
                    int h = e.getKey().equals("keystrokes") ? 32 : 10;
                    if (mx >= pt[0] && mx <= pt[0] + w
                            && my >= pt[1] && my <= pt[1] + h) {
                        hit = e.getKey();
                        break;
                    }
                }
            }
            if (hit != null) {
                dragging = hit;
                int[] pt = ClientMod.settings.pos(hit, 0, 0);
                dragOffX = (int) mx - pt[0];
                dragOffY = (int) my - pt[1];
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) {
            int[] pt = ClientMod.settings.pos(dragging, 0, 0);
            pt[0] = MathHelper.clamp((int) click.x() - dragOffX, 0,
                    this.width - 20);
            pt[1] = MathHelper.clamp((int) click.y() - dragOffY, 0,
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
