package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.List;

/**
 * A ramp joining a multi-lane road at a shallow angle. Two arms of the same class with at least two
 * lanes per direction form the through road; the third, lower-class arm is the ramp. Right-hand traffic:
 * the ramp belongs to the carriageway it lies to the right of, {@code d} being that carriageway's
 * direction of travel. The ramp is an entry when it points back against {@code d}, an exit when it
 * points along it; the node kind can force either or turn the merge off.
 *
 * @param forward the through arm along {@code d}, {@code back} the one against it
 * @param nose    where the ramp's centre line meets the auxiliary lane, abreast of the node
 */
public record Merge(RoadNode node, RoadLink forward, RoadLink back, RoadLink ramp, Vec2 d, boolean entry,
                    RoadClass highway, RoadClass rampClass, Vec2 nose) {

    /** Largest angle between ramp and through road for an automatic merge. */
    public static final double MAX_ANGLE_DEG = 35;
    /** Length of the taper that opens or closes an auxiliary lane. */
    public static final double LANE_TAPER = 20;

    public Vec2 right() { return new Vec2(-d.z(), d.x()); }

    /** Half carriageway of the through road on the ramp's side (a one-way road is centred on its carriageway). */
    public double throughHalf() { return forward.oneWay() ? highway.oneWayHalf() : highway.halfCarriageway(); }

    /** Half carriageway of the ramp. */
    public double rampHalf() { return ramp.oneWay() ? rampClass.oneWayHalf() : rampClass.halfCarriageway(); }

    /** Lateral offset (to the right of {@code d}) of the auxiliary lane's centre. */
    public double auxCentre() { return throughHalf() + 1 + highway.laneWidth() / 2.0; }

    public static Merge at(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT || node.kind() == NodeKind.JUNCTION) return null;
        List<RoadLink> links = net.linksOf(node.id());
        if (links.size() != 3) return null;
        Vec2[] u = new Vec2[3];
        RoadNode[] other = new RoadNode[3];
        for (int i = 0; i < 3; i++) {
            other[i] = net.nodes().get(links.get(i).other(node.id()));
            if (other[i] == null) return null;
            Vec2 v = other[i].xz().sub(node.xz());
            if (v.lengthSq() < 1e-6) return null;
            u[i] = v.normalize();
        }
        // The through pair: same class and direction mode, 2+ lanes, nearly opposite; one-way arms must run
        // through the node (one arriving, one leaving).
        int a = -1, b = -1;
        for (int i = 0; i < 3 && a < 0; i++) {
            for (int k = i + 1; k < 3; k++) {
                RoadLink li = links.get(i), lk = links.get(k);
                RoadClass ci = net.classOf(li), ck = net.classOf(lk);
                if (!ci.id().equals(ck.id()) || ci.lanesPerDirection() < 2 || u[i].dot(u[k]) >= -0.866) continue;
                if (li.oneWay() != lk.oneWay()) continue;
                if (li.oneWay() && li.arrives(node.id()) == lk.arrives(node.id())) continue;
                a = i; b = k; break;
            }
        }
        if (a < 0) return null;
        int r = 3 - a - b;
        RoadLink la = links.get(a), lb = links.get(b), lr = links.get(r);
        RoadClass hw = net.classOf(la), rc = net.classOf(lr);
        boolean forced = node.kind() == NodeKind.ENTRY || node.kind() == NodeKind.EXIT;
        if (!forced) {
            boolean lower = rc.lanesPerDirection() < hw.lanesPerDirection() || rc.laneWidth() < hw.laneWidth() || lr.oneWay();
            if (!lower) return null;
            double axisDot = Math.max(Math.abs(u[r].dot(u[a])), Math.abs(u[r].dot(u[b])));
            if (axisDot < Math.cos(Math.toRadians(MAX_ANGLE_DEG))) return null;
        }
        // The carriageway the ramp lies to the right of (a one-way through road has only one).
        Vec2 d;
        if (la.oneWay()) d = la.leaves(node.id()) ? u[a] : u[b];
        else { d = u[a]; if (u[r].dot(new Vec2(-d.z(), d.x())) < 0) d = u[b]; }
        Vec2 right = new Vec2(-d.z(), d.x());
        if (u[r].dot(right) < 0) return null; // ramp on the wrong side of a one-way road
        // The ramp must really leave the road: its far node has to lie outside the through road's width.
        // A road drawn along the carriageway (both ends on the highway) is not a ramp.
        double rampLen = other[r].xz().sub(node.xz()).length();
        double sideways = Math.abs(u[r].dot(right)) * rampLen;
        if (sideways < hw.halfTotal() + 2) return null;
        RoadLink forward = d == u[a] ? la : lb;
        RoadLink backLink = d == u[a] ? lb : la;
        boolean entry = switch (node.kind()) {
            case ENTRY -> true;
            case EXIT -> false;
            default -> lr.oneWay() ? lr.arrives(node.id()) : u[r].dot(d) < 0;
        };
        Merge m = new Merge(node, forward, backLink, lr, d, entry, hw, rc, Vec2.ZERO);
        return new Merge(node, forward, backLink, lr, d, entry, hw, rc, node.xz().add(right.scale(m.auxCentre())));
    }
}
