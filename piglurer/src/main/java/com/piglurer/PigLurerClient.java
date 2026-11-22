package com.piglurer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PigLurerClient implements ClientModInitializer {
    public static PigLureConfig config;
    public static PigLureStateMachine machine;
    // quips the bot picks from when someone says its name - user-editable
    public static List<String> panicResponses = new ArrayList<>();
    // "go away"-style lines when a player stands in the bot's face
    public static List<String> shooResponses = new ArrayList<>();
    private static Pattern panicKeywordRe;

    @Override
    public void onInitializeClient() {
        config = PigLureConfig.load();
        machine = new PigLureStateMachine(config);
        loadPanicResponses();
        panicKeywordRe = buildPanicPattern();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.level != null) {
                machine.tick(client);
            }
        });

        // panic triggers on chat lines: someone says our IGN -> quip then
        // leave, a bot keyword -> leave instantly. Only while the bot runs.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay || machine == null) return;
            String text = msg.getString();
            machine.onGameChat(text);   // SHINY! orb-charged / token lines
            checkPanic(Minecraft.getInstance(), text);
        });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> {
            if (machine == null) return;
            checkPanic(Minecraft.getInstance(), msg.getString());
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

    // panic check on an incoming chat line. Hypixel format puts the sender
    // before the first ": " - lines without one are system text, not speech
    private static void checkPanic(Minecraft mc, String text) {
        if (mc.player == null || !machine.enabled() || !config.panic.enabled) return;
        int ci = text.indexOf(": ");
        if (ci < 0) return;
        String senderSeg = text.substring(0, ci);
        String ign = mc.player.getName().getString();
        if (senderSeg.endsWith(ign)) return; // our own line echoing back
        String body = text.substring(ci + 2);
        java.util.regex.Matcher m = panicKeywordRe.matcher(body);
        if (m.find()) {
            machine.panicKeyword(mc, m.group());
            return;
        }
        if (body.toLowerCase().contains(ign.toLowerCase())) {
            machine.panicMention(mc);
        }
    }

    // each keyword is a word-start stem: "bot" matches bot/bots/botting
    private static Pattern buildPanicPattern() {
        StringBuilder sb = new StringBuilder("\\b(");
        for (String k : config.panic.keywords) {
            String t = k.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 3) sb.append('|');
            sb.append(Pattern.quote(t));
        }
        sb.append(')');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    // responses live as config/*.txt files (one per line) so they're easy
    // to extend; the jar ships defaults copied on first run
    private static void loadPanicResponses() {
        loadResponseFile("piglurer_responses.txt", panicResponses);
        loadResponseFile("piglurer_shoo.txt", shooResponses);
        if (panicResponses.isEmpty()) {
            panicResponses.addAll(List.of("yo", "yeah?", "sup", "what's up", "sorry was afk-ish"));
        }
        if (shooResponses.isEmpty()) {
            shooResponses.addAll(List.of("go away", "move pls", "personal space", "??"));
        }
        System.out.println("[PigLurer] " + panicResponses.size() + " panic + "
                + shooResponses.size() + " shoo responses loaded");
    }

    private static void loadResponseFile(String name, List<String> out) {
        Path p = FabricLoader.getInstance().getConfigDir().resolve(name);
        try {
            if (!Files.exists(p)) {
                try (var in = PigLurerClient.class.getResourceAsStream("/" + name)) {
                    if (in != null) Files.copy(in, p);
                }
            }
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
                }
            }
        } catch (Exception e) {
            System.err.println("[PigLurer] " + name + " error: " + e.getMessage());
        }
    }
}
