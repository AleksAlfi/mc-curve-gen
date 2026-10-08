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

    /** Radius of the painted core around a node: junction box, roundabout ring plus flare, 0 for plain nodes. */
    public static double boxRadius(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT) return Roundabout.of(net, node).flareEnd();
        if (!isJunction(net, node)) return 0;
        return Junction.of(net, node).box();
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
        List<Curve2> curves = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<double[]> solid = new ArrayList<>();
        List<Double> zebras = new ArrayList<>();
        List<Key> keys = new ArrayList<>();
        List<Integer> keyNode = new ArrayList<>(); // node index a key belongs to, -1 for fillet tangent points
        // Pending explicit curves between keys (fillet arcs): index of the key before the arc -> arc
        List<Curve2> arcAfterKey = new ArrayList<>();

        RoadNode first = nodes.get(0);
        Vec2 d0 = nodes.get(1).xz().sub(first.xz()).normalize();
        keys.add(new Key(first.xz(), d0, first.y()));
        keyNode.add(0);
        arcAfterKey.add(null);
        for (int i = 1; i < nodes.size() - 1; i++) {
            RoadNode prev = nodes.get(i - 1), node = nodes.get(i), next = nodes.get(i + 1);
            Vec2 d1 = node.xz().sub(prev.xz()).normalize();
            Vec2 d2 = next.xz().sub(node.xz()).normalize();
            double turn = Math.acos(Math.max(-1, Math.min(1, d1.dot(d2))));
            boolean smooth = node.corner() == CornerStyle.SMOOTH || turn < Math.toRadians(1) || node.filletRadius() < 0.5;
            if (smooth || turn > Math.toRadians(170)) {
                Vec2 t = d1.add(d2);
                keys.add(new Key(node.xz(), t.lengthSq() < 1e-9 ? d2 : t.normalize(), node.y()));
                keyNode.add(i);
                arcAfterKey.add(null);
                if (node.zebra()) zebras.add(null); // resolved after sampling (position of this key)
                continue;
            }
            double len1 = node.xz().distanceTo(prev.xz()), len2 = node.xz().distanceTo(next.xz());
            double r = node.filletRadius();
            double t = r * Math.tan(turn / 2);
            double maxT = 0.45 * Math.min(len1, len2);
            if (t > maxT) { t = maxT; r = t / Math.tan(turn / 2); }
            Vec2 t1 = node.xz().sub(d1.scale(t)), t2 = node.xz().add(d2.scale(t));
            double y1 = node.y() + (prev.y() - node.y()) * (t / len1), y2 = node.y() + (next.y() - node.y()) * (t / len2);
            keys.add(new Key(t1, d1, y1));
            keyNode.add(-1);
            Arc2 arc = Arc2.fromTangent(t1, d1, t2);
            arcAfterKey.add(arc != null ? arc : new Line2(t1, t2));
            keys.add(new Key(t2, d2, y2));
            keyNode.add(-1);
            arcAfterKey.add(null);
            if (r < 3 * cls.laneWidth()) solid.add(new double[]{-1, -1, keys.size() - 2}); // resolved below (arc index)
            if (node.zebra()) zebras.add(null);
        }
        RoadNode last = nodes.get(nodes.size() - 1);
        Vec2 dl = last.xz().sub(nodes.get(nodes.size() - 2).xz()).normalize();
        keys.add(new Key(last.xz(), dl, last.y()));
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
        return new RoadChain(chainId, cls, nodeIds, linkIds, line, startBox, endBox, solidRanges, zebraAlong);
    }

    /** Along-distance of the polyline vertex closest to a point. */
    static double alongOf(Polyline line, Vec2 p) {
        double best = Double.MAX_VALUE, along = 0;
        for (int i = 0; i < line.size; i++) {
            double dx = line.x[i] - p.x(), dz = line.z[i] - p.z();
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; along = line.s[i]; }
        }
        return along;
    }
}
