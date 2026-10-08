package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.List;

/**
 * Where a two-way road becomes two one-way roads: one leaving the node, one arriving, both within
 * {@link #MAX_ANGLE_DEG} of the two-way road's direction. The one-way centre lines start half a
 * carriageway to either side of the two-way centre line, so each lane runs straight into its road:
 * right-hand traffic puts the leaving road on the right of {@code d}, the direction of travel away from
 * the two-way road.
 *
 * @param offset lateral distance of each one-way centre line from the two-way centre line at the node
 */
public record Split(RoadNode node, RoadLink twoWay, RoadLink out, RoadLink in, Vec2 d, RoadClass cls, double offset, double outSide) {

    public static final double MAX_ANGLE_DEG = 60;

    public Vec2 right() { return new Vec2(-d.z(), d.x()); }

    /** Where the leaving road's centre line starts: on the side it leaves towards (the right, for right-hand traffic). */
    public Vec2 outStart() { return node.xz().add(right().scale(outSide * offset)); }

    /** Where the arriving road's centre line ends: the other side. */
    public Vec2 inEnd() { return node.xz().sub(right().scale(outSide * offset)); }

    public static Split at(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT || node.kind() == NodeKind.JUNCTION) return null;
        if (node.kind() != NodeKind.FORK && node.kind() != NodeKind.AUTO) return null;
        List<RoadLink> links = RoadGeometry.arms(net, node);
        if (links.size() != 3) return null;
        RoadLink two = null, out = null, in = null;
        for (RoadLink l : links) {
            if (!l.oneWay()) { if (two != null) return null; two = l; }
            else if (l.from() == node.id()) { if (out != null) return null; out = l; }
            else { if (in != null) return null; in = l; }
        }
        if (two == null || out == null || in == null) return null;
        RoadNode t = net.nodes().get(two.other(node.id())), o = net.nodes().get(out.other(node.id())), i = net.nodes().get(in.other(node.id()));
        if (t == null || o == null || i == null) return null;
        Vec2 ut = t.xz().sub(node.xz()), uo = o.xz().sub(node.xz()), ui = i.xz().sub(node.xz());
        if (ut.lengthSq() < 1e-6 || uo.lengthSq() < 1e-6 || ui.lengthSq() < 1e-6) return null;
        Vec2 d = ut.normalize().scale(-1);
        if (node.kind() != NodeKind.FORK) {
            double lim = Math.cos(Math.toRadians(MAX_ANGLE_DEG));
            if (uo.normalize().dot(d) < lim || ui.normalize().dot(d) < lim) return null;
        }
        // Each road starts on the side it heads for, so the two never cross; both on one side is a junction.
        Vec2 right = new Vec2(-d.z(), d.x());
        double so = uo.dot(right), si = ui.dot(right);
        if (Math.signum(so) == Math.signum(si) && so != 0) return null;
        double outSide = so > 0 ? 1 : so < 0 ? -1 : si < 0 ? 1 : -1;
        RoadClass cls = net.classOf(two);
        double offset = 0.5 + cls.oneWayHalf();
        return new Split(node, two, out, in, d, cls, offset, outSide);
    }
}
