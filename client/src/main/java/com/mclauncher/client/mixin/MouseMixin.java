package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CPS counting for the keystrokes widget. Scroll-zoom lives in pro. */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void mcl$click(long window, MouseInput input, int action,
                           CallbackInfo ci) {
        if (action == 1 && MinecraftClient.getInstance().currentScreen == null
                && (input.button() == 0 || input.button() == 1)) {
            ClientMod.state.click(input.button());
        }
    }
}
