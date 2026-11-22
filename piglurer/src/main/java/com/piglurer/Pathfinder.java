package com.piglurer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Grid A* over walkable block columns: step up/down ledges, around walls
// and hedges - replaces straight-line bump steering for longer moves.
public final class Pathfinder {
    private static final int MAX_EXPANDED = 6000;
    private static final int MAX_PATH = 90;
    // level, +1 jump, -1/-2/-3 drops (bigger drops are not pathed)
    private static final int[] DY_ORDER = { 0, 1, -1, -2, -3 };

    private Pathfinder() { }

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g, f;
        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    // block-center waypoints from `from` to `to`, or null if unreachable
    public static List<Vec3> find(ClientLevel w, Vec3 from, Vec3 to) {
        BlockPos start = nearestWalkable(w, BlockPos.containing(from), 2);
        BlockPos goal = nearestWalkable(w, BlockPos.containing(to), 3);
        if (start == null || goal == null) return null;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> nodes = new HashMap<>();
        Node s = new Node(start, null, 0, h(start, goal));
        open.add(s);
        nodes.put(start.asLong(), s);

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_EXPANDED) {
            Node cur = open.poll();
            if (cur.pos.equals(goal)) return rebuild(cur);
            for (BlockPos nb : neighbors(w, cur.pos)) {
                double g = cur.g + cost(cur.pos, nb);
                Node old = nodes.get(nb.asLong());
                if (old != null && old.g <= g) continue;
                Node n = new Node(nb, cur, g, g + h(nb, goal));
                nodes.put(nb.asLong(), n);
                open.add(n);
            }
        }
        return null;
    }

    private static List<Vec3> rebuild(Node end) {
        ArrayDeque<Vec3> out = new ArrayDeque<>();
        for (Node n = end; n != null; n = n.parent) {
            out.addFirst(Vec3.atBottomCenterOf(n.pos));
        }
        List<Vec3> list = new ArrayList<>(out);
        return list.size() > MAX_PATH ? list.subList(0, MAX_PATH) : list;
    }

    private static double h(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ())
                + Math.abs(a.getY() - b.getY()) * 1.5;
    }

    private static double cost(BlockPos a, BlockPos b) {
        boolean diag = a.getX() != b.getX() && a.getZ() != b.getZ();
        double c = diag ? 1.414 : 1.0;
        int dy = b.getY() - a.getY();
        if (dy > 0) c += 0.6;          // jump up a ledge
        else if (dy <= -3) c += 1.0;   // big drop
        else if (dy < 0) c += 0.3;     // small drop
        return c;
    }

    private static List<BlockPos> neighbors(ClientLevel w, BlockPos p) {
        List<BlockPos> out = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx != 0 && dz != 0) {
                    // don't squeeze diagonally past solid corners
                    if (solid(w, p.offset(dx, 0, 0)) || solid(w, p.offset(dx, 1, 0))
                            || solid(w, p.offset(0, 0, dz)) || solid(w, p.offset(0, 1, dz))) {
                        continue;
                    }
                }
                for (int dy : DY_ORDER) {
                    BlockPos c = p.offset(dx, dy, dz);
                    if (walkable(w, c)) {
                        out.add(c);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // a cell is walkable when feet and head are clear of motion-blocking
    // blocks and there's a surface below low enough to stand on
    private static boolean walkable(ClientLevel w, BlockPos p) {
        return !solid(w, p) && !solid(w, p.above()) && standable(w, p.below());
    }

    private static boolean solid(ClientLevel w, BlockPos p) {
        return w.getBlockState(p).blocksMotion();
    }

    // standable = the block's collision top reaches at most 1 block up
    // (slabs/carpet ok, fences/walls at 1.5 are not)
    private static boolean standable(ClientLevel w, BlockPos p) {
        BlockState bs = w.getBlockState(p);
        if (bs.isAir() || bs.liquid()) return false;
        VoxelShape shape = bs.getCollisionShape(w, p);
        if (shape.isEmpty()) {
            return bs.isFaceSturdy(w, p, Direction.UP);
        }
        return shape.max(Direction.Axis.Y) <= 1.0;
    }

    private static BlockPos nearestWalkable(ClientLevel w, BlockPos bp, int radius) {
        if (walkable(w, bp)) return bp;
        for (int dy = -1; dy <= 1; dy++) {
            if (walkable(w, bp.offset(0, dy, 0))) return bp.offset(0, dy, 0);
        }
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = bp.offset(dx, dy, dz);
                        if (walkable(w, c)) return c;
                    }
                }
            }
        }
        return null;
    }
}
