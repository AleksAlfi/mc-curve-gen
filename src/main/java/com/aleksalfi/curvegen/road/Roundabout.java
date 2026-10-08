package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.ArrayList;
import java.util.List;

/** Geometry of a roundabout node: ring radii, arms and the flare where arms meet the ring. */
public final class Roundabout {
    public final RoadNode node;
    public final Vec2 center;
    public final List<Junction.Arm> arms = new ArrayList<>();
    public final double laneWidth;
    public final double innerRadius, outerRadius, cornerRadius;
    public final int lanes;
    public final int sidewalkWidth, curbLayers;
    public final RoadClass ringClass;

    private Roundabout(RoadNetwork net, RoadNode node) {
        this.node = node;
        this.center = node.xz();
        double lw = 0;
        RoadClass cls = null;
        int sw = 0, curb = 0;
        List<Vec2> dirs = new ArrayList<>();
        List<Double> grades = new ArrayList<>();
        for (RoadLink link : RoadGeometry.arms(net, node)) {
            RoadNode other = net.nodes().get(link.other(node.id()));
            if (other == null) continue;
            Vec2 u = other.xz().sub(center);
            if (u.lengthSq() < 1e-6) continue;
            RoadClass c = net.classOf(link);
            arms.add(new Junction.Arm(link, c, u.normalize(), node.arm(link.id())));
            dirs.add(u.normalize());
            grades.add((other.y() - node.y()) / u.length());
            if (c.laneWidth() > lw) { lw = c.laneWidth(); cls = c; }
            sw = Math.max(sw, c.sidewalkWidth());
            curb = Math.max(curb, c.curbLayers());
        }
        if (cls == null) { cls = net.classOrDefault(net.defaultClass()); lw = cls.laneWidth(); sw = cls.sidewalkWidth(); curb = cls.curbLayers(); }
        this.ringClass = cls;
        this.laneWidth = lw;
        this.lanes = node.roundaboutLanes();
        this.innerRadius = node.roundaboutRadius();
        // inner edge line, lanes with a lane line between them, outer edge line
        this.outerRadius = innerRadius + 1 + lanes * lw + (lanes - 1) + 1;
        this.cornerRadius = Math.max(2, Math.round(lw / 2.0));
        this.sidewalkWidth = sw;
        this.curbLayers = curb;
        this.gradient = Junction.fitPlane(dirs, grades);
    }

    /** Rise per block of the roundabout's plane (fitted to the arms' grades). */
    public final Vec2 gradient;

    /** Height of the roundabout plane at a point. */
    public double heightAt(Vec2 q) { return node.y() + gradient.dot(q.sub(center)); }

    public static Roundabout of(RoadNetwork net, RoadNode node) { return new Roundabout(net, node); }

    /** Along-distance from the centre where an arm's flare ends and the plain road begins. */
    public double flareEnd() { return outerRadius + cornerRadius + 1; }

    public double paintRadius() { return outerRadius + 1 + sidewalkWidth + cornerRadius + 2; }

    /** Half width of an arm's carriageway at along-distance s: a quarter-circle flare of the corner radius at the ring. */
    public double flaredHalf(Junction.Arm arm, double s) {
        double r = cornerRadius;
        double extra;
        if (s <= outerRadius) extra = r;
        else if (s >= outerRadius + r) extra = 0;
        else {
            double dx = outerRadius + r - s; // distance from the fillet centre along the arm
            extra = r - Math.sqrt(Math.max(0, r * r - dx * dx));
        }
        return arm.cls().halfCarriageway() + extra;
    }

    /** Arm whose (flared) carriageway mouth contains q (only at and beyond the ring), or null. */
    public Junction.Arm armContaining(Vec2 q) {
        Vec2 rel = q.sub(center);
        for (Junction.Arm a : arms) {
            double s = rel.dot(a.u()), d = Math.abs(rel.dot(a.u().left()));
            if (s >= outerRadius - 1 && d <= flaredHalf(a, s)) return a;
        }
        return null;
    }

    /** Distance from q to the nearest arm carriageway outside the ring (positive outside). */
    public double armDistance(Vec2 q) {
        Vec2 rel = q.sub(center);
        double best = Double.POSITIVE_INFINITY;
        for (Junction.Arm a : arms) {
            double s = rel.dot(a.u()), d = Math.abs(rel.dot(a.u().left()));
            double half = flaredHalf(a, Math.max(s, 0));
            double dist = s >= 0 ? d - half : Math.hypot(Math.max(0, d - half), -s);
            best = Math.min(best, dist);
        }
        return best;
    }
}
