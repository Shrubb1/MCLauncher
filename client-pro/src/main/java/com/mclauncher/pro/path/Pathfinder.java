package com.mclauncher.pro.path;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over integer "feet" positions. An edge exists between feet spots
 * when the player can move between them by walking, stepping up one,
 * dropping <= 3, or leaping a 1-wide gap. No digging, bridging, ladders
 * or water in v1 - if the goal needs those, no path is reported.
 */
final class Pathfinder {

    private static final int MAX_ITER = 20000;
    private static final int MAX_DROP = 3;
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final ClientWorld world;

    private record Node(int x, int y, int z, double g, double f,
                        Node parent) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    Pathfinder(ClientWorld world) {
        this.world = world;
    }

    /** Feet position is usable: feet+head passable, floor under. */
    private boolean walkable(int x, int y, int z) {
        return passable(x, y, z) && passable(x, y + 1, z)
                && ground(x, y - 1, z);
    }

    private boolean passable(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = world.getBlockState(p);
        FluidState f = s.getFluidState();
        return s.getCollisionShape(world, p).isEmpty()
                && f.isEmpty() && !dangerous(s);
    }

    private boolean ground(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !world.getBlockState(p).getCollisionShape(world, p)
                .isEmpty();
    }

    private static boolean dangerous(BlockState s) {
        return s.isIn(net.minecraft.registry.tag.BlockTags.FIRE)
                || s.isOf(net.minecraft.block.Blocks.CACTUS)
                || s.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
                || s.isOf(net.minecraft.block.Blocks.SWEET_BERRY_BUSH);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) | (((long) z & 0x3FFFFFF) << 26)
                | (((long) y & 0xFFF) << 52);
    }

    private static double heur(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return Math.max(dx, dz) + Math.min(dx, dz) * 0.414
                + Math.abs(y - gy) * 0.5;
    }

    /** Shortest path of feet positions from start to goal, or null. */
    List<BlockPos> find(BlockPos start, BlockPos goal) {
        if (!walkable(goal.getX(), goal.getY(), goal.getZ())) {
            // aim for a walkable spot next to the goal instead
            BlockPos alt = nearestWalkable(goal);
            if (alt == null) {
                return null;
            }
            goal = alt;
        }
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(n -> n.f));
        HashMap<Long, Double> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Node s0 = new Node(start.getX(), start.getY(), start.getZ(), 0,
                heur(start.getX(), start.getY(), start.getZ(),
                        goal.getX(), goal.getY(), goal.getZ()), null);
        open.add(s0);
        best.put(key(s0.x, s0.y, s0.z), 0.0);
        int iter = 0;
        while (!open.isEmpty() && iter++ < MAX_ITER) {
            Node n = open.poll();
            long nk = key(n.x, n.y, n.z);
            if (!closed.add(nk)) {
                continue;
            }
            if (n.x == goal.getX() && n.z == goal.getZ()
                    && Math.abs(n.y - goal.getY()) <= 1) {
                ArrayDeque<BlockPos> out = new ArrayDeque<>();
                for (Node c = n; c != null; c = c.parent) {
                    out.addFirst(new BlockPos(c.x, c.y, c.z));
                }
                return new ArrayList<>(out);
            }
            for (int[] d : DIRS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                int tx = n.x + d[0], tz = n.z + d[1];
                if (diag && !(passable(n.x + d[0], n.y, n.z)
                        && passable(n.x, n.y, n.z + d[1]))) {
                    continue; // no corner cutting
                }
                double mul = diag ? 1.4 : 1.0;
                // same level
                if (walkable(tx, n.y, tz)) {
                    relax(n, tx, n.y, tz, n.g + mul, goal, open, best);
                    continue;
                }
                // step up 1
                if (walkable(tx, n.y + 1, tz)
                        && passable(n.x, n.y + 2, n.z)) {
                    relax(n, tx, n.y + 1, tz, n.g + 1.6 * mul, goal,
                            open, best);
                    continue;
                }
                // drop <= MAX_DROP
                boolean relaxed = false;
                if (passable(tx, n.y, tz) && passable(tx, n.y + 1, tz)) {
                    for (int dy = 1; dy <= MAX_DROP; dy++) {
                        if (walkable(tx, n.y - dy, tz)) {
                            relax(n, tx, n.y - dy, tz,
                                    n.g + (0.6 + 0.4 * dy) * mul, goal,
                                    open, best);
                            relaxed = true;
                            break;
                        }
                        if (!passable(tx, n.y - dy, tz)) {
                            break; // hit a wall on the way down
                        }
                    }
                }
                if (relaxed) {
                    continue;
                }
                // leap a 1-wide gap: t is air with no floor, t+2*dir lands
                int gx = n.x + 2 * d[0], gz = n.z + 2 * d[1];
                if (passable(tx, n.y, tz) && !ground(tx, n.y - 1, tz)
                        && walkable(gx, n.y, gz)) {
                    relax(n, gx, n.y, gz, n.g + 2.4 * mul, goal, open,
                            best);
                }
            }
        }
        return null;
    }

    private void relax(Node n, int x, int y, int z, double g,
                       BlockPos goal, PriorityQueue<Node> open,
                       HashMap<Long, Double> best) {
        long k = key(x, y, z);
        Double prev = best.get(k);
        if (prev != null && prev <= g) {
            return;
        }
        best.put(k, g);
        open.add(new Node(x, y, z, g,
                g + heur(x, y, z, goal.getX(), goal.getY(), goal.getZ()),
                n));
    }

    private BlockPos nearestWalkable(BlockPos goal) {
        for (int r = 0; r <= 2; r++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }
                        int x = goal.getX() + dx, y = goal.getY() + dy,
                                z = goal.getZ() + dz;
                        if (walkable(x, y, z)) {
                            return new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }
}
