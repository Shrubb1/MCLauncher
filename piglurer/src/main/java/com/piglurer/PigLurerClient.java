package com.piglurer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

public class PigLurerClient implements ClientModInitializer {
    public static PigLureConfig config;
    public static PigLureStateMachine machine;

    @Override
    public void onInitializeClient() {
        config = PigLureConfig.load();
        machine = new PigLureStateMachine(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.level != null) {
                machine.tick(client);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                PigLureCommands.register(dispatcher, machine));

        // buy-script recorder inputs: block right-clicks, entity right-clicks
        // and GUI slot clicks are captured while recordbuy is active
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() && machine.isRecording()) {
                machine.recordBlockClick(hit.getBlockPos(), hit.getDirection());
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && machine.isRecording()) {
                machine.recordEntityClick(entity);
            }
            return InteractionResult.PASS;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> hs) {
                ScreenMouseEvents.beforeMouseClick(hs).register((scr, mouse) -> {
                    if (!machine.isRecording()) return;
                    Slot s = hoveredSlot(hs);
                    if (s != null) {
                        machine.recordGuiClick(hs.getMenu().slots.indexOf(s),
                                mouse.button(), (mouse.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0);
                    }
                });
            }
        });
    }

    // hoveredSlot is protected - read it by name (unobfuscated jar, stable)
    private static java.lang.reflect.Field hoveredSlotField;

    private static Slot hoveredSlot(AbstractContainerScreen<?> hs) {
        try {
            if (hoveredSlotField == null) {
                hoveredSlotField = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
                hoveredSlotField.setAccessible(true);
            }
            return (Slot) hoveredSlotField.get(hs);
        } catch (Throwable t) {
            return null;
        }
    }
}
