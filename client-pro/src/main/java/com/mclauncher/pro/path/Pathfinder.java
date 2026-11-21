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
    /** Drop ceiling - testing is creative so fall damage doesn't
     * matter; 20 bounds the column scan and covers any sane ledge */
    private static final int MAX_DROP = 20;
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final ClientWorld world;
    /** spots where the walker recently got stuck - repaths pay extra
     * to route near them so retries try different terrain */
    private List<BlockPos> avoid = List.of();

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

    /** Ladder/vine column a player can occupy and climb. */
    private boolean climbable(int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z))
                .isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE);
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
        return find(start, goal, List.of());
    }

    /** Same, but nodes near {@code avoid} spots cost extra so a repath
     * prefers terrain that didn't just fail. */
    List<BlockPos> find(BlockPos start, BlockPos goal,
                        List<BlockPos> avoid) {
        this.avoid = avoid == null ? List.of() : avoid;
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
        // budget scales with straight-line distance: far goals need a
        // wider search before it's fair to say "can't get closer"
        int maxIter = Math.min(300000, Math.max(MAX_ITER,
                (int) (heur(start.getX(), start.getY(), start.getZ(),
                        goal.getX(), goal.getY(), goal.getZ()) * 400)));
        // closest-to-goal node seen: if the full route doesn't fit in
        // the budget, march to this and let the navigator replan
        Node bestNode = s0;
        double bestH = s0.f - s0.g;
        int iter = 0;
        while (!open.isEmpty() && iter++ < maxIter) {
            Node n = open.poll();
            long nk = key(n.x, n.y, n.z);
            if (!closed.add(nk)) {
                continue;
            }
            double h = n.f - n.g;
            if (h < bestH) {
                bestH = h;
                bestNode = n;
            }
            if (n.x == goal.getX() && n.z == goal.getZ()
                    && Math.abs(n.y - goal.getY()) <= 1) {
                ArrayDeque<BlockPos> out = new ArrayDeque<>();
                for (Node c = n; c != null; c = c.parent) {
                    out.addFirst(new BlockPos(c.x, c.y, c.z));
                }
                return new ArrayList<>(out);
            }
            expand(n.x, n.y, n.z, (tx, ty, tz, cost) ->
                    relax(n, tx, ty, tz, n.g + cost, goal, open, best));
        }
        // no full route - return the best partial path so the walker
        // still makes progress toward the goal and replans from there
        if (bestNode != s0) {
            ArrayDeque<BlockPos> out = new ArrayDeque<>();
            for (Node c = bestNode; c != null; c = c.parent) {
                out.addFirst(new BlockPos(c.x, c.y, c.z));
            }
            return new ArrayList<>(out);
        }
        return null;
    }

    /** Movement-edge generator shared by find() and enclosed():
     * walk, step-up-1, drops to MAX_DROP, leaps over 1-3 wide gaps,
     * and climbable columns (ladders/vines) up and down. */
    private interface Sink {
        void accept(int x, int y, int z, double cost);
    }

    private void expand(int x, int y, int z, Sink sink) {
        // inside a climbable column: climb up/down first
        if (climbable(x, y, z)) {
            if (climbable(x, y + 1, z)) {
                sink.accept(x, y + 1, z, 1.2);
            }
            if (climbable(x, y - 1, z)) {
                sink.accept(x, y - 1, z, 1.0);
            }
        }
        for (int[] d : DIRS) {
            boolean diag = d[0] != 0 && d[1] != 0;
            int tx = x + d[0], tz = z + d[1];
            if (diag && !(passable(x + d[0], y, z)
                    && passable(x, y, z + d[1]))) {
                continue; // no corner cutting
            }
            double mul = diag ? 1.4 : 1.0;
            // same level
            if (walkable(tx, y, tz)) {
                sink.accept(tx, y, tz, mul);
                continue;
            }
            // step up 1
            if (walkable(tx, y + 1, tz) && passable(x, y + 2, z)) {
                sink.accept(tx, y + 1, tz, 1.6 * mul);
                continue;
            }
            // grab an adjacent climbable column
            if (!diag && climbable(tx, y, tz)) {
                sink.accept(tx, y, tz, 1.4);
                continue;
            }
            // drop <= MAX_DROP
            boolean done = false;
            if (passable(tx, y, tz) && passable(tx, y + 1, tz)) {
                for (int dy = 1; dy <= MAX_DROP; dy++) {
                    if (walkable(tx, y - dy, tz)) {
                        sink.accept(tx, y - dy, tz,
                                (0.6 + 0.4 * dy) * mul);
                        done = true;
                        break;
                    }
                    if (!passable(tx, y - dy, tz)) {
                        break; // hit a wall on the way down
                    }
                }
            }
            if (done) {
                continue;
            }
            // leap a gap: air blocks with no floor, landing same level
            // or one lower. Diagonals cap at 1 wide - 1.4 is already
            // the realistic sprint-jump diagonal
            int maxGap = diag ? 1 : 3;
            for (int gap = 1; gap <= maxGap; gap++) {
                int lx = x + (gap + 1) * d[0], lz = z + (gap + 1) * d[1];
                boolean air = true;
                for (int i = 1; i <= gap; i++) {
                    int ax = x + i * d[0], az = z + i * d[1];
                    if (!passable(ax, y, az) || !passable(ax, y + 1, az)
                            || ground(ax, y - 1, az)) {
                        air = false;
                        break;
                    }
                }
                if (!air) {
                    break; // further gaps are worse, not better
                }
                if (walkable(lx, y, lz)) {
                    sink.accept(lx, y, lz, (1.6 + 0.6 * gap) * mul);
                    break;
                }
                if (gap == 1 && passable(lx, y, lz)
                        && walkable(lx, y - 1, lz)) {
                    sink.accept(lx, y - 1, lz, 2.2 * mul);
                    break;
                }
            }
        }
    }

    /** Is the start truly enclosed? Flood-fills reachable positions
     * through the same movement edges; under LIMIT nodes = boxed in.
     * Only meaningful after find() returned null. */
    boolean enclosed(BlockPos start, int limit) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen.add(key(start.getX(), start.getY(), start.getZ()));
        q.add(new int[]{start.getX(), start.getY(), start.getZ()});
        while (!q.isEmpty() && seen.size() < limit) {
            int[] c = q.poll();
            expand(c[0], c[1], c[2], (tx, ty, tz, cost) -> {
                if (seen.add(key(tx, ty, tz))) {
                    q.add(new int[]{tx, ty, tz});
                }
            });
        }
        return seen.size() < limit;
    }

    private void relax(Node n, int x, int y, int z, double g,
                       BlockPos goal, PriorityQueue<Node> open,
                       HashMap<Long, Double> best) {
        g += penalty(x, y, z);
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

    /** Extra cost within ~4 blocks of a remembered stuck spot. Flat so
     * the spot stays traversable when there is genuinely no other way. */
    private double penalty(int x, int y, int z) {
        double add = 0;
        for (BlockPos a : avoid) {
            if (Math.abs(y - a.getY()) <= 3) {
                double dx = x - a.getX(), dz = z - a.getZ();
                if (dx * dx + dz * dz <= 16.0) {
                    add = Math.max(add, 6.0);
                }
            }
        }
        return add;
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
