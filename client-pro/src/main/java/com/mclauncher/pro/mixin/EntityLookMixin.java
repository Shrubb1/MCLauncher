package com.mclauncher.pro.mixin;

import com.mclauncher.pro.path.Navigator;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the navigator owns the controls, physical mouse movement must
 * not fight its steering. Every mouse delta flows through
 * changeLookDirection - swallowing it here locks the camera to the
 * route. Only the local player ever receives look input, so this is
 * safe on Entity. Navigator steers via setYaw, which bypasses this.
 */
@Mixin(Entity.class)
public class EntityLookMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"),
            cancellable = true)
    private void mcl$lockLook(double cursorDeltaX, double cursorDeltaY,
                              CallbackInfo ci) {
        if (Navigator.active()) {
            ci.cancel();
        }
    }
}
