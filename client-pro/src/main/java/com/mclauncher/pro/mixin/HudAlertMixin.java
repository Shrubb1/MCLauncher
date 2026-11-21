package com.mclauncher.pro.mixin;

import com.mclauncher.pro.ProMod;
import com.mclauncher.pro.watch.Watcher;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws watch/mention alerts over the vanilla HUD. */
@Mixin(InGameHud.class)
public class HudAlertMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void mcl$alerts(DrawContext context, RenderTickCounter tickCounter,
                            CallbackInfo ci) {
        if (ProMod.isEnabled()) {
            Watcher.render(context);
        }
    }
}
