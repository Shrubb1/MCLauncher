package com.mclauncher.client.mixin;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toggle sprint/sneak: holds the vanilla key state, exactly as if the user
 * were physically holding the key. Restores the real key state when untoggled.
 * Also applies fullbright via the brightness (gamma) option.
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    private boolean sprintWasHeld;
    private boolean sneakWasHeld;
    private boolean fbApplied;
    private double origGamma = 0.5;

    @Inject(method = "tick", at = @At("TAIL"))
    private void mcl$toggles(CallbackInfo ci) {
        if (!ClientMod.isEnabled()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        sprintWasHeld = apply(mc, mc.options.sprintKey,
                ClientMod.state.sprintToggled
                        && ClientMod.settings.on("sprint"), sprintWasHeld);
        sneakWasHeld = apply(mc, mc.options.sneakKey,
                ClientMod.state.sneakToggled, sneakWasHeld);

        // fullbright: crank the brightness option past its UI max, restore on off
        boolean want = ClientMod.settings.on("fullbright");
        if (want && !fbApplied) {
            origGamma = mc.options.getGamma().getValue();
            mc.options.getGamma().setValue(10.0);
            fbApplied = true;
        } else if (!want && fbApplied) {
            mc.options.getGamma().setValue(origGamma);
            fbApplied = false;
        }
    }

    private boolean apply(MinecraftClient mc, KeyBinding key,
                          boolean wantHeld, boolean wasHeld) {
        if (wantHeld) {
            key.setPressed(true);
            return true;
        }
        if (wasHeld) {
            int code = ((KeyBindingAccessor) key).mcl$boundKey().getCode();
            key.setPressed(InputUtil.isKeyPressed(mc.getWindow(), code));
        }
        return false;
    }
}
