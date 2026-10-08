package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Rasterizer;
import com.aleksalfi.curvegen.geom.Vec2;

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
        /** Distance of the column from its chain's centre line (0 for junction and roundabout cells). */
        public double lateral;
        /** Position along its chain's centre line (0 for junction and roundabout cells). */
        public double along;

        Cell(Surface surface, RoadClass cls, double height, double coverage, double ox, double oz, int chainId) {
            this(surface, cls, height, coverage, ox, oz, chainId, 0, 0);
        }

        Cell(Surface surface, RoadClass cls, double height, double coverage, double ox, double oz, int chainId, double lateral, double along) {
            this.surface = surface; this.cls = cls; this.height = height; this.coverage = coverage; this.ox = ox; this.oz = oz; this.chainId = chainId; this.lateral = lateral; this.along = along;
        }

        boolean carriageway() { return surface == Surface.ASPHALT || surface == Surface.LINE; }
        boolean full() { return coverage >= 15.0 / 16; }
    }

    /**
     * Where two roads overlap (arms meeting at a narrow angle, tight hairpins), the column belongs to the
     * road whose centre line is nearest, except that a full column beats a partial edge column and a
     * carriageway always beats a curb or sidewalk, so a sidewalk never cuts across lanes.
     */
    static boolean beats(Cell candidate, Cell existing) {
        if (existing == null || existing.chainId == candidate.chainId) return true;
        if (candidate.full() != existing.full()) return candidate.full();
        if (candidate.carriageway() != existing.carriageway()) return candidate.carriageway();
        return candidate.lateral <= existing.lateral;
    }

    public static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    public static int keyX(long k) { return (int) (k >> 32); }
    public static int keyZ(long k) { return (int) k; }

    public static final int SUPERSAMPLE = 4;
    public static final long MAX_COLUMNS = 4_000_000L;

    public static CellMap paint(RoadNetwork net, List<RoadChain> chains) {
        CellMap cells = new CellMap();
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

    private static void paintChain(RoadChain chain, CellMap cells) {
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
            long k = key(c.x(), c.z());
            double height = c.height() + chain.crossSlopeAt(s) * c.lateral();
            Cell cell = new Cell(surface, cls, height, c.coverage(), c.outwardX(), c.outwardZ(), chain.id(), Math.abs(c.lateral()), c.along());
            if (beats(cell, cells.at(k, cell.height))) cells.put(k, cell);
        }
        for (RoadChain.Corner corner : chain.corners()) mitreInnerCorner(chain, profile, corner, cells);
    }

    /**
     * A fillet whose radius is smaller than the road's half width has no inner arc for the outer lanes:
     * the rasterized sidewalk folds over itself. Inside such a corner the lanes beyond the radius are
     * repainted as the plain intersection of the two straight legs (a mitre), which is how a tight curb
     * corner looks in reality.
     */
    private static void mitreInnerCorner(RoadChain chain, LaneProfile profile, RoadChain.Corner corner, CellMap cells) {
        RoadClass cls = chain.roadClass();
        double half = cls.halfTotal();
        double r = corner.radius();
        if (r >= half + 0.5) return;
        Vec2 p = corner.node();
        Vec2 n1 = corner.dIn().left(), n2 = corner.dOut().left();
        double side1 = Math.signum(corner.centre().sub(p).dot(n1)), side2 = Math.signum(corner.centre().sub(p).dot(n2));
        double reach = half + r + 2;
        int minX = (int) Math.floor(p.x() - reach), maxX = (int) Math.ceil(p.x() + reach);
        int minZ = (int) Math.floor(p.z() - reach), maxZ = (int) Math.ceil(p.z() + reach);
        double y = chain.line().size > 0 ? RoadGeometry.heightAt(chain.line(), p) : 0;
        // The curb corner is rounded like a junction corner: a circle of the class's corner radius tangent to
        // both inner curb lines (the lines at halfCarriageway from each leg), on the inner side of the wedge.
        double hc = cls.halfCarriageway(), rc = cls.cornerRadius();
        Vec2 u1 = corner.dIn().scale(-1), u2 = corner.dOut(); // both pointing away from the node along the legs
        double psi = Math.acos(Math.max(-1, Math.min(1, u1.dot(u2)))); // wedge angle between the legs
        Vec2 bis = u1.add(u2).normalize();
        Vec2 curbCorner = p.add(bis.scale(hc / Math.sin(psi / 2))); // where the two inner curb lines meet
        Vec2 filletCentre = curbCorner.add(bis.scale(rc / Math.sin(psi / 2)));
        double tangentLen = rc / Math.tan(psi / 2);
        Vec2 ft1 = curbCorner.add(u1.scale(tangentLen)), ft2 = curbCorner.add(u2.scale(tangentLen));
        boolean rounded = psi > Math.toRadians(5) && psi < Math.toRadians(175);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec2 q = new Vec2(x + 0.5, z + 0.5);
                Vec2 rel = q.sub(p);
                if (rel.length() > reach) continue;
                double a1 = rel.dot(n1), a2 = rel.dot(n2);
                if (a1 * side1 < 0 || a2 * side2 < 0) continue; // not in the inner wedge
                // Distance to each straight leg (half-line ending at the node).
                double s1 = rel.dot(corner.dIn()), s2 = rel.dot(corner.dOut());
                double dl1 = s1 <= 0 ? Math.abs(a1) : Math.hypot(a1, s1);
                double dl2 = s2 >= 0 ? Math.abs(a2) : Math.hypot(a2, s2);
                double lateral = Math.min(dl1, dl2);
                // Inside the corner triangle the curb follows the fillet circle: outside the circle is asphalt and
                // just inside it the curb; deeper inside, the sidewalk keeps its straight outer edges.
                if (rounded && Junction.inTriangle(q, curbCorner, ft1, ft2)) {
                    double tri = hc + (rc - q.distanceTo(filletCentre));
                    lateral = tri < hc + 1 ? tri : Math.max(lateral, hc + 1);
                }
                if (lateral < r - 0.5) continue; // the arc still rasterizes this part cleanly
                long k = key(x, z);
                Cell existing = cells.at(k, y);
                LaneKind kind = profile.kindAt(lateral);
                if (kind == null) {
                    if (existing != null && existing.chainId == chain.id()) cells.remove(k, existing);
                    continue;
                }
                Surface surface = switch (kind) {
                    case SIDEWALK -> Surface.SIDEWALK;
                    case CURB -> Surface.CURB;
                    case EDGE -> Surface.LINE;
                    default -> Surface.ASPHALT;
                };
                if (existing != null && existing.chainId != chain.id() && !beats(new Cell(surface, cls, y, 1, 0, 0, chain.id(), lateral, 0), existing)) continue;
                if (surface == Surface.ASPHALT && existing != null && existing.chainId == chain.id() && existing.carriageway()) continue; // keep markings
                double h = existing != null && existing.chainId == chain.id() ? existing.height : y;
                cells.put(k, new Cell(surface, cls, h, 1, 0, 0, chain.id(), lateral, existing != null && existing.chainId == chain.id() ? existing.along : 0));
            }
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

    /**
     * Whether a cell is junk left by an arm's chain inside the node's core: it belongs to one of the arms'
     * chains and lies within {@code core} of this node measured along that chain. Cells of the same chain
     * further along (the start of a bend after the straight run) are kept.
     */
    private static boolean armJunk(Cell cell, List<RoadChain> chains, List<Junction.Arm> arms, int nodeId, double core) {
        if (cell == null) return false;
        for (Junction.Arm a : arms) {
            for (RoadChain c : chains) {
                if (c.id() != cell.chainId || !c.linkIds().contains(a.link().id())) continue;
                double fromNode = Double.MAX_VALUE;
                if (c.nodeIds().get(0) == nodeId) fromNode = cell.along;
                if (c.nodeIds().get(c.nodeIds().size() - 1) == nodeId) fromNode = Math.min(fromNode, c.length() - cell.along);
                return fromNode <= core + 0.5;
            }
        }
        return false;
    }

    private static void paintJunction(RoadNetwork net, RoadNode node, List<RoadChain> chains, CellMap cells) {
        Junction j = Junction.of(net, node);
        if (j.arms.size() < 3) return;
        double rp = j.paintRadius();
        int minX = (int) Math.floor(j.center.x() - rp), maxX = (int) Math.ceil(j.center.x() + rp);
        int minZ = (int) Math.floor(j.center.z() - rp), maxZ = (int) Math.ceil(j.center.z() + rp);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec2 q = new Vec2(x + 0.5, z + 0.5);
                if (q.distanceTo(j.center) > rp) continue;
                double y = j.heightAt(q);
                long k = key(x, z);
                Cell existing = cells.at(k, y);
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
                    // Inside the box the arms' chains cross the core and leave junk between the arms; beyond it
                    // their cells are their own straight strips (or the start of a bend) and must stay.
                    if (armJunk(existing, chains, j.arms, node.id(), j.box)) cells.remove(k, existing);
                    continue;
                }
                cells.put(k, new Cell(surface, cls, y, 1, 0, 0, -1));
            }
        }
    }

    // ---- roundabouts -----------------------------------------------------------------------------------

    private static void paintRoundabout(RoadNetwork net, RoadNode node, List<RoadChain> chains, CellMap cells) {
        Roundabout r = Roundabout.of(net, node);
        double rp = r.paintRadius();
        int minX = (int) Math.floor(r.center.x() - rp), maxX = (int) Math.ceil(r.center.x() + rp);
        int minZ = (int) Math.floor(r.center.z() - rp), maxZ = (int) Math.ceil(r.center.z() + rp);
        RoadClass cls = r.ringClass;
        int ldash = laneDash(cls), lgap = 2 * ldash;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vec2 q = new Vec2(x + 0.5, z + 0.5);
                double rad = q.distanceTo(r.center);
                if (rad > rp) continue;
                double y = r.heightAt(q);
                long k = key(x, z);
                Cell existing = cells.at(k, y);
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
                    if (armJunk(existing, chains, r.arms, node.id(), r.flareEnd())) cells.remove(k, existing);
                    continue;
                }
                cells.put(k, new Cell(surface, cls, y, 1, 0, 0, -1));
            }
        }
    }
}
