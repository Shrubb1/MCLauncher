package com.mclauncher.pro.mixin;

import com.mclauncher.client.ClientMod;
import com.mclauncher.pro.ProMod;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scroll-to-adjust zoom factor while zooming. */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void mcl$scroll(long window, double horizontal, double vertical,
                            CallbackInfo ci) {
        if (ProMod.isEnabled() && ClientMod.state.zooming
                && ClientMod.settings.on("zoom")) {
            double f = ClientMod.settings.zoomFactor
                    - vertical * 0.5;
            ClientMod.settings.zoomFactor =
                    Math.max(2.0, Math.min(10.0, f));
            ci.cancel(); // keep scroll from switching hotbar slots while zoomed
        }
    }
}
