package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Rasterizer;
import com.aleksalfi.curvegen.geom.Vec2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints every road column with a {@link Surface}: chains first (lanes, dashes, edge lines, zebras on
 * plain nodes), then junction and roundabout cores on top. Minecraft-free.
 */
public final class RoadPainter {
    private RoadPainter() {}

    /** One painted column. */
    public static final class Cell {
        public Surface surface;
        public RoadClass cls;
        public double height;
        public double coverage;
        public double ox, oz;
        public int chainId;

        Cell(Surface surface, RoadClass cls, double height, double coverage, double ox, double oz, int chainId) {
            this.surface = surface; this.cls = cls; this.height = height; this.coverage = coverage; this.ox = ox; this.oz = oz; this.chainId = chainId;
        }
    }

    public static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    public static int keyX(long k) { return (int) (k >> 32); }
    public static int keyZ(long k) { return (int) k; }

    public static final int SUPERSAMPLE = 4;
    public static final long MAX_COLUMNS = 4_000_000L;

    public static Map<Long, Cell> paint(RoadNetwork net, List<RoadChain> chains) {
        Map<Long, Cell> cells = new HashMap<>();
        for (RoadChain chain : chains) paintChain(chain, cells);
        for (RoadNode node : net.nodes().values()) {
            if (node.kind() == NodeKind.ROUNDABOUT) paintRoundabout(net, node, chains, cells);
            else if (RoadGeometry.isJunction(net, node)) paintJunction(net, node, chains, cells);
        }
        return cells;
    }

    // ---- chains ----------------------------------------------------------------------------------------

    static int centreDash(RoadClass c) { return Math.max(2, (int) Math.round(c.laneWidth() * 0.67)); }
    static int laneDash(RoadClass c) { return Math.max(2, c.laneWidth() / 3); }
    static double approach(RoadClass c) { return 4.0 * c.laneWidth(); }
    static double zebraWidth(RoadClass c) { return Math.max(3, Math.round(c.laneWidth() / 2.0)); }

    private static void paintChain(RoadChain chain, Map<Long, Cell> cells) {
        RoadClass cls = chain.roadClass();
        LaneProfile profile = LaneProfile.of(cls);
        List<Rasterizer.Column> columns = Rasterizer.rasterize(chain.line(), profile.widths(), SUPERSAMPLE, MAX_COLUMNS);
        double total = chain.length();
        int dash = centreDash(cls), gap = 2 * dash, ldash = laneDash(cls), lgap = 2 * ldash;
        double zw = zebraWidth(cls);
        for (Rasterizer.Column c : columns) {
            if (c.lane() < 0 || c.lane() >= profile.kinds().length) continue;
            LaneKind kind = profile.kinds()[c.lane()];
            double s = c.along();
            Surface surface = switch (kind) {
                case SIDEWALK -> Surface.SIDEWALK;
                case CURB -> Surface.CURB;
                case EDGE -> Surface.LINE;
                case LANE -> Surface.ASPHALT;
                case LANE_LINE -> (s % (ldash + lgap)) < ldash ? Surface.LINE : Surface.ASPHALT;
                case CENTRE -> centreSolid(chain, s, total) || (s % (dash + gap)) < dash ? Surface.LINE : Surface.ASPHALT;
            };
            // Zebra crossing on a plain node: stripes across the carriageway.
            if (kind != LaneKind.SIDEWALK && kind != LaneKind.CURB) {
                for (double z : chain.zebras()) {
                    if (Math.abs(s - z) <= zw / 2) {
                        surface = (Math.floorMod((int) Math.floor(c.lateral() + cls.halfTotal()), 2) == 0) ? Surface.LINE : Surface.ASPHALT;
                    }
                }
            }
            cells.put(key(c.x(), c.z()), new Cell(surface, cls, c.height(), c.coverage(), c.outwardX(), c.outwardZ(), chain.id()));
        }
    }

    /** Solid centre line: multi-lane roads, tight bends, and the approach to a junction or roundabout. */
    private static boolean centreSolid(RoadChain chain, double s, double total) {
        RoadClass cls = chain.roadClass();
        if (cls.lanesPerDirection() >= 2) return true;
        for (double[] r : chain.solidRanges()) if (s >= r[0] && s <= r[1]) return true;
        double app = approach(cls);
        if (chain.startBox() > 0 && s < chain.startBox() + app) return true;
        return chain.endBox() > 0 && total - s < chain.endBox() + app;
    }

    // ---- junctions -------------------------------------------------------------------------------------

    private static int chainOfLink(List<RoadChain> chains, int linkId) {
        for (RoadChain c : chains) if (c.linkIds().contains(linkId)) return c.id();
        return -1;
    }

    private static boolean ownChain(Cell cell, List<RoadChain> chains, List<Junction.Arm> arms) {
        if (cell == null) return false;
        for (Junction.Arm a : arms) if (chainOfLink(chains, a.link().id()) == cell.chainId) return true;
        return false;
    }

    private static void paintJunction(RoadNetwork net, RoadNode node, List<RoadChain> chains, Map<Long, Cell> cells) {
        Junction j = Junction.of(net, node);
        if (j.arms.size() < 3) return;
        double rp = j.paintRadius();
        int minX = (int) Math.floor(j.center.x() - rp), maxX = (int) Math.ceil(j.center.x() + rp);
        int minZ = (int) Math.floor(j.center.z() - rp), maxZ = (int) Math.ceil(j.center.z() + rp);
        double y = node.y();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec2 q = new Vec2(x + 0.5, z + 0.5);
                if (q.distanceTo(j.center) > rp) continue;
                long k = key(x, z);
                Cell existing = cells.get(k);
                Junction.Arm arm = j.armContaining(q, true);
                Vec2 rel = q.sub(j.center);
                if (arm != null) {
                    double s = rel.dot(arm.u()), d = rel.dot(arm.u().left());
                    if (s >= j.box) {
                        // Plain road zone of the arm: keep the chain's painting, add stop / give-way lines.
                        if (existing != null && s < j.box + 1 && d > 0.5 && d <= arm.halfCarriageway() && existing.surface != Surface.CURB && existing.surface != Surface.SIDEWALK) {
                            ArmPriority p = arm.settings().priority();
                            if (p == ArmPriority.STOP) existing.surface = Surface.LINE;
                            else if (p == ArmPriority.GIVE_WAY) existing.surface = Math.floorMod((int) Math.floor(d - 0.5), 2) == 0 ? Surface.LINE : Surface.ASPHALT;
                        }
                        continue;
                    }
                }
                // Junction core: classify from the distance to the asphalt region.
                double dist = j.asphaltDistance(q);
                Junction.Arm ref = arm != null ? arm : j.nearestArm(q);
                RoadClass cls = ref.cls();
                Surface surface;
                if (dist <= 0) surface = Surface.ASPHALT;
                else if (cls.hasSidewalk() && dist <= 1) surface = Surface.CURB;
                else if (cls.hasSidewalk() && dist <= 1 + cls.sidewalkWidth()) surface = Surface.SIDEWALK;
                else surface = Surface.NONE;
                // Zebra on the arm, between the stop line and the junction.
                if (arm != null && surface == Surface.ASPHALT && arm.settings().zebra()) {
                    double s = rel.dot(arm.u()), d = rel.dot(arm.u().left());
                    double zw = j.zebraWidth;
                    if (s >= j.box - zw - 1 && s < j.box - 1 && Math.abs(d) <= arm.halfCarriageway()) {
                        surface = Math.floorMod((int) Math.floor(d + arm.halfCarriageway()), 2) == 0 ? Surface.LINE : Surface.ASPHALT;
                    }
                }
                if (surface == Surface.NONE) {
                    if (ownChain(existing, chains, j.arms)) cells.remove(k);
                    continue;
                }
                double coverage = dist <= 0 ? 1 : 1;
                cells.put(k, new Cell(surface, cls, y, coverage, 0, 0, -1));
            }
        }
    }

    // ---- roundabouts -----------------------------------------------------------------------------------

    private static void paintRoundabout(RoadNetwork net, RoadNode node, List<RoadChain> chains, Map<Long, Cell> cells) {
        Roundabout r = Roundabout.of(net, node);
        double rp = r.paintRadius();
        int minX = (int) Math.floor(r.center.x() - rp), maxX = (int) Math.ceil(r.center.x() + rp);
        int minZ = (int) Math.floor(r.center.z() - rp), maxZ = (int) Math.ceil(r.center.z() + rp);
        double y = node.y();
        RoadClass cls = r.ringClass;
        int ldash = laneDash(cls), lgap = 2 * ldash;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec2 q = new Vec2(x + 0.5, z + 0.5);
                double rad = q.distanceTo(r.center);
                if (rad > rp) continue;
                long k = key(x, z);
                Cell existing = cells.get(k);
                Junction.Arm arm = r.armContaining(q);
                Vec2 rel = q.sub(r.center);
                if (arm != null) {
                    double s = rel.dot(arm.u()), d = rel.dot(arm.u().left());
                    if (s >= r.flareEnd()) continue; // plain road beyond the flare: chain painting stands
                    if (rad >= r.outerRadius) {
                        // Arm mouth: asphalt, with a give-way line across the entry half at the ring.
                        Surface surface = Surface.ASPHALT;
                        if (rad < r.outerRadius + 1 && d > 0.5 && d <= arm.halfCarriageway()) {
                            surface = Math.floorMod((int) Math.floor(d - 0.5), 2) == 0 ? Surface.LINE : Surface.ASPHALT;
                        }
                        cells.put(k, new Cell(surface, arm.cls(), y, 1, 0, 0, -1));
                        continue;
                    }
                }
                Surface surface;
                if (rad < r.innerRadius) surface = Surface.ISLAND;
                else if (rad < r.innerRadius + 1) surface = Surface.LINE;
                else if (rad < r.outerRadius - 1) {
                    surface = Surface.ASPHALT;
                    if (r.lanes == 2) {
                        double laneLine = r.innerRadius + 1 + r.laneWidth;
                        if (rad >= laneLine && rad < laneLine + 1) {
                            double angle = Math.atan2(rel.z(), rel.x());
                            double along = (angle + Math.PI) * (laneLine + 0.5);
                            surface = (along % (ldash + lgap)) < ldash ? Surface.LINE : Surface.ASPHALT;
                        }
                    }
                } else if (rad < r.outerRadius) {
                    surface = arm != null ? Surface.ASPHALT : Surface.LINE; // outer edge line, open at the arms
                } else {
                    double dist = Math.min(rad - r.outerRadius, r.armDistance(q));
                    if (dist <= 0) surface = Surface.ASPHALT;
                    else if (r.sidewalkWidth > 0 && dist <= 1) surface = Surface.CURB;
                    else if (r.sidewalkWidth > 0 && dist <= 1 + r.sidewalkWidth) surface = Surface.SIDEWALK;
                    else surface = Surface.NONE;
                }
                if (surface == Surface.NONE) {
                    if (ownChain(existing, chains, r.arms)) cells.remove(k);
                    continue;
                }
                cells.put(k, new Cell(surface, cls, y, 1, 0, 0, -1));
            }
        }
    }
}
