package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Polyline;
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
        if (Math.abs(candidate.lateral - existing.lateral) < 0.75) {
            // Roads running on top of each other: the wider one is the real road.
            double cw = candidate.cls.halfTotal(), ew = existing.cls.halfTotal();
            if (cw != ew) return cw > ew;
        }
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
            else {
                Merge m = Merge.at(net, node);
                if (m != null) paintMerge(m, chains, cells);
                else {
                    Split sp = Split.at(net, node);
                    if (sp != null) paintSplit(sp, chains, cells);
                    else { Fork fk = Fork.at(net, node); if (fk != null) paintFork(fk, chains, cells); }
                }
            }
        }
        return cells;
    }

    // ---- chains ----------------------------------------------------------------------------------------

    static int centreDash(RoadClass c) { return Math.max(2, (int) Math.round(c.laneWidth() * 0.67)); }
    static int laneDash(RoadClass c) { return Math.max(2, c.laneWidth() / 3); }
    static double approach(RoadClass c) { return 4.0 * c.laneWidth(); }
    static double zebraWidth(RoadClass c) { return Math.max(3, Math.round(c.laneWidth() / 2.0)); }

    private static void paintChain(RoadChain chain, CellMap cells) {
        List<Rasterizer.Column> columns = Rasterizer.rasterize(chain.line(), chain.profile(), SUPERSAMPLE, MAX_COLUMNS);
        double total = chain.length();
        for (Rasterizer.Column c : columns) {
            if (c.lane() < 0 || c.lane() >= LaneProfile.KINDS.length) continue;
            LaneKind kind = LaneProfile.KINDS[c.lane()];
            double s = c.along();
            RoadClass cls = chain.classAt(s);
            int dash = centreDash(cls), gap = 2 * dash, ldash = laneDash(cls), lgap = 2 * ldash;
            double ds = s + chain.profile().dashOffset();
            Surface surface = switch (kind) {
                case SIDEWALK -> Surface.SIDEWALK;
                case CURB -> Surface.CURB;
                case SHOULDER, AUX, LANE -> Surface.ASPHALT;
                case EDGE -> Surface.LINE;
                case AUX_LINE -> (s % (3 * ldash)) < 2 * ldash ? Surface.LINE : Surface.ASPHALT; // long dashes, short gaps
                case LANE_LINE -> (ds % (ldash + lgap)) < ldash ? Surface.LINE : Surface.ASPHALT;
                case CENTRE -> chain.oneWay()
                        ? (chain.widthsAt(s)[LaneProfile.CENTRE_INDEX] > 1.5 ? Surface.ASPHALT : (ds % (ldash + lgap)) < ldash ? Surface.LINE : Surface.ASPHALT)
                        : centreSolid(chain, s, total) || (s % (dash + gap)) < dash ? Surface.LINE : Surface.ASPHALT;
            };
            // Zebra crossing on a plain node: stripes across the carriageway.
            if (kind != LaneKind.SIDEWALK && kind != LaneKind.CURB) {
                double zw = zebraWidth(cls);
                for (double z : chain.zebras()) {
                    if (Math.abs(s - z) <= zw / 2) {
                        surface = (Math.floorMod((int) Math.floor(c.lateral() + chain.halfAt(s)), 2) == 0) ? Surface.LINE : Surface.ASPHALT;
                    }
                }
            }
            long k = key(c.x(), c.z());
            double height = c.height() + chain.crossSlopeAt(s) * c.lateral();
            Cell cell = new Cell(surface, cls, height, c.coverage(), c.outwardX(), c.outwardZ(), chain.id(), Math.abs(c.lateral()), c.along());
            if (beats(cell, cells.at(k, cell.height))) cells.put(k, cell);
        }
        for (RoadChain.Corner corner : chain.corners()) mitreInnerCorner(chain, corner, cells);
        if (chain.oneWay()) paintArrows(chain, cells);
    }

    /** Direction arrows in every lane of a one-way road, every {@link #ARROW_SPACING} blocks, where the class asks for them. */
    public static final double ARROW_SPACING = 24;

    private static void paintArrows(RoadChain chain, CellMap cells) {
        Polyline line = chain.line();
        for (double s = 8; s + 6 < chain.length(); s += ARROW_SPACING) {
            RoadClass cls = chain.classAt(s);
            if (!cls.paintArrows()) continue;
            double[] w = chain.widthsAt(s);
            double left = Rasterizer.leftHalf(w, LaneProfile.CENTRE_INDEX);
            double edge = left;
            for (int i = 0; i < w.length; i++) {
                double lo = edge - w[i];
                boolean lane = w[i] >= 3 && (LaneProfile.KINDS[i] == LaneKind.LANE || LaneProfile.KINDS[i] == LaneKind.AUX || (LaneProfile.KINDS[i] == LaneKind.CENTRE && w[i] > 1.5));
                if (lane) arrow(chain, line, s, (edge + lo) / 2, cells);
                edge = lo;
            }
        }
    }

    /** An arrow pointing along the chain: a 1-wide stem of 6 blocks, then head rows 5, 3 and 1 wide (3 and 1 in narrow lanes). */
    private static void arrow(RoadChain chain, Polyline line, double s, double lateral, CellMap cells) {
        double[] w0 = chain.widthsAt(s);
        boolean wide = true;
        {   // the lane this arrow sits in must be at least 6 wide for the 5-wide head row
            double left = Rasterizer.leftHalf(w0, LaneProfile.CENTRE_INDEX), edge = left;
            for (int i = 0; i < w0.length; i++) { double lo = edge - w0[i]; if (lateral <= edge && lateral > lo) { wide = w0[i] >= 6; break; } edge = lo; }
        }
        int[] halves = wide ? new int[]{0, 0, 0, 0, 0, 0, 2, 1, 0} : new int[]{0, 0, 0, 0, 0, 1, 0};
        for (int k = 0; k < halves.length; k++) {
            double[] pt = RoadGeometry.pointAt(line, s + k);
            int half = halves[k];
            for (int o = -half; o <= half; o++) {
                double lat = lateral + o;
                double x = pt[0] + pt[3] * lat, z = pt[1] - pt[2] * lat; // left = (tz, -tx)
                long key = key((int) Math.floor(x), (int) Math.floor(z));
                Cell c = cells.at(key, pt[4]);
                if (c != null && c.chainId == chain.id() && c.surface == Surface.ASPHALT) c.surface = Surface.LINE;
            }
        }
    }

    /**
     * A fillet whose radius is smaller than the road's half width has no inner arc for the outer lanes:
     * the rasterized sidewalk folds over itself. Inside such a corner the lanes beyond the radius are
     * repainted as the plain intersection of the two straight legs (a mitre), which is how a tight curb
     * corner looks in reality.
     */
    private static void mitreInnerCorner(RoadChain chain, RoadChain.Corner corner, CellMap cells) {
        double sCorner = RoadGeometry.alongOf(chain.line(), corner.node());
        RoadClass cls = chain.classAt(sCorner);
        double[] widths = chain.widthsAt(sCorner);
        double half = Rasterizer.half(widths);
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
                LaneKind kind = LaneProfile.kindAt(widths, lateral);
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
        RoadClass cls = chain.classAt(s);
        if (cls.lanesPerDirection() >= 2) return true;
        for (double[] r : chain.solidRanges()) if (s >= r[0] && s <= r[1]) return true;
        double app = approach(cls);
        if (chain.startBox() > 0 && s < chain.startBox() + app) return true;
        return chain.endBox() > 0 && total - s < chain.endBox() + app;
    }

    // ---- ramp merges -----------------------------------------------------------------------------------

    /**
     * The gore of a ramp merge: the wedge between the through road's edge line and the ramp's inner edge,
     * behind the nose of an entry or ahead of the nose of an exit. It is paved and hatched with diagonal
     * stripes, bordered by a solid line on the ramp side (the through road's edge line is already there).
     */
    private static void paintMerge(Merge m, List<RoadChain> chains, CellMap cells) {
        RoadChain ramp = null, hw = null;
        for (RoadChain c : chains) {
            if (c.linkIds().contains(m.ramp().id())) ramp = c;
            if (c.linkIds().contains(m.forward().id())) hw = c;
        }
        if (ramp == null || hw == null) return;
        Vec2 centre = m.node().xz(), d = m.d(), right = m.right();
        double hc = m.throughHalf(), rhc = m.rampHalf();
        double sign = m.entry() ? -1 : 1;
        int reach = 120;
        double[] rampT = new double[reach + 1];
        java.util.Arrays.fill(rampT, Double.NaN);
        Polyline rl = ramp.line();
        for (int i = 0; i < rl.size; i++) {
            Vec2 rel = new Vec2(rl.x[i], rl.z[i]).sub(centre);
            double s = rel.dot(d) * sign, t = rel.dot(right);
            int idx = (int) Math.round(s);
            if (idx >= 0 && idx <= reach && (Double.isNaN(rampT[idx]) || t < rampT[idx])) rampT[idx] = t;
        }
        // The wedge is hatched only while the ramp is still close: once its inner edge is three quarters of a
        // lane beyond the highway's shoulder the roads have visibly separated and the ground between is grass.
        double farEnough = hc + 1 + m.highway().shoulderWidth() + 0.75 * m.highway().laneWidth();
        java.util.Set<Long> done = new java.util.HashSet<>();
        RoadClass cls = m.highway();
        for (int idx = 0; idx <= reach; idx++) {
            if (Double.isNaN(rampT[idx])) { if (idx > 0) break; else continue; }
            double inner = rampT[idx] - rhc;
            if (inner > farEnough) break;
            for (double s = idx - 0.5; s < idx + 0.5; s += 0.5) {
                for (double t = hc + 1; t < inner; t += 0.5) {
                    Vec2 q = centre.add(d.scale(s * sign)).add(right.scale(t));
                    int x = (int) Math.floor(q.x()), z = (int) Math.floor(q.z());
                    long k = key(x, z);
                    if (!done.add(k)) continue;
                    double y = RoadGeometry.heightAt(hw.line(), q);
                    Cell existing = cells.at(k, y);
                    if (existing != null && (existing.surface.raised() || (existing.carriageway() && existing.chainId != ramp.id() && existing.chainId != hw.id()))) continue;
                    boolean border = t >= inner - 1;
                    boolean stripe = Math.floorMod((int) Math.floor(s - 2 * t), 6) < 2; // diagonal bars, 2-block runs
                    Surface surface = border || stripe ? Surface.LINE : Surface.ASPHALT;
                    cells.put(k, new Cell(surface, cls, y, 1, 0, 0, -1));
                }
            }
        }
    }

    /**
     * The gore of a split: the hatched wedge between the two one-way roads ahead of the node, bordered by
     * solid lines, from where the two-way centre line ends until the roads have separated by most of a lane.
     */
    private static void paintSplit(Split sp, List<RoadChain> chains, CellMap cells) {
        RoadChain outChain = null, inChain = null;
        for (RoadChain c : chains) {
            if (c.linkIds().contains(sp.out().id())) outChain = c;
            if (c.linkIds().contains(sp.in().id())) inChain = c;
        }
        if (outChain == null || inChain == null) return;
        Vec2 centre = sp.node().xz(), d = sp.d(), right = sp.right();
        int reach = 120;
        double[] innerOut = edgeByS(outChain, centre, d, right, reach, sp.outSide() > 0);
        double[] innerIn = edgeByS(inChain, centre, d, right, reach, sp.outSide() < 0);
        double farEnough = 0.75 * sp.cls().laneWidth();
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (int idx = 0; idx <= reach; idx++) {
            if (Double.isNaN(innerOut[idx]) || Double.isNaN(innerIn[idx])) { if (idx > 0) break; else continue; }
            double lo = Math.min(innerIn[idx], innerOut[idx]), hi = Math.max(innerIn[idx], innerOut[idx]);
            if (hi - lo > farEnough + 2) break;
            if (hi - lo < 0.5) continue;
            for (double s = idx - 0.5; s < idx + 0.5; s += 0.5) {
                for (double t = lo; t < hi; t += 0.5) {
                    Vec2 q = centre.add(d.scale(s)).add(right.scale(t));
                    int x = (int) Math.floor(q.x()), z = (int) Math.floor(q.z());
                    long k = key(x, z);
                    if (!done.add(k)) continue;
                    double y = RoadGeometry.heightAt(outChain.line(), q);
                    Cell existing = cells.at(k, y);
                    if (existing != null && (existing.surface.raised() || (existing.carriageway() && existing.chainId != outChain.id() && existing.chainId != inChain.id()))) continue;
                    boolean border = t <= lo + 1 || t >= hi - 1;
                    boolean stripe = Math.floorMod((int) Math.floor(s - 2 * t), 6) < 2;
                    cells.put(k, new Cell(border || stripe ? Surface.LINE : Surface.ASPHALT, sp.cls(), y, 1, 0, 0, -1));
                }
            }
        }
    }

    /** The hatched nose between the two branches of a fork, on the side where they are apart. */
    private static void paintFork(Fork f, List<RoadChain> chains, CellMap cells) {
        RoadChain rc = null, lc = null;
        for (RoadChain c : chains) {
            if (c.linkIds().contains(f.right().id())) rc = c;
            if (c.linkIds().contains(f.left().id())) lc = c;
        }
        if (rc == null || lc == null) return;
        Vec2 centre = f.node().xz();
        Vec2 dm = f.diverge() ? f.d() : f.d().scale(-1);
        Vec2 rm = new Vec2(-dm.z(), dm.x());
        boolean rightOnRm = f.branchPos(f.right()).sub(centre).dot(rm) > 0;
        int reach = 120;
        double[] er = edgeByS(rc, centre, dm, rm, reach, rightOnRm);
        double[] el = edgeByS(lc, centre, dm, rm, reach, !rightOnRm);
        double farEnough = 0.75 * f.trunkClass().laneWidth();
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (int idx = 0; idx <= reach; idx++) {
            if (Double.isNaN(er[idx]) || Double.isNaN(el[idx])) { if (idx > 0) break; else continue; }
            double lo = Math.min(er[idx], el[idx]), hi = Math.max(er[idx], el[idx]);
            if (hi - lo > farEnough + 2) break;
            if (hi - lo < 0.5) continue;
            for (double s = idx - 0.5; s < idx + 0.5; s += 0.5) {
                for (double t = lo; t < hi; t += 0.5) {
                    Vec2 q = centre.add(dm.scale(s)).add(rm.scale(t));
                    int x = (int) Math.floor(q.x()), z = (int) Math.floor(q.z());
                    long k = key(x, z);
                    if (!done.add(k)) continue;
                    double y = RoadGeometry.heightAt(rc.line(), q);
                    Cell existing = cells.at(k, y);
                    if (existing != null && existing.surface.raised()) continue;
                    boolean border = t <= lo + 1 || t >= hi - 1;
                    boolean stripe = Math.floorMod((int) Math.floor(s - 2 * t), 6) < 2;
                    cells.put(k, new Cell(border || stripe ? Surface.LINE : Surface.ASPHALT, f.trunkClass(), y, 1, 0, 0, -1));
                }
            }
        }
    }

    /**
     * Inner carriageway edge of a one-way chain leaving (or arriving at) a split, per block of distance
     * ahead of the node along {@code d}, as a lateral offset along {@code right}. NaN where the chain
     * has no sample.
     */
    private static double[] edgeByS(RoadChain chain, Vec2 centre, Vec2 d, Vec2 right, int reach, boolean rightSide) {
        double[] out = new double[reach + 1];
        java.util.Arrays.fill(out, Double.NaN);
        Polyline l = chain.line();
        for (int i = 0; i + 1 < l.size; i++) {
            Vec2 rel = new Vec2(l.x[i], l.z[i]).sub(centre);
            double s = rel.dot(d), t = rel.dot(right);
            int idx = (int) Math.round(s);
            if (idx < 0 || idx > reach) continue;
            // Which of the chain's sides faces the split centre depends on the side it lies on and its direction of travel.
            boolean travelsAlongD = (l.x[i + 1] - l.x[i]) * d.x() + (l.z[i + 1] - l.z[i]) * d.z() > 0;
            boolean leftFacesCentre = rightSide == travelsAlongD;
            double[] w = chain.widthsAt(l.s[i]);
            double edge = rightSide ? t - carriagewayHalf(w, leftFacesCentre) : t + carriagewayHalf(w, leftFacesCentre);
            if (Double.isNaN(out[idx]) || (rightSide ? edge < out[idx] : edge > out[idx])) out[idx] = edge;
        }
        return out;
    }

    /** Carriageway half width (lanes and lines, no shoulder, edge line, curb or sidewalk) on the left or right of the centre line. */
    private static double carriagewayHalf(double[] w, boolean leftSide) {
        int c = LaneProfile.CENTRE_INDEX;
        double t = w[c] / 2;
        for (int i = 4; i < c; i++) t += leftSide ? w[i] : w[w.length - 1 - i]; // AUX, AUX_LINE, lanes and lane lines
        return t;
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
                        boolean entryHalf = arm.link().oneWay() ? arm.link().arrives(node.id()) && Math.abs(d) <= arm.halfCarriageway() : d > 0.5 && d <= arm.halfCarriageway();
                        if (existing != null && s < j.box + 1 && entryHalf && existing.surface != Surface.CURB && existing.surface != Surface.SIDEWALK) {
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
                        boolean entryHalf = arm.link().oneWay() ? arm.link().arrives(node.id()) && Math.abs(d) <= arm.halfCarriageway() : d > 0.5 && d <= arm.halfCarriageway();
                        if (rad < r.outerRadius + 1 && entryHalf) {
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
