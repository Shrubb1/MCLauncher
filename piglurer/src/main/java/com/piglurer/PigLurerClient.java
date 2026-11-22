package com.piglurer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

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
    }
}
