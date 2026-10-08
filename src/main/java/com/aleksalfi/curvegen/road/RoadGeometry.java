package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Arc2;
import com.aleksalfi.curvegen.geom.CubicBezier2;
import com.aleksalfi.curvegen.geom.Curve2;
import com.aleksalfi.curvegen.geom.Line2;
import com.aleksalfi.curvegen.geom.PathSampler;
import com.aleksalfi.curvegen.geom.PathSegment;
import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.geom.Vec2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Turns a network into road chains with sampled centerlines. Minecraft-free. */
public final class RoadGeometry {
    private RoadGeometry() {}

    public static final double SAMPLE_STEP = 0.5;

    /** Whether a road simply passes through this node (two links of the same class, not a roundabout). */
    public static boolean passThrough(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT) return false;
        List<RoadLink> links = net.linksOf(node.id());
        return links.size() == 2 && links.get(0).classId().equals(links.get(1).classId());
    }

    public static boolean isJunction(RoadNetwork net, RoadNode node) {
        return node.kind() != NodeKind.ROUNDABOUT && net.degree(node.id()) >= 3;
    }

    /** Gradient of a junction's or roundabout's core plane (zero for plain nodes). */
    public static Vec2 coreGradient(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT) return Roundabout.of(net, node).gradient;
        if (isJunction(net, node)) return Junction.of(net, node).gradient;
        return Vec2.ZERO;
    }

    /** Surface height of a junction's or roundabout's core plane at a point (the node's height elsewhere). */
    public static double coreHeightAt(RoadNetwork net, RoadNode node, Vec2 p) {
        if (node.kind() == NodeKind.ROUNDABOUT) return Roundabout.of(net, node).heightAt(p);
        if (isJunction(net, node)) return Junction.of(net, node).heightAt(p);
        return node.y();
    }

    /** Radius of the painted core around a node: junction box, roundabout ring plus flare, 0 for plain nodes. */
    public static double boxRadius(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT) return Roundabout.of(net, node).flareEnd();
        if (!isJunction(net, node)) return 0;
        return Junction.of(net, node).box();
    }

    /**
     * The fillet radius actually used at a pass-through node: the requested one, limited to 45% of the
     * shorter adjacent road. -1 when the node is not a filleted corner (not pass-through, smooth, or straight).
     */
    public static double effectiveFilletRadius(RoadNetwork net, RoadNode node) {
        if (!passThrough(net, node) || node.corner() == CornerStyle.SMOOTH) return -1;
        List<RoadLink> links = net.linksOf(node.id());
        RoadNode prev = net.nodes().get(links.get(0).other(node.id())), next = net.nodes().get(links.get(1).other(node.id()));
        if (prev == null || next == null) return -1;
        Vec2 d1 = node.xz().sub(prev.xz()).normalize(), d2 = next.xz().sub(node.xz()).normalize();
        double turn = Math.acos(Math.max(-1, Math.min(1, d1.dot(d2))));
        if (turn < Math.toRadians(1) || turn > Math.toRadians(170) || node.filletRadius() < 0.5) return -1;
        double len1 = node.xz().distanceTo(prev.xz()), len2 = node.xz().distanceTo(next.xz());
        double t = node.filletRadius() * Math.tan(turn / 2);
        double maxT = 0.45 * Math.min(len1, len2);
        return t > maxT ? maxT / Math.tan(turn / 2) : node.filletRadius();
    }

    public static List<RoadChain> chains(RoadNetwork net) {
        List<RoadChain> out = new ArrayList<>();
        Set<Integer> usedLinks = new HashSet<>();
        int chainId = 0;
        // Chains start at nodes that are not pass-through.
        for (RoadNode start : net.nodes().values()) {
            if (passThrough(net, start)) continue;
            for (RoadLink first : net.linksOf(start.id())) {
                if (usedLinks.contains(first.id())) continue;
                RoadChain chain = walk(net, start, first, usedLinks, chainId);
                if (chain != null) { out.add(chain); chainId++; }
            }
        }
        // Closed loops made only of pass-through nodes.
        for (RoadLink link : net.links().values()) {
            if (usedLinks.contains(link.id())) continue;
            RoadNode start = net.nodes().get(link.a());
            RoadChain chain = walk(net, start, link, usedLinks, chainId);
            if (chain != null) { out.add(chain); chainId++; }
        }
        return out;
    }

    private static RoadChain walk(RoadNetwork net, RoadNode start, RoadLink first, Set<Integer> usedLinks, int chainId) {
        List<Integer> nodeIds = new ArrayList<>();
        List<Integer> linkIds = new ArrayList<>();
        nodeIds.add(start.id());
        RoadLink link = first;
        RoadNode node = start;
        while (link != null) {
            usedLinks.add(link.id());
            linkIds.add(link.id());
            RoadNode next = net.nodes().get(link.other(node.id()));
            if (next == null) return null;
            nodeIds.add(next.id());
            node = next;
            if (!passThrough(net, node) || node.id() == start.id()) break;
            RoadLink cont = null;
            for (RoadLink l : net.linksOf(node.id())) if (l.id() != link.id() && !usedLinks.contains(l.id())) cont = l;
            link = cont;
        }
        return build(net, chainId, nodeIds, linkIds);
    }

    /** A point on the centerline with a known direction of travel. */
    private record Key(Vec2 p, Vec2 tangent, double y) {}

    private static RoadChain build(RoadNetwork net, int chainId, List<Integer> nodeIds, List<Integer> linkIds) {
        if (nodeIds.size() < 2) return null;
        RoadClass cls = net.classOf(net.links().get(linkIds.get(0)));
        List<RoadNode> nodes = new ArrayList<>();
        for (int id : nodeIds) nodes.add(net.nodes().get(id));
        // A closed loop of plain nodes: the start node is a corner like every other, so the chain is laid
        // out as [last-1, first, ..., last-1, first] and both ends are cut at the middle of that shared link.
        boolean loop = nodeIds.size() >= 4 && nodeIds.get(0).equals(nodeIds.get(nodeIds.size() - 1)) && passThrough(net, nodes.get(0));
        if (loop) {
            List<RoadNode> ring = new ArrayList<>();
            ring.add(nodes.get(nodes.size() - 2));
            ring.addAll(nodes);
            nodes = ring;
        }
        List<Curve2> curves = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<double[]> solid = new ArrayList<>();
        List<Double> zebras = new ArrayList<>();
        List<RoadChain.Corner> corners = new ArrayList<>();
        List<Key> keys = new ArrayList<>();
        List<Integer> keyNode = new ArrayList<>(); // node index a key belongs to, -1 for fillet tangent points
        // Pending explicit curves between keys (fillet arcs): index of the key before the arc -> arc
        List<Curve2> arcAfterKey = new ArrayList<>();

        RoadNode first = nodes.get(0);
        RoadNode last = nodes.get(nodes.size() - 1);
        Vec2 d0 = nodes.get(1).xz().sub(first.xz()).normalize();
        Vec2 dl = last.xz().sub(nodes.get(nodes.size() - 2).xz()).normalize();
        // Inside a junction box or roundabout flare the arm must run straight (that is what gets painted there),
        // so a chain leaving such a node gets a straight key at the box edge and corners may only start beyond it.
        double startStraight = loop ? 0 : straightRun(net, first, nodes.get(1));
        double endStraight = loop ? 0 : straightRun(net, last, nodes.get(nodes.size() - 2));
        if (nodes.size() == 2 && startStraight + endStraight > 0) {
            // One link between two junctions: both runs lie on the same leg and must not cross each other.
            double len = first.xz().distanceTo(last.xz());
            double avail = Math.max(0, len - 1);
            if (startStraight + endStraight > avail) {
                double f = avail / (startStraight + endStraight);
                startStraight *= f;
                endStraight *= f;
            }
        }
        if (loop) keys.add(new Key(first.xz().lerp(nodes.get(1).xz(), 0.5), d0, (first.y() + nodes.get(1).y()) / 2));
        else keys.add(new Key(first.xz(), d0, first.y()));
        keyNode.add(0);
        arcAfterKey.add(null);
        if (startStraight > 0) {
            // On the core's plane (flat, or tilted on a hill): the arm's own climb starts beyond the box.
            Vec2 edge = first.xz().add(d0.scale(startStraight));
            keys.add(new Key(edge, d0, coreHeightAt(net, first, edge)));
            keyNode.add(-1);
            arcAfterKey.add(null);
        }
        for (int i = 1; i < nodes.size() - 1; i++) {
            RoadNode prev = nodes.get(i - 1), node = nodes.get(i), next = nodes.get(i + 1);
            Vec2 d1 = node.xz().sub(prev.xz()).normalize();
            Vec2 d2 = next.xz().sub(node.xz()).normalize();
            double turn = Math.acos(Math.max(-1, Math.min(1, d1.dot(d2))));
            double len1 = node.xz().distanceTo(prev.xz()), len2 = node.xz().distanceTo(next.xz());
            // Room for the corner on each leg: half the leg, minus any straight run demanded by a junction neighbour.
            double room1 = 0.45 * len1, room2 = 0.45 * len2;
            if (i == 1 && startStraight > 0) room1 = Math.min(room1, len1 - startStraight - 0.5);
            if (i == nodes.size() - 2 && endStraight > 0) room2 = Math.min(room2, len2 - endStraight - 0.5);
            double maxT = Math.max(0, Math.min(room1, room2));
            boolean smooth = node.corner() == CornerStyle.SMOOTH || turn < Math.toRadians(1) || node.filletRadius() < 0.5;
            if (smooth && maxT < 1) smooth = false; // no room to curve: fall through to a (tiny) fillet
            if (smooth || turn > Math.toRadians(170)) {
                Vec2 t = d1.add(d2);
                keys.add(new Key(node.xz(), t.lengthSq() < 1e-9 ? d2 : t.normalize(), node.y()));
                keyNode.add(i);
                arcAfterKey.add(null);
                if (node.zebra()) zebras.add(null); // resolved after sampling (position of this key)
                continue;
            }
            double r = node.filletRadius();
            double t = r * Math.tan(turn / 2);
            if (t > maxT) { t = maxT; r = turn > 1e-6 ? t / Math.tan(turn / 2) : 0; }
            Vec2 t1 = node.xz().sub(d1.scale(t)), t2 = node.xz().add(d2.scale(t));
            double y1 = node.y() + (prev.y() - node.y()) * (t / len1), y2 = node.y() + (next.y() - node.y()) * (t / len2);
            keys.add(new Key(t1, d1, y1));
            keyNode.add(-1);
            Arc2 arc = Arc2.fromTangent(t1, d1, t2);
            arcAfterKey.add(arc != null ? arc : new Line2(t1, t2));
            if (arc != null) corners.add(new RoadChain.Corner(node.xz(), d1, d2, arc.center(), arc.radius()));
            keys.add(new Key(t2, d2, y2));
            keyNode.add(-1);
            arcAfterKey.add(null);
            if (r < 3 * cls.laneWidth()) solid.add(new double[]{-1, -1, keys.size() - 2}); // resolved below (arc index)
            if (node.zebra()) zebras.add(null);
        }
        if (endStraight > 0) {
            Vec2 edge = last.xz().sub(dl.scale(endStraight));
            keys.add(new Key(edge, dl, coreHeightAt(net, last, edge)));
            keyNode.add(-1);
            arcAfterKey.add(null);
        }
        if (loop) keys.add(new Key(nodes.get(nodes.size() - 2).xz().lerp(last.xz(), 0.5), dl, (last.y() + nodes.get(nodes.size() - 2).y()) / 2));
        else keys.add(new Key(last.xz(), dl, last.y()));
        keyNode.add(nodes.size() - 1);
        arcAfterKey.add(null);

        // Connect keys: explicit arcs, straight lines where both tangents follow the chord, Hermite otherwise.
        List<Double> curveStartAlong = new ArrayList<>();
        List<Integer> curveKey = new ArrayList<>();
        double along = 0;
        ys.add(keys.get(0).y());
        for (int k = 0; k < keys.size() - 1; k++) {
            Key a = keys.get(k), b = keys.get(k + 1);
            Curve2 c = arcAfterKey.get(k);
            if (c == null) {
                Vec2 chord = b.p().sub(a.p());
                double len = chord.length();
                if (len < 1e-6) continue;
                Vec2 cd = chord.scale(1 / len);
                if (a.tangent().dot(cd) > 0.9999 && b.tangent().dot(cd) > 0.9999) c = new Line2(a.p(), b.p());
                else c = new CubicBezier2(a.p(), a.p().add(a.tangent().scale(len / 3)), b.p().sub(b.tangent().scale(len / 3)), b.p());
            }
            curves.add(c);
            curveKey.add(k);
            curveStartAlong.add(along);
            along += c.length();
            ys.add(b.y());
        }
        if (curves.isEmpty()) return null;
        double[] heights = new double[ys.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = ys.get(i);
        PathSegment seg = new PathSegment(curves, heights, false, false);
        Polyline line = PathSampler.sample(List.of(seg), SAMPLE_STEP, PathSampler.Elevation.LINEAR);

        // Vertical curves: replace the piecewise-linear profile by a monotone cubic through the keys, with the
        // grade pinned to the core plane where the chain leaves a junction or roundabout, so a steep arm eases
        // out of the box instead of hinging at its edge.
        {
            int n = curves.size() + 1;
            double[] ks = new double[n], kh = new double[n], fixed = new double[n];
            java.util.Arrays.fill(fixed, Double.NaN);
            for (int ci = 0; ci < curves.size(); ci++) { ks[ci] = curveStartAlong.get(ci); kh[ci] = keys.get(curveKey.get(ci)).y(); }
            ks[n - 1] = along;
            kh[n - 1] = keys.get(keys.size() - 1).y();
            if (!loop) {
                int startKeys = startStraight > 0 ? 2 : 1, endKeys = endStraight > 0 ? 2 : 1;
                double gs = coreGradient(net, first).dot(d0), ge = coreGradient(net, last).dot(dl);
                for (int ci = 0; ci < curves.size() && curveKey.get(ci) < startKeys; ci++) if (boxRadius(net, first) > 0) fixed[ci] = gs;
                for (int ci = 0; ci < curves.size(); ci++) if (curveKey.get(ci) >= keys.size() - endKeys && boxRadius(net, last) > 0) fixed[ci] = ge;
                if (boxRadius(net, last) > 0) fixed[n - 1] = ge;
            }
            smoothHeights(line, ks, kh, fixed);
        }

        // Resolve solid ranges (tight fillets) and zebra positions to along-distances.
        List<double[]> solidRanges = new ArrayList<>();
        for (double[] s : solid) {
            int keyIdx = (int) s[2];
            int ci = curveKey.indexOf(keyIdx);
            if (ci < 0) continue;
            double from = curveStartAlong.get(ci) - cls.laneWidth(), to = curveStartAlong.get(ci) + curves.get(ci).length() + cls.laneWidth();
            solidRanges.add(new double[]{from, to});
        }
        List<Double> zebraAlong = new ArrayList<>();
        int zi = 0;
        for (int i = 1; i < nodes.size() - 1 && zi < zebras.size(); i++) {
            if (!nodes.get(i).zebra()) continue;
            zebraAlong.add(alongOf(line, nodes.get(i).xz()));
            zi++;
        }
        double startBox = boxRadius(net, first), endBox = boxRadius(net, last);
        RoadChain.CrossSlope startCross = loop || startStraight <= 0 ? RoadChain.CrossSlope.NONE
                : new RoadChain.CrossSlope(coreGradient(net, first).dot(d0.left()), startStraight);
        RoadChain.CrossSlope endCross = loop || endStraight <= 0 ? RoadChain.CrossSlope.NONE
                : new RoadChain.CrossSlope(coreGradient(net, last).dot(dl.left()), endStraight);
        return new RoadChain(chainId, cls, nodeIds, linkIds, line, startBox, endBox, solidRanges, zebraAlong, corners, startCross, endCross);
    }

    /**
     * How far a chain must stay straight when it leaves {@code node} towards {@code neighbour}: the painted
     * core radius plus one block, or 0 for plain nodes and legs too short to matter.
     */
    static double straightRun(RoadNetwork net, RoadNode node, RoadNode neighbour) {
        double box = boxRadius(net, node);
        if (box <= 0) return 0;
        double len = node.xz().distanceTo(neighbour.xz());
        double run = box + 1;
        return len > run + 2 ? run : 0;
    }

    /**
     * Rewrites the polyline heights as a monotone cubic Hermite spline (Fritsch–Carlson) through the keys
     * at along-positions {@code ks} with heights {@code kh}. A non-NaN entry in {@code fixed} pins the grade
     * at that key.
     */
    static void smoothHeights(Polyline line, double[] ks, double[] kh, double[] fixed) {
        int n = ks.length;
        if (n < 2) return;
        double[] delta = new double[n - 1], m = new double[n];
        for (int k = 0; k < n - 1; k++) {
            double ds = ks[k + 1] - ks[k];
            delta[k] = ds < 1e-9 ? 0 : (kh[k + 1] - kh[k]) / ds;
        }
        m[0] = delta[0];
        m[n - 1] = delta[n - 2];
        for (int k = 1; k < n - 1; k++) m[k] = delta[k - 1] * delta[k] <= 0 ? 0 : (delta[k - 1] + delta[k]) / 2;
        for (int k = 0; k < n; k++) if (!Double.isNaN(fixed[k])) m[k] = fixed[k];
        // Keep each span monotone: limit the tangents relative to the span's own slope (unless pinned).
        for (int k = 0; k < n - 1; k++) {
            if (Math.abs(delta[k]) < 1e-9) {
                if (Double.isNaN(fixed[k])) m[k] = 0;
                if (Double.isNaN(fixed[k + 1])) m[k + 1] = 0;
                continue;
            }
            double a = m[k] / delta[k], b = m[k + 1] / delta[k];
            double r = a * a + b * b;
            if (r > 9) {
                double t = 3 / Math.sqrt(r);
                if (Double.isNaN(fixed[k])) m[k] = t * a * delta[k];
                if (Double.isNaN(fixed[k + 1])) m[k + 1] = t * b * delta[k];
            }
        }
        int k = 0;
        for (int i = 0; i < line.size; i++) {
            double s = line.s[i];
            while (k < n - 2 && s > ks[k + 1]) k++;
            double ds = ks[k + 1] - ks[k];
            if (ds < 1e-9) { line.y[i] = kh[k + 1]; continue; }
            double t = Math.max(0, Math.min(1, (s - ks[k]) / ds));
            double t2 = t * t, t3 = t2 * t;
            double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
            line.y[i] = h00 * kh[k] + h10 * ds * m[k] + h01 * kh[k + 1] + h11 * ds * m[k + 1];
        }
    }

    /** Height of the polyline vertex closest to a point. */
    public static double heightAt(Polyline line, Vec2 p) {
        double best = Double.MAX_VALUE, y = 0;
        for (int i = 0; i < line.size; i++) {
            double dx = line.x[i] - p.x(), dz = line.z[i] - p.z();
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; y = line.y[i]; }
        }
        return y;
    }

    /** Along-distance of the polyline vertex closest to a point. */
    public static double alongOf(Polyline line, Vec2 p) {
        double best = Double.MAX_VALUE, along = 0;
        for (int i = 0; i < line.size; i++) {
            double dx = line.x[i] - p.x(), dz = line.z[i] - p.z();
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; along = line.s[i]; }
        }
        return along;
    }
}
