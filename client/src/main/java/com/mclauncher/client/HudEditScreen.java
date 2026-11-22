package com.mclauncher.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * HUD editor: a light overlay (no vanilla background dim) where the
 * enabled widgets show as outlined boxes at their real positions and
 * can be dragged. Done / Right Shift returns to the config menu.
 */
public class HudEditScreen extends Screen {

    private String dragging;
    private int dragOffX, dragOffY;

    public HudEditScreen() {
        super(Text.literal("Edit HUD"));
    }

    private int[] doneBox() {
        return new int[]{this.width - 70, this.height - 34, 56, 20};
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY,
                       float delta) {
        // faint veil so boxes pop without hiding the game behind
        ctx.fill(0, 0, this.width, this.height, 0x55000000);
        var s = ClientMod.settings;
        for (String id : HudRenderer.widgetIds()) {
            if (!s.on(id)) {
                continue;
            }
            int[] b = HudRenderer.bounds(s, id);
            boolean hot = id.equals(dragging)
                    || (mouseX >= b[0] && mouseX <= b[0] + b[2]
                            && mouseY >= b[1] && mouseY <= b[1] + b[3]);
            ctx.fill(b[0] - 2, b[1] - 2, b[0] + b[2] + 2,
                    b[1] + b[3] + 2,
                    hot ? 0x88336699 : 0x88444452);
            ctx.fill(b[0] - 2, b[1] - 2, b[0] + 1, b[1] + b[3] + 2,
                    hot ? 0xFF4E8CFF : 0xFF666672);
            ctx.drawText(this.textRenderer, id, b[0], b[1] + 1,
                    0xFFFFFFFF, false);
        }
        String hint = "drag a box to move it";
        ctx.drawText(this.textRenderer, hint,
                (this.width - this.textRenderer.getWidth(hint)) / 2, 12,
                0xFF9A9AA8, false);
        int[] db = doneBox();
        boolean dh = mouseX >= db[0] && mouseX <= db[0] + db[2]
                && mouseY >= db[1] && mouseY <= db[1] + db[3];
        ctx.fill(db[0], db[1], db[0] + db[2], db[1] + db[3],
                dh ? 0xFF2E2E40 : 0xFF1F1F2C);
        ctx.fill(db[0], db[1], db[0] + 3, db[1] + db[3], 0xFF444452);
        ctx.drawText(this.textRenderer, "Done", db[0] + 9, db[1] + 6,
                dh ? 0xFFFFFFFF : 0xFFD8D8E0, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int[] db = doneBox();
        if (mx >= db[0] && mx <= db[0] + db[2]
                && my >= db[1] && my <= db[1] + db[3]) {
            back();
            return true;
        }
        String hit = HudRenderer.widgetAt(ClientMod.settings,
                MinecraftClient.getInstance(), mx, my);
        if (hit != null) {
            dragging = hit;
            int[] b = HudRenderer.bounds(ClientMod.settings, hit);
            dragOffX = (int) mx - b[0];
            dragOffY = (int) my - b[1];
            return true;
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
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        // Right Shift = done editing, back to the menu
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) {
            back();
            return true;
        }
        return super.keyPressed(input);
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

    private void back() {
        ClientMod.saveQuiet();
        MinecraftClient.getInstance().setScreen(new ConfigScreen());
    }
}
