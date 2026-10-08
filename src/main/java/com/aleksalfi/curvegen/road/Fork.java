package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.List;

/**
 * A one-way road dividing into two one-way roads (diverge), or two joining into one (converge), all lanes
 * kept: before a diverge the trunk widens to carry both branches' lanes side by side and each branch takes
 * its half straight on; a converge is the mirror image. Detected when all three roads are one-way, the two
 * branches lie on either side of the trunk within {@link #MAX_ANGLE_DEG}, and they have the same lane count
 * and width (otherwise the smaller one is a ramp); the Split node kind forces it.
 *
 * @param d     direction of travel through the node
 * @param right the branch on the right of {@code d}
 */
public record Fork(RoadNode node, RoadLink trunk, RoadLink right, RoadLink left, Vec2 d, boolean diverge,
                   RoadClass trunkClass, RoadClass rightClass, RoadClass leftClass) {

    public static final double MAX_ANGLE_DEG = 60;

    public Vec2 rightVec() { return new Vec2(-d.z(), d.x()); }

    public double rightOffset() { return 0.5 + rightClass.oneWayHalf(); }
    public double leftOffset() { return 0.5 + leftClass.oneWayHalf(); }

    /** Where a branch's centre line meets the node. */
    public Vec2 branchPos(RoadLink branch) {
        return branch.id() == right.id() ? node.xz().add(rightVec().scale(rightOffset())) : node.xz().sub(rightVec().scale(leftOffset()));
    }

    public boolean isBranch(int linkId) { return linkId == right.id() || linkId == left.id(); }

    /** The trunk's cross-section at the node: both branches' lanes side by side. */
    public double[] trunkTarget() {
        return LaneProfile.oneWayWidths(trunkClass, rightClass.lanesPerDirection() + leftClass.lanesPerDirection());
    }

    public static Fork at(RoadNetwork net, RoadNode node) {
        if (node.kind() == NodeKind.ROUNDABOUT || node.kind() == NodeKind.JUNCTION) return null;
        List<RoadLink> links = RoadGeometry.arms(net, node);
        if (links.size() != 3) return null;
        for (RoadLink l : links) if (!l.oneWay()) return null;
        int arriving = 0;
        for (RoadLink l : links) if (l.arrives(node.id())) arriving++;
        boolean diverge;
        if (arriving == 1) diverge = true; else if (arriving == 2) diverge = false; else return null;
        RoadLink trunk = null;
        for (RoadLink l : links) if (l.arrives(node.id()) == diverge) trunk = l;
        RoadNode tf = net.nodes().get(trunk.other(node.id()));
        if (tf == null) return null;
        Vec2 d = diverge ? node.xz().sub(tf.xz()) : tf.xz().sub(node.xz());
        if (d.lengthSq() < 1e-6) return null;
        d = d.normalize();
        Vec2 right = new Vec2(-d.z(), d.x());
        RoadLink r = null, l = null;
        double lim = Math.cos(Math.toRadians(MAX_ANGLE_DEG));
        for (RoadLink b : links) {
            if (b.id() == trunk.id()) continue;
            RoadNode bf = net.nodes().get(b.other(node.id()));
            if (bf == null) return null;
            Vec2 ub = diverge ? bf.xz().sub(node.xz()) : node.xz().sub(bf.xz());
            if (ub.lengthSq() < 1e-6) return null;
            ub = ub.normalize();
            if (node.kind() != NodeKind.FORK && ub.dot(d) < lim) return null;
            double side = (diverge ? bf.xz().sub(node.xz()) : bf.xz().sub(node.xz())).dot(right);
            if (side > 0) { if (r != null) return null; r = b; } else { if (l != null) return null; l = b; }
        }
        if (r == null || l == null) return null;
        RoadClass rc = net.classOf(r), lc = net.classOf(l);
        if (node.kind() != NodeKind.FORK && (rc.lanesPerDirection() != lc.lanesPerDirection() || rc.laneWidth() != lc.laneWidth())) return null;
        return new Fork(node, trunk, r, l, d, diverge, net.classOf(trunk), rc, lc);
    }
}
