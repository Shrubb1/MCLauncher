package com.mclauncher.pro.mixin;

import com.mclauncher.pro.ProMod;
import com.mclauncher.pro.path.PathCommand;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swallows "#"-prefixed chat as local path commands. */
@Mixin(ClientPlayNetworkHandler.class)
public class NetworkHandlerMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"),
            cancellable = true)
    private void mcl$chat(String content, CallbackInfo ci) {
        if (ProMod.isEnabled() && PathCommand.handle(content)) {
            ci.cancel();
        }
    }
}
