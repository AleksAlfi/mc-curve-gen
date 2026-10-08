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

    /**
     * The links at a node that shape it. A link that leaves in (nearly) the same direction as a wider link
     * lies inside that road and is ignored: a street drawn along a highway does not make a junction.
     */
    public static List<RoadLink> arms(RoadNetwork net, RoadNode node) {
        List<RoadLink> links = net.linksOf(node.id());
        List<RoadLink> out = new ArrayList<>();
        for (RoadLink l : links) if (!shadowed(net, node, l, links)) out.add(l);
        return out;
    }

    private static boolean shadowed(RoadNetwork net, RoadNode node, RoadLink l, List<RoadLink> links) {
        RoadNode o = net.nodes().get(l.other(node.id()));
        if (o == null) return false;
        Vec2 u = o.xz().sub(node.xz());
        if (u.lengthSq() < 1e-6) return false; // zero-length link: kept so the chain can run through both nodes
        u = u.normalize();
        double half = net.classOf(l).halfTotal();
        for (RoadLink m : links) {
            if (m.id() == l.id()) continue;
            RoadNode om = net.nodes().get(m.other(node.id()));
            if (om == null) continue;
            Vec2 v = om.xz().sub(node.xz());
            if (v.lengthSq() < 1e-6) continue;
            double halfM = net.classOf(m).halfTotal();
            if (halfM < half || (halfM == half && m.id() > l.id())) continue; // the wider (or earlier) road wins
            if (v.normalize().dot(u) > Math.cos(Math.toRadians(5))) return true;
        }
        return false;
    }

    /**
     * Whether a road simply passes through this node: exactly two shaping links that a chain can run along
     * in sequence (same two-way / one-way mode, consistent direction), not a roundabout. The classes may differ.
     */
    public static boolean passThrough(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT) return false;
        List<RoadLink> a = arms(net, node);
        if (a.size() != 2) return false;
        return continues(a.get(0), a.get(1), node.id()) || continues(a.get(1), a.get(0), node.id());
    }

    public static boolean isJunction(RoadNetwork net, RoadNode node) {
        return node.kind() != NodeKind.ROUNDABOUT && arms(net, node).size() >= 3 && Merge.at(net, node) == null && Split.at(net, node) == null && Fork.at(net, node) == null;
    }

    /** Whether a chain travelling along {@code in} into {@code node} may continue along {@code out}: same mode, consistent direction. */
    private static boolean continues(RoadLink in, RoadLink out, int nodeId) {
        if (in.oneWay() != out.oneWay()) return false;
        return !in.oneWay() || (in.arrives(nodeId) && out.leaves(nodeId));
    }

    /**
     * The link a chain continues along after arriving at {@code node} by {@code in}: the other link of a
     * pass-through node, the other through arm of a ramp merge, otherwise none (the chain ends).
     */
    private static RoadLink continuation(RoadNetwork net, RoadNode node, RoadLink in, Set<Integer> usedLinks) {
        if (passThrough(net, node)) {
            for (RoadLink l : arms(net, node)) if (l.id() != in.id() && !usedLinks.contains(l.id()) && continues(in, l, node.id())) return l;
            return null;
        }
        Merge m = Merge.at(net, node);
        if (m == null) return null;
        RoadLink other = in.id() == m.forward().id() ? m.back() : in.id() == m.back().id() ? m.forward() : null;
        return other != null && !usedLinks.contains(other.id()) && continues(in, other, node.id()) ? other : null;
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
        // Deterministic order (lowest ids first) so a chain's orientation does not depend on map iteration.
        List<RoadNode> nodesById = new ArrayList<>(net.nodes().values());
        nodesById.sort(java.util.Comparator.comparingInt(RoadNode::id));
        List<RoadLink> linksById = new ArrayList<>(net.links().values());
        linksById.sort(java.util.Comparator.comparingInt(RoadLink::id));
        // Chains start at nodes that are not pass-through. At a ramp merge only the ramp starts a chain here:
        // the through road runs through the node as one chain, started from wherever it really ends.
        for (RoadNode start : nodesById) {
            if (passThrough(net, start)) continue;
            Merge m = Merge.at(net, start);
            List<RoadLink> arms = new ArrayList<>(m != null ? List.of(m.ramp()) : net.linksOf(start.id()));
            arms.sort(java.util.Comparator.comparingInt(RoadLink::id));
            for (RoadLink first : arms) {
                if (usedLinks.contains(first.id())) continue;
                if (!first.leaves(start.id())) continue; // one-way chains run with the traffic: started from their from-node
                RoadChain chain = walk(net, start, first, usedLinks, chainId);
                if (chain != null) { out.add(chain); chainId++; }
            }
        }
        // Closed loops, and through roads whose both ends are ramp merges.
        for (RoadLink link : linksById) {
            if (usedLinks.contains(link.id())) continue;
            RoadNode start = net.nodes().get(link.from());
            RoadChain chain = walk(net, start, link, usedLinks, chainId);
            if (chain != null) { out.add(chain); chainId++; }
        }
        alignForkDashes(net, out);
        return out;
    }

    /** Lane-line dashes run on through a fork or join: the downstream chains continue the upstream phase. */
    private static void alignForkDashes(RoadNetwork net, List<RoadChain> chains) {
        for (RoadNode n : net.nodes().values()) {
            Fork f = Fork.at(net, n);
            if (f == null) continue;
            RoadChain trunk = null, r = null;
            List<RoadChain> branches = new ArrayList<>();
            for (RoadChain c : chains) {
                if (c.linkIds().contains(f.trunk().id())) trunk = c;
                if (c.linkIds().contains(f.right().id()) || c.linkIds().contains(f.left().id())) branches.add(c);
            }
            if (trunk == null || branches.isEmpty()) continue;
            if (f.diverge()) for (RoadChain b : branches) b.profile().setDashOffset(trunk.profile().dashOffset() + trunk.length());
            else trunk.profile().setDashOffset(branches.get(0).profile().dashOffset() + branches.get(0).length());
        }
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
            if (node.id() == start.id()) break;
            link = continuation(net, node, link, usedLinks);
        }
        return build(net, chainId, nodeIds, linkIds);
    }

    /** A point on the centerline with a known direction of travel. */
    private record Key(Vec2 p, Vec2 tangent, double y) {}

    private static RoadChain build(RoadNetwork net, int chainId, List<Integer> nodeIds, List<Integer> linkIds) {
        if (nodeIds.size() < 2) return null;
        RoadClass cls = net.classOf(net.links().get(linkIds.get(0)));
        boolean oneWay = net.links().get(linkIds.get(0)).oneWay();
        List<RoadClass> linkClasses = new ArrayList<>();
        for (int linkId : linkIds) linkClasses.add(net.classOf(net.links().get(linkId)));
        List<RoadNode> nodes = new ArrayList<>();
        for (int id : nodeIds) nodes.add(net.nodes().get(id));
        // Nodes on top of each other (a zero-length link) contribute nothing: drop the later one.
        for (int i = nodes.size() - 1; i >= 1; i--) {
            if (nodes.get(i).xz().distanceTo(nodes.get(i - 1).xz()) < 0.5 && !(i == nodes.size() - 1 && nodes.size() == 2)) nodes.remove(i);
        }
        if (nodes.size() < 2) return null;
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
        // A ramp chain starts or ends on the nose of its merge, parallel to the through road; the one-way
        // roads of a split start or end half a carriageway beside the two-way centre line, parallel to it.
        Merge mStart = loop ? null : Merge.at(net, first), mEnd = loop ? null : Merge.at(net, last);
        Split sStart = loop ? null : Split.at(net, first), sEnd = loop ? null : Split.at(net, last);
        boolean rampStart = mStart != null && mStart.ramp().id() == linkIds.get(0);
        boolean rampEnd = mEnd != null && mEnd.ramp().id() == linkIds.get(linkIds.size() - 1);
        boolean splitOut = sStart != null && sStart.out().id() == linkIds.get(0);
        boolean splitIn = sEnd != null && sEnd.in().id() == linkIds.get(linkIds.size() - 1);
        Vec2 startPos = rampStart ? mStart.nose() : splitOut ? sStart.outStart() : first.xz();
        Vec2 endPos = rampEnd ? mEnd.nose() : splitIn ? sEnd.inEnd() : last.xz();
        if (rampStart) d0 = mStart.entry() ? mStart.d().scale(-1) : mStart.d();
        if (rampEnd) dl = mEnd.entry() ? mEnd.d() : mEnd.d().scale(-1);
        if (splitOut) d0 = sStart.d();
        if (splitIn) dl = sEnd.d().scale(-1);
        // Forks: a branch starts (diverge) or ends (converge) beside the trunk centre line, parallel to it;
        // the trunk itself runs straight into the node.
        Fork fStart = loop ? null : Fork.at(net, first), fEnd = loop ? null : Fork.at(net, last);
        boolean forkBranchStart = fStart != null && fStart.diverge() && fStart.isBranch(linkIds.get(0));
        boolean forkBranchEnd = fEnd != null && !fEnd.diverge() && fEnd.isBranch(linkIds.get(linkIds.size() - 1));
        boolean forkTrunkStart = fStart != null && !fStart.diverge() && fStart.trunk().id() == linkIds.get(0);
        boolean forkTrunkEnd = fEnd != null && fEnd.diverge() && fEnd.trunk().id() == linkIds.get(linkIds.size() - 1);
        if (forkBranchStart) { startPos = fStart.branchPos(net.links().get(linkIds.get(0))); d0 = fStart.d(); }
        if (forkBranchEnd) { endPos = fEnd.branchPos(net.links().get(linkIds.get(linkIds.size() - 1))); dl = fEnd.d(); }
        if (forkTrunkStart) d0 = fStart.d();
        if (forkTrunkEnd) dl = fEnd.d();
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
        // Fork branches run side by side with the other branch, parallel to the trunk, before they diverge.
        if (forkBranchStart) startStraight = parallelRun(startPos, nodes.get(1).xz(), cls);
        if (forkBranchEnd) endStraight = parallelRun(endPos, nodes.get(nodes.size() - 2).xz(), cls);
        if (loop) keys.add(new Key(first.xz().lerp(nodes.get(1).xz(), 0.5), d0, (first.y() + nodes.get(1).y()) / 2));
        else keys.add(new Key(startPos, d0, first.y()));
        keyNode.add(0);
        arcAfterKey.add(null);
        if (startStraight > 0) {
            // On the core's plane (flat, or tilted on a hill): the arm's own climb starts beyond the box.
            Vec2 edge = startPos.add(d0.scale(startStraight));
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
            if (r < 3 * classOfNode(linkClasses, nodes, i, loop).laneWidth()) solid.add(new double[]{-1, -1, keys.size() - 2}); // resolved below (arc index)
            if (node.zebra()) zebras.add(null);
        }
        if (endStraight > 0) {
            Vec2 edge = endPos.sub(dl.scale(endStraight));
            keys.add(new Key(edge, dl, coreHeightAt(net, last, edge)));
            keyNode.add(-1);
            arcAfterKey.add(null);
        }
        if (loop) keys.add(new Key(nodes.get(nodes.size() - 2).xz().lerp(last.xz(), 0.5), dl, (last.y() + nodes.get(nodes.size() - 2).y()) / 2));
        else keys.add(new Key(endPos, dl, last.y()));
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
        // Along-position of every node and the width profile (class tapers) of the chain.
        double[] nodeAlong = new double[nodeIds.size()];
        for (int i = 0; i < nodeIds.size(); i++) {
            RoadNode n = net.nodes().get(nodeIds.get(i));
            nodeAlong[i] = i == 0 && !loop ? 0 : i == nodeIds.size() - 1 && !loop ? line.totalLength() : alongOf(line, n.xz());
        }
        if (loop) nodeAlong[nodeAlong.length - 1] = line.totalLength();
        TaperProfile profile = new TaperProfile(nodeAlong, linkClasses, oneWay);
        solidRanges.addAll(profile.taperRanges());
        if (forkTrunkStart) forkBlend(profile, fStart, line.totalLength(), true);
        if (forkTrunkEnd) forkBlend(profile, fEnd, line.totalLength(), false);
        if (rampStart) mergeZone(profile, line, mStart, linkClasses.get(0), oneWay, true);
        if (rampEnd) mergeZone(profile, line, mEnd, linkClasses.get(linkClasses.size() - 1), oneWay, false);
        // The two-way road approaching a split gets a solid centre line.
        if (sStart != null && sStart.twoWay().id() == linkIds.get(0)) solidRanges.add(new double[]{0, 4.0 * cls.laneWidth()});
        if (sEnd != null && sEnd.twoWay().id() == linkIds.get(linkIds.size() - 1)) solidRanges.add(new double[]{line.totalLength() - 4.0 * cls.laneWidth(), line.totalLength()});
        addAuxLanes(net, nodeIds, nodes, nodeAlong, line.totalLength(), profile, loop);
        RoadChain.CrossSlope startCross = loop || startStraight <= 0 ? RoadChain.CrossSlope.NONE
                : new RoadChain.CrossSlope(coreGradient(net, first).dot(d0.left()), startStraight);
        RoadChain.CrossSlope endCross = loop || endStraight <= 0 ? RoadChain.CrossSlope.NONE
                : new RoadChain.CrossSlope(coreGradient(net, last).dot(dl.left()), endStraight);
        return new RoadChain(chainId, cls, nodeIds, linkIds, line, startBox, endBox, solidRanges, zebraAlong, corners, startCross, endCross,
                nodeAlong, linkClasses, profile, oneWay);
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

    /** Position, direction and height on the polyline at an along-distance: {x, z, tx, tz, y}. */
    public static double[] pointAt(Polyline line, double s) {
        int i = 0;
        while (i + 2 < line.size && line.s[i + 1] <= s) i++;
        if (i + 1 >= line.size) return new double[]{line.x[line.size - 1], line.z[line.size - 1], 1, 0, line.y[line.size - 1]};
        double ds = line.s[i + 1] - line.s[i];
        double f = ds < 1e-9 ? 0 : Math.max(0, Math.min(1, (s - line.s[i]) / ds));
        double tx = line.x[i + 1] - line.x[i], tz = line.z[i + 1] - line.z[i];
        double tl = Math.hypot(tx, tz);
        if (tl < 1e-9) { tx = 1; tz = 0; } else { tx /= tl; tz /= tl; }
        return new double[]{line.x[i] + (line.x[i + 1] - line.x[i]) * f, line.z[i] + (line.z[i + 1] - line.z[i]) * f, tx, tz, line.y[i] + (line.y[i + 1] - line.y[i]) * f};
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

    /**
     * Acceleration and deceleration lanes for every ramp merge this chain runs through, on the ramp's side.
     * An entry's lane runs forward from the node, an exit's lane ends at it; when an entry's lane would
     * reach the next exit on the same side within twice the merge length, the two become one weaving lane.
     */
    private static void addAuxLanes(RoadNetwork net, List<Integer> nodeIds, List<RoadNode> nodes, double[] nodeAlong, double length,
                                    TaperProfile profile, boolean loop) {
        if (loop) return;
        List<TaperProfile.AuxLane> lanes = new ArrayList<>();
        for (int i = 1; i < nodeIds.size() - 1; i++) {
            RoadNode node = net.nodes().get(nodeIds.get(i));
            Merge m = Merge.at(net, node);
            if (m == null) continue;
            RoadNode nextNode = net.nodes().get(nodeIds.get(i + 1));
            boolean forward = nextNode.xz().sub(node.xz()).dot(m.d()) > 0; // chain runs along the carriageway's travel direction
            boolean rightSide = forward; // the ramp is on the right of d: on the chain's right only when the chain runs along d
            double L = m.highway().mergeLength(), lw = m.highway().laneWidth();
            double s = nodeAlong[i];
            boolean after = m.entry() == forward; // entry lane lies ahead along d; exit lane behind
            if (after) lanes.add(new TaperProfile.AuxLane(rightSide, s, Math.min(length, s + L), false, s + L < length, lw));
            else lanes.add(new TaperProfile.AuxLane(rightSide, Math.max(0, s - L), s, s - L > 0, false, lw));
        }
        lanes.sort(java.util.Comparator.comparingDouble(TaperProfile.AuxLane::start));
        // Weaving: join lanes on the same side whose gap (node to node) is under twice the merge length.
        List<TaperProfile.AuxLane> merged = new ArrayList<>();
        for (TaperProfile.AuxLane a : lanes) {
            TaperProfile.AuxLane prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (prev != null && prev.rightSide() == a.rightSide() && prev.taperOut() && a.taperIn()
                    && a.end() - prev.start() < 2 * Math.max(prev.end() - prev.start(), a.end() - a.start()) + 1e-6) {
                merged.set(merged.size() - 1, new TaperProfile.AuxLane(a.rightSide(), prev.start(), a.end(), prev.taperIn(), a.taperOut(), Math.max(prev.laneWidth(), a.laneWidth())));
            } else {
                merged.add(a);
            }
        }
        for (TaperProfile.AuxLane a : merged) profile.addAuxLane(a);
    }

    /**
     * The merge zone of a ramp chain: the stretch next to the nose where the ramp runs beside the through
     * road, from the nose to where its lane has moved three quarters of a lane away from the auxiliary
     * lane's position. There the ramp has no curb or sidewalk (the gore lies on the inner side), and on the
     * outer side it carries the through road's edge line and shoulder, so the acceleration lane's edge
     * continues seamlessly into the ramp.
     */
    private static void mergeZone(TaperProfile profile, Polyline line, Merge m, RoadClass rampClass, boolean oneWay, boolean atStart) {
        Vec2 centre = m.node().xz(), right = m.right();
        double limit = m.auxCentre() + 0.75 * m.highway().laneWidth();
        double from, to;
        if (atStart) {
            int i = 0;
            while (i + 1 < line.size && new Vec2(line.x[i + 1], line.z[i + 1]).sub(centre).dot(right) <= limit) i++;
            from = 0; to = line.s[i];
        } else {
            int i = line.size - 1;
            while (i > 0 && new Vec2(line.x[i - 1], line.z[i - 1]).sub(centre).dot(right) <= limit) i--;
            from = line.s[i]; to = line.totalLength();
        }
        if (to - from < 1) return;
        // Outer side: the chain's right when it travels along d at the nose, else its left.
        int k = atStart ? 0 : line.size - 1, k2 = atStart ? Math.min(1, line.size - 1) : Math.max(0, line.size - 2);
        Vec2 travel = new Vec2(line.x[Math.max(k, k2)] - line.x[Math.min(k, k2)], line.z[Math.max(k, k2)] - line.z[Math.min(k, k2)]);
        boolean outerRight = travel.dot(m.d()) > 0;
        double[] w = LaneProfile.widthsOf(rampClass, oneWay).clone();
        int n = w.length;
        int[] left = {0, 1, 2, 3}, rightIdx = {n - 1, n - 2, n - 3, n - 4}; // sidewalk, curb, shoulder, edge
        int[] outer = outerRight ? rightIdx : left, inner = outerRight ? left : rightIdx;
        w[inner[0]] = 0; w[inner[1]] = 0; w[inner[2]] = 0; w[inner[3]] = 0;
        w[outer[0]] = 0; w[outer[1]] = 0;
        w[outer[2]] = m.highway().shoulderWidth();
        w[outer[3]] = m.highway().edgeLines() ? 1 : 0;
        profile.addZone(from, to, w);
    }

    /** How far a fork branch runs parallel beside its twin before curving away. */
    static double parallelRun(Vec2 from, Vec2 next, RoadClass cls) {
        double len = from.distanceTo(next);
        return Math.min(Math.max(30, 4.0 * cls.laneWidth()), 0.45 * len);
    }

    /** The trunk of a fork widens to both branches' lanes over the last stretch before (or first after) the node. */
    private static void forkBlend(TaperProfile profile, Fork f, double length, boolean atStart) {
        double full = Math.min(Math.max(30, 4.0 * f.trunkClass().laneWidth()), 0.4 * length);
        double taper = Math.min(Merge.LANE_TAPER, 0.25 * length);
        if (atStart) profile.addBlend(new TaperProfile.Blend(0, full, full + taper, f.trunkTarget()));
        else profile.addBlend(new TaperProfile.Blend(length, length - full, length - full - taper, f.trunkTarget()));
    }

    /** Class of the link arriving at node index {@code i} of the (possibly loop-extended) node list. */
    private static RoadClass classOfNode(List<RoadClass> linkClasses, List<RoadNode> nodes, int i, boolean loop) {
        int link = loop ? i - 1 : i - 1; // the loop list is shifted by one node, so link i-1 arrives at nodes[i] in both cases
        if (loop) link = Math.floorMod(i - 2, linkClasses.size());
        return linkClasses.get(Math.max(0, Math.min(linkClasses.size() - 1, link)));
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
