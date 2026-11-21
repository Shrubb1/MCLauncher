package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** C-key smooth zoom: scales FOV while held. No packets, purely visual. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void mcl$zoom(Camera camera, float tickDelta, boolean changingFov,
                          CallbackInfoReturnable<Double> cir) {
        if (ClientMod.isEnabled()
                && ClientMod.settings.on("zoom") && ClientMod.state.zooming
                && changingFov) {
            cir.setReturnValue(cir.getReturnValue()
                    / ClientMod.settings.zoomFactor);
        }
    }
}
