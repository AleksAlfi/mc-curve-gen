package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.road.RoadChain;
import com.aleksalfi.curvegen.road.RoadGeometry;
import com.aleksalfi.curvegen.road.RoadLink;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Road Planner's crosshair points at (node, road or ground), what a right-click would do there,
 * and the centre line the road would get: all recomputed each tick on the client so the HUD and the
 * in-world ghost always describe the next click.
 */
public final class RoadAim {
    private RoadAim() {}

    public enum Kind { NONE, GROUND, NODE, ROAD }

    /** {@code id} is the node id for NODE targets and the link id for ROAD targets. */
    public record Target(Kind kind, int id, double x, double y, double z, boolean snapped) {
        public static final Target NONE = new Target(Kind.NONE, -1, 0, 0, 0, false);
        public boolean same(Target o) { return o != null && kind == o.kind && id == o.id; }
    }

    /** What a right-click does for the current target. */
    public enum Action { NOTHING, NO_NETWORK, READ_ONLY, PLACE, CONNECT, SELECT, DESELECT, INSERT, MOVE }

    public static final double NODE_PICK_RADIUS = 1.5;
    public static final double AXIS_SNAP = 1.0;

    public static Target target = Target.NONE;
    public static Action action = Action.NOTHING;
    /** Node being moved (dragged or from the node screen), or -1. */
    public static int movingNode = -1;
    /** The moving node was just created by this client and may not have arrived in a sync yet. */
    public static boolean movingPending;
    private static int pendingTicks;
    /** Centre lines of the road(s) the next click would create or change. */
    public static List<Polyline> ghost = List.of();

    private static Object ghostKey;
    private static RoadNetwork chainsFor;
    private static List<RoadChain> chains = List.of();
    private static double[][] chainBounds = new double[0][];
    private static Map<Integer, Double> pickRadius = Map.of();

    public static void clear() {
        target = Target.NONE;
        action = Action.NOTHING;
        ghost = List.of();
        ghostKey = null;
        movingNode = -1;
        movingPending = false;
        chainsFor = null;
        chains = List.of();
        chainBounds = new double[0][];
        pickRadius = Map.of();
    }

    public static void tick(Minecraft mc, @Nullable RoadNetwork net, RoadPlannerState state) {
        if (net == null) { movingNode = -1; movingPending = false; }
        else if (movingNode >= 0 && !net.nodes().containsKey(movingNode)) {
            // A freshly inserted node shows up with the next sync; give it a moment before giving up.
            if (!movingPending || ++pendingTicks > 60) { movingNode = -1; movingPending = false; }
        } else {
            movingPending = false;
            pendingTicks = 0;
        }
        target = pick(mc, net, state);
        action = actionFor(mc, net, state, target);
        Object key = List.of(RoadClientCache.revision(), target, state.selectedNode(), movingNode, action);
        if (!key.equals(ghostKey)) {
            ghostKey = key;
            ghost = net == null ? List.of() : computeGhost(net, state, target, action);
        }
    }

    // ---- picking -----------------------------------------------------------------------------------------

    private static Target pick(Minecraft mc, @Nullable RoadNetwork net, RoadPlannerState state) {
        HitResult hit = CurvePlannerItem.pickLoaded(mc.player, CurvePlannerItem.LONG_RANGE);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return Target.NONE;
        BlockPos column = bhr.getBlockPos().relative(bhr.getDirection());
        double x = column.getX() + 0.5, y = column.getY() + 1, z = column.getZ() + 0.5;
        if (net == null) return new Target(Kind.GROUND, -1, x, y, z, false);
        refreshChains(net);
        RoadNode node = nearestNode(net, x, z);
        if (node != null && node.id() != movingNode) return new Target(Kind.NODE, node.id(), node.x(), node.y(), node.z(), false);
        Target road = pickRoad(net, x, z);
        if (road != null) return road;
        List<RoadNode> refs = new ArrayList<>();
        if (movingNode >= 0) { for (RoadLink l : net.linksOf(movingNode)) { RoadNode o = net.nodes().get(l.other(movingNode)); if (o != null) refs.add(o); } }
        else if (net.nodes().containsKey(state.selectedNode())) refs.add(net.nodes().get(state.selectedNode()));
        boolean snapped = false;
        if (ClientConfig.axisSnap()) {
            double bestOff = AXIS_SNAP + 1e-9;
            double sx = x, sz = z;
            for (RoadNode ref : refs) {
                double dx = Math.abs(x - ref.x()), dz = Math.abs(z - ref.z());
                if (dx <= AXIS_SNAP && dz > 2 && dx < bestOff) { bestOff = dx; sx = ref.x(); sz = z; snapped = true; }
                if (dz <= AXIS_SNAP && dx > 2 && dz < bestOff) { bestOff = dz; sx = x; sz = ref.z(); snapped = true; }
            }
            x = sx; z = sz;
        }
        return new Target(Kind.GROUND, -1, x, y, z, snapped);
    }

    /** Chains and pick radii are rebuilt only when the client receives a new copy of the network. */
    private static void refreshChains(RoadNetwork net) {
        if (chainsFor == net) return;
        chainsFor = net;
        chains = RoadGeometry.chains(net);
        chainBounds = new double[chains.size()][];
        for (int i = 0; i < chains.size(); i++) {
            Polyline l = chains.get(i).line();
            double pad = Math.max(NODE_PICK_RADIUS, chains.get(i).maxHalf());
            chainBounds[i] = new double[]{l.minX() - pad, l.maxX() + pad, l.minZ() - pad, l.maxZ() + pad, pad};
        }
        Map<Integer, Double> radii = new HashMap<>();
        for (RoadNode n : net.nodes().values()) radii.put(n.id(), Math.max(NODE_PICK_RADIUS, RoadGeometry.boxRadius(net, n)));
        pickRadius = radii;
    }

    /** The node whose pick circle (at least 1.5 blocks, the whole core for junctions and roundabouts) contains the point. */
    @Nullable
    private static RoadNode nearestNode(RoadNetwork net, double x, double z) {
        RoadNode best = null;
        double bestScore = 1;
        for (RoadNode n : net.nodes().values()) {
            double r = pickRadius.getOrDefault(n.id(), NODE_PICK_RADIUS);
            double d = Math.hypot(n.x() - x, n.z() - z) / r;
            if (d <= bestScore) { bestScore = d; best = n; }
        }
        return best;
    }

    /** The road (link) under the point: nearest chain centre line within the road's half width. */
    @Nullable
    private static Target pickRoad(RoadNetwork net, double x, double z) {
        RoadChain bestChain = null;
        int bestSeg = -1;
        double bestT = 0, bestD = Double.MAX_VALUE;
        for (int c = 0; c < chains.size(); c++) {
            double[] b = chainBounds[c];
            if (x < b[0] || x > b[1] || z < b[2] || z > b[3]) continue;
            Polyline l = chains.get(c).line();
            double limit = b[4];
            for (int i = 0; i + 1 < l.size; i++) {
                double ax = l.x[i], az = l.z[i], bx = l.x[i + 1], bz = l.z[i + 1];
                double vx = bx - ax, vz = bz - az;
                double len2 = vx * vx + vz * vz;
                double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / len2));
                double px = ax + vx * t, pz = az + vz * t;
                double d = Math.hypot(px - x, pz - z);
                if (d <= limit && d < bestD) { bestD = d; bestChain = chains.get(c); bestSeg = i; bestT = t; }
            }
        }
        if (bestChain == null) return null;
        Polyline l = bestChain.line();
        double px = l.x[bestSeg] + (l.x[bestSeg + 1] - l.x[bestSeg]) * bestT;
        double pz = l.z[bestSeg] + (l.z[bestSeg + 1] - l.z[bestSeg]) * bestT;
        double py = l.y[bestSeg] + (l.y[bestSeg + 1] - l.y[bestSeg]) * bestT;
        double along = l.s[bestSeg] + (l.s[bestSeg + 1] - l.s[bestSeg]) * bestT;
        // Which link of the chain: count the interior nodes the point has passed.
        int linkIndex = 0;
        for (int k = 1; k < bestChain.nodeIds().size() - 1; k++) {
            RoadNode n = net.nodes().get(bestChain.nodeIds().get(k));
            if (n != null && RoadGeometry.alongOf(l, n.xz()) <= along) linkIndex++;
        }
        linkIndex = Math.min(linkIndex, bestChain.linkIds().size() - 1);
        return new Target(Kind.ROAD, bestChain.linkIds().get(linkIndex), Math.floor(px) + 0.5, Math.rint(py), Math.floor(pz) + 0.5, false);
    }

    // ---- actions ------------------------------------------------------------------------------------------

    public static boolean canEdit(Minecraft mc, RoadNetwork net) {
        return mc.player != null && net.canEdit(mc.player.getUUID(), mc.player.hasPermissions(2));
    }

    private static Action actionFor(Minecraft mc, @Nullable RoadNetwork net, RoadPlannerState state, Target t) {
        if (net == null) return Action.NO_NETWORK;
        boolean edit = canEdit(mc, net);
        if (movingNode >= 0) return t.kind() == Kind.GROUND || t.kind() == Kind.ROAD ? (edit ? Action.MOVE : Action.READ_ONLY) : Action.NOTHING;
        int sel = state.selectedNode();
        return switch (t.kind()) {
            case NONE -> Action.NOTHING;
            case NODE -> sel == t.id() ? Action.DESELECT
                    : sel >= 0 && net.nodes().containsKey(sel) && net.linkBetween(sel, t.id()) == null ? (edit ? Action.CONNECT : Action.READ_ONLY)
                    : Action.SELECT;
            case ROAD -> edit ? Action.INSERT : Action.READ_ONLY;
            case GROUND -> edit ? Action.PLACE : Action.READ_ONLY;
        };
    }

    /** Applies the click to a copy of the network and returns the centre lines of the chains it creates or changes. */
    private static List<Polyline> computeGhost(RoadNetwork net, RoadPlannerState state, Target t, Action a) {
        int sel = state.selectedNode();
        boolean hasSel = net.nodes().containsKey(sel);
        RoadNetwork hyp;
        int watchNode = -1, watchLink = -1;
        switch (a) {
            case PLACE -> {
                if (!hasSel) return List.of();
                hyp = net.addNode(t.x(), t.y(), t.z());
                if (hyp == net) return List.of();
                watchNode = hyp.nextId() - 1;
                hyp = hyp.addLinkContinuing(sel, watchNode);
            }
            case CONNECT -> {
                hyp = net.addLinkContinuing(sel, t.id());
                watchLink = net.nextLinkId();
            }
            case INSERT -> {
                RoadLink link = net.links().get(t.id());
                if (link == null) return List.of();
                hyp = net.insertNode(link.id(), t.x(), t.y(), t.z());
                if (hyp == net) return List.of();
                watchNode = hyp.nextId() - 1;
                if (hasSel && !link.touches(sel)) hyp = hyp.addLinkContinuing(sel, watchNode);
            }
            case MOVE -> {
                RoadNode moving = net.nodes().get(movingNode);
                if (moving == null) return List.of();
                hyp = net.putNode(moving.withPosition(t.x(), t.y(), t.z()));
                watchNode = movingNode;
            }
            default -> { return List.of(); }
        }
        List<Polyline> out = new ArrayList<>();
        for (RoadChain c : RoadGeometry.chains(hyp)) {
            if ((watchNode >= 0 && c.nodeIds().contains(watchNode)) || (watchLink >= 0 && c.linkIds().contains(watchLink))) out.add(c.line());
        }
        return out;
    }

    /** The road class of the hovered link, for the HUD. */
    @Nullable
    public static RoadLink hoveredLink(RoadNetwork net) {
        return target.kind() == Kind.ROAD ? net.links().get(target.id()) : null;
    }
}
