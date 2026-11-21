package com.mclauncher.pro.path;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * "#" chat commands for the pathfinder. Swallowed locally - never sent.
 *   #goto x z        walk to column (y resolved on the ground)
 *   #goto x y z      walk to exact spot
 *   #mine <block>    scan + etherwarp + harvest matching blocks
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
                if (a.length < 2) {
                    usage();
                    break;
                }
                if (a[1].equalsIgnoreCase("stop")
                        || a[1].equalsIgnoreCase("off")) {
                    Miner.cancel("§7[mine] stopped");
                } else {
                    Miner.start(a[1]);
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
        say("§7[path] #goto x z | #goto x y z | #mine <block> | #stop");
    }
}
