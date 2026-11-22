package com.piglurer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class PigLureCommands {
    private PigLureCommands() { }

    public static void register(CommandDispatcher<FabricClientCommandSource> d, PigLureStateMachine sm) {
        d.register(ClientCommands.literal("piglure")
            .then(ClientCommands.literal("start").executes(c -> {
                sm.enable(Minecraft.getInstance());
                return 1;
            }))
            .then(ClientCommands.literal("stop").executes(c -> {
                sm.disable(Minecraft.getInstance());
                return 1;
            }))
            .then(ClientCommands.literal("status").executes(c -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) return 0;
                c.getSource().sendFeedback(Component.literal("state=" + sm.state
                        + " orbs=" + Sense.countOrbs(p.getInventory(), PigLurerClient.config.orbItemName)
                        + " pos=" + p.blockPosition().toShortString()));
                return 1;
            }))
            .then(ClientCommands.literal("setshop").executes(c -> {
                // saves your feet position as the walk-to point and the block
                // under your feet (or your crosshair target) as the click block
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) return 0;
                PigLureConfig.ShopCfg s = PigLurerClient.config.shop;
                s.pos = new double[] { p.getX(), p.getY(), p.getZ() };
                HitResult hit = Minecraft.getInstance().hitResult;
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                    s.click = new int[] { bhr.getBlockPos().getX(), bhr.getBlockPos().getY(), bhr.getBlockPos().getZ() };
                } else {
                    s.click = new int[] { (int) Math.floor(p.getX()), (int) Math.floor(p.getY()) - 1, (int) Math.floor(p.getZ()) };
                }
                PigLurerClient.config.save();
                c.getSource().sendFeedback(Component.literal("shop saved pos=" + fmt(s.pos) + " click=" + s.click[0] + "," + s.click[1] + "," + s.click[2]));
                return 1;
            }))
            .then(ClientCommands.literal("setshopclick").executes(c -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) return 0;
                HitResult hit = Minecraft.getInstance().hitResult;
                if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
                    c.getSource().sendFeedback(Component.literal("look at the block to click first"));
                    return 0;
                }
                PigLurerClient.config.shop.click = new int[] { bhr.getBlockPos().getX(), bhr.getBlockPos().getY(), bhr.getBlockPos().getZ() };
                PigLurerClient.config.save();
                c.getSource().sendFeedback(Component.literal("shop click saved"));
                return 1;
            }))
            .then(ClientCommands.literal("setgoal").executes(c -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) return 0;
                PigLurerClient.config.goal.pos = new double[] { p.getX(), p.getY(), p.getZ() };
                PigLurerClient.config.save();
                c.getSource().sendFeedback(Component.literal("goal pos saved (mode=" + PigLurerClient.config.goal.mode + ")"));
                return 1;
            }))
            .then(ClientCommands.literal("goalmode")
                .then(ClientCommands.argument("mode", StringArgumentType.word()).executes(c -> {
                    String mode = StringArgumentType.getString(c, "mode");
                    if (!mode.equals("lastClickPos") && !mode.equals("lastClick") && !mode.equals("fixed") && !mode.equals("entity")) {
                        c.getSource().sendFeedback(Component.literal("usage: /piglure goalmode lastClick|fixed|entity"));
                        return 0;
                    }
                    PigLurerClient.config.goal.mode = mode.equals("lastClick") ? "lastClickPos" : mode;
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("goal mode = " + PigLurerClient.config.goal.mode));
                    return 1;
                })))
            .then(ClientCommands.literal("buyslot")
                .then(ClientCommands.argument("slot", IntegerArgumentType.integer(-1)).executes(c -> {
                    int slot = IntegerArgumentType.getInteger(c, "slot");
                    PigLurerClient.config.shop.buySlot = slot < 0 ? null : slot;
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("buy slot = " + PigLurerClient.config.shop.buySlot));
                    return 1;
                })))
            .then(ClientCommands.literal("navslot")
                .then(ClientCommands.argument("slot", IntegerArgumentType.integer(-1)).executes(c -> {
                    int slot = IntegerArgumentType.getInteger(c, "slot");
                    PigLurerClient.config.shop.navSlot = slot < 0 ? null : slot;
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("nav slot = " + PigLurerClient.config.shop.navSlot));
                    return 1;
                })))
            .then(ClientCommands.literal("buycount")
                .then(ClientCommands.argument("count", IntegerArgumentType.integer(-1)).executes(c -> {
                    int n = IntegerArgumentType.getInteger(c, "count");
                    PigLurerClient.config.shop.buyCount = n < 0 ? null : n;
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("buy count = " + PigLurerClient.config.shop.buyCount));
                    return 1;
                })))
            .then(ClientCommands.literal("herdstyle")
                .then(ClientCommands.argument("style", StringArgumentType.word()).executes(c -> {
                    String style = StringArgumentType.getString(c, "style");
                    if (!style.equals("push") && !style.equals("punch")) {
                        c.getSource().sendFeedback(Component.literal("usage: /piglure herdstyle push|punch"));
                        return 0;
                    }
                    PigLurerClient.config.herdStyle = style;
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("herd style = " + style));
                    return 1;
                })))
            .then(ClientCommands.literal("listgui").executes(c -> {
                // dump the open container's slots so navslot/buyslot numbers
                // are exact - index, count, name
                net.minecraft.client.Minecraft mc = Minecraft.getInstance();
                if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> hs)) {
                    c.getSource().sendFeedback(Component.literal("no container GUI open"));
                    return 0;
                }
                var menu = hs.getMenu();
                int i = 0;
                for (net.minecraft.world.inventory.Slot s : menu.slots) {
                    var st = s.getItem();
                    if (!st.isEmpty()) {
                        c.getSource().sendFeedback(Component.literal(
                                i + ": x" + st.getCount() + " " + st.getHoverName().getString()));
                    }
                    i++;
                }
                return 1;
            }))
            .then(ClientCommands.literal("orbname")
                .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                    PigLurerClient.config.orbItemName = StringArgumentType.getString(c, "name");
                    PigLurerClient.config.save();
                    c.getSource().sendFeedback(Component.literal("orb item name = " + PigLurerClient.config.orbItemName));
                    return 1;
                })))
            .executes(c -> {
                c.getSource().sendFeedback(Component.literal(
                        "/piglure start|stop|status|setshop|setshopclick|setgoal|goalmode|herdstyle|navslot|buyslot|buycount|orbname"));
                return 1;
            })
        );
    }

    private static String fmt(double[] v) {
        return String.format("%.1f,%.1f,%.1f", v[0], v[1], v[2]);
    }
}
