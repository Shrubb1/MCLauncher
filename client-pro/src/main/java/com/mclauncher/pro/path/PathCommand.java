package com.mclauncher.pro.path;

import com.mclauncher.client.ClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * "#" chat commands for the pathfinder. Swallowed locally - never sent.
 *   #goto x z        walk to column (y resolved on the ground)
 *   #goto x y z      walk to exact spot
 *   #mine <block>    scan + etherwarp + harvest matching blocks
 *   #mine            same, targeting the block you're looking at
 *   #mine stop       stop mining
 *   #stop / #cancel  abort
 */
public final class PathCommand {

    private PathCommand() {
    }

    /** Returns true when the message was a path command (swallow it). */
    public static boolean handle(String msg) {
        if (!msg.startsWith("#")) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        String[] a = msg.substring(1).trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) {
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "goto" -> {
                if (p == null || a.length < 3) {
                    usage();
                    break;
                }
                try {
                    int x = coord(a[1], p.getX());
                    int z, y;
                    if (a.length >= 4) {
                        y = coord(a[2], p.getY());
                        z = coord(a[3], p.getZ());
                    } else {
                        z = coord(a[2], p.getZ());
                        y = groundY(mc, x, (int) p.getY() + 20, z);
                        if (y == Integer.MIN_VALUE) {
                            say("§c[path] no ground found at that column");
                            break;
                        }
                    }
                    Navigator.start(new BlockPos(x, y, z));
                } catch (NumberFormatException e) {
                    usage();
                }
            }
            case "mine" -> {
                if (a.length >= 2 && (a[1].equalsIgnoreCase("stop")
                        || a[1].equalsIgnoreCase("off"))) {
                    Miner.cancel("§7[mine] stopped");
                    break;
                }
                if (a.length < 2 || a[1].equalsIgnoreCase("this")) {
                    // "#mine" while looking at a block = mine that type
                    if (mc.crosshairTarget
                            instanceof net.minecraft.util.hit.BlockHitResult h
                            && h.getType()
                                    == net.minecraft.util.hit.HitResult
                                            .Type.BLOCK) {
                        var st = mc.world.getBlockState(h.getBlockPos());
                        String id = net.minecraft.registry.Registries.BLOCK
                                .getId(st.getBlock()).getPath();
                        Miner.start(id);
                        say("§7[mine] target: §f"
                                + st.getBlock().getName().getString());
                    } else {
                        say("§c[mine] look at a block first, or name "
                                + "one: #mine diamond_ore");
                    }
                } else {
                    Miner.start(a[1]);
                }
            }
            case "route" -> {
                if (a.length >= 2) {
                    switch (a[1].toLowerCase()) {
                        case "stop", "off" ->
                            RouteRunner.cancel("§7[route] stopped");
                        case "record" -> RouteRecorder.toggle();
                        case "add" -> RouteRecorder.capture();
                        case "clear" -> RouteRecorder.clear();
                        case "dwell" -> {
                            if (a.length >= 3) {
                                try {
                                    int s = Integer.parseInt(a[2]);
                                    if (ClientMod.state != null) {
                                        ClientMod.state.recordDwell =
                                                Math.max(1, Math.min(
                                                        600, s));
                                        say("§7[route] dwell = "
                                                + ClientMod.state
                                                        .recordDwell + "s");
                                    }
                                } catch (NumberFormatException e) {
                                    say("§c[route] #route dwell <s>");
                                }
                            }
                        }
                        default -> RouteRunner.start();
                    }
                } else {
                    RouteRunner.start();
                }
            }
            case "stop", "cancel" -> Navigator.cancel("§7[path] cancelled");
            case "help", "path" -> usage();
            default -> usage();
        }
        return true;
    }

    /** First standable or swimmable height at the column scanning down
     * from top. Water has no collision shape, so feet-fluid + head-air
     * marks the surface - a swim target beats the lakebed. */
    private static int groundY(MinecraftClient mc, int x, int top, int z) {
        for (int y = top; y > mc.world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!mc.world.getFluidState(p).isEmpty()
                    && mc.world.getFluidState(p.up()).isEmpty()) {
                return y; // water surface
            }
            boolean feet = mc.world.getBlockState(p)
                    .getCollisionShape(mc.world, p).isEmpty();
            boolean head = mc.world.getBlockState(p.up())
                    .getCollisionShape(mc.world, p.up()).isEmpty();
            boolean floor = !mc.world.getBlockState(p.down())
                    .getCollisionShape(mc.world, p.down()).isEmpty();
            if (feet && head && floor) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int coord(String s, double cur) {
        if (s.equals("~")) {
            return (int) Math.floor(cur);
        }
        if (s.startsWith("~")) {
            return (int) Math.floor(cur) + Integer.parseInt(s.substring(1));
        }
        return Integer.parseInt(s);
    }

    private static void say(String s) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) {
            p.sendMessage(Text.literal(s), false);
        }
    }

    private static void usage() {
        say("§7[path] #goto x z | #goto x y z | #mine <block> | "
                + "#mine (look at one) | #stop");
    }
}
