package com.mclauncher.pro.mixin;

import com.mclauncher.pro.ProMod;
import com.mclauncher.pro.watch.Watcher;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds incoming chat lines into the mention watcher. */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V",
            at = @At("HEAD"))
    private void mcl$mention(Text message, CallbackInfo ci) {
        if (ProMod.isEnabled()) {
            Watcher.onChat(message.getString());
        }
    }
}
