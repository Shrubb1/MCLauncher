package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CPS counting + scroll-to-adjust zoom factor while zooming. */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void mcl$click(long window, int button, int action, int mods,
                           CallbackInfo ci) {
        if (action == 1 && MinecraftClient.getInstance().currentScreen == null
                && (button == 0 || button == 1)) {
            ClientMod.state.click(button);
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void mcl$scroll(long window, double horizontal, double vertical,
                            CallbackInfo ci) {
        if (ClientMod.state.zooming && ClientMod.settings.on("zoom")) {
            double f = ClientMod.settings.zoomFactor
                    - vertical * 0.5;
            ClientMod.settings.zoomFactor =
                    Math.max(2.0, Math.min(10.0, f));
            ci.cancel(); // keep scroll from switching hotbar slots while zoomed
        }
    }
}
