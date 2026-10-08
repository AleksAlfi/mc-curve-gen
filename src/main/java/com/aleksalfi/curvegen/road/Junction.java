package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Geometry of a junction node: its arms sorted by angle, the corner fillets and the painted box. */
public final class Junction {
    public record Arm(RoadLink link, RoadClass cls, Vec2 u, ArmSettings settings) {
        double halfCarriageway() { return cls.halfCarriageway(); }
        double halfTotal() { return cls.halfTotal(); }
    }

    /** Rounded corner between two adjacent arms: triangle (p, t1, t2) outside the circle (f, r) is asphalt. */
    public record Fillet(Vec2 p, Vec2 t1, Vec2 t2, Vec2 f, double r) {
        boolean inTriangle(Vec2 q) {
            return sameSide(q, p, t1, t2) && sameSide(q, t1, t2, p) && sameSide(q, t2, p, t1);
        }

        private static boolean sameSide(Vec2 q, Vec2 a, Vec2 b, Vec2 ref) {
            Vec2 ab = b.sub(a);
            double cq = ab.x() * (q.z() - a.z()) - ab.z() * (q.x() - a.x());
            double cr = ab.x() * (ref.z() - a.z()) - ab.z() * (ref.x() - a.x());
            return cq * cr >= -1e-9;
        }
    }

    public final RoadNode node;
    public final Vec2 center;
    public final List<Arm> arms = new ArrayList<>();
    public final List<Fillet> fillets = new ArrayList<>();
    public final double cornerRadius;
    public final double box;
    public final double maxHalfTotal;
    public final double zebraWidth;

    private Junction(RoadNetwork net, RoadNode node) {
        this.node = node;
        this.center = node.xz();
        double r = 0, maxHalf = 0, maxLane = 0;
        for (RoadLink link : net.linksOf(node.id())) {
            RoadNode other = net.nodes().get(link.other(node.id()));
            if (other == null) continue;
            Vec2 u = other.xz().sub(center);
            if (u.lengthSq() < 1e-6) continue;
            RoadClass cls = net.classOf(link);
            arms.add(new Arm(link, cls, u.normalize(), node.arm(link.id())));
            r = Math.max(r, cls.cornerRadius());
            maxHalf = Math.max(maxHalf, cls.halfTotal());
            maxLane = Math.max(maxLane, cls.laneWidth());
        }
        arms.sort(Comparator.comparingDouble(a -> a.u().angle()));
        this.cornerRadius = r;
        this.maxHalfTotal = maxHalf;
        this.box = maxHalf + r + 1;
        this.zebraWidth = Math.max(3, Math.round(maxLane / 2.0));
        for (int i = 0; i < arms.size(); i++) {
            Fillet f = fillet(arms.get(i), arms.get((i + 1) % arms.size()));
            if (f != null) fillets.add(f);
        }
    }

    public static Junction of(RoadNetwork net, RoadNode node) { return new Junction(net, node); }

    public double box() { return box; }

    /** Radius of the region this junction repaints. */
    public double paintRadius() { return box + zebraWidth + 2; }

    /** Fillet between arm a and the next arm b counter-clockwise (in angle order). */
    private Fillet fillet(Arm a, Arm b) {
        if (a == b) return null;
        double cross = a.u().x() * b.u().z() - a.u().z() * b.u().x();
        if (Math.abs(cross) < 0.05) return null; // (nearly) parallel arms: no corner
        // Edge of a facing b and edge of b facing a.
        double sideA = Math.signum(b.u().dot(a.u().left()));
        double sideB = Math.signum(a.u().dot(b.u().left()));
        Vec2 pa = center.add(a.u().left().scale(sideA * a.halfCarriageway()));
        Vec2 pb = center.add(b.u().left().scale(sideB * b.halfCarriageway()));
        Vec2 p = intersect(pa, a.u(), pb, b.u());
        if (p == null) return null;
        double psi = Math.acos(Math.max(-1, Math.min(1, a.u().dot(b.u()))));
        if (psi < Math.toRadians(5) || psi > Math.toRadians(175)) return null;
        double r = cornerRadius;
        double t = r / Math.tan(psi / 2);
        Vec2 t1 = p.add(a.u().scale(t)), t2 = p.add(b.u().scale(t));
        Vec2 bis = a.u().add(b.u()).normalize();
        Vec2 f = p.add(bis.scale(r / Math.sin(psi / 2)));
        return new Fillet(p, t1, t2, f, r);
    }

    private static Vec2 intersect(Vec2 p1, Vec2 d1, Vec2 p2, Vec2 d2) {
        double denom = d1.x() * d2.z() - d1.z() * d2.x();
        if (Math.abs(denom) < 1e-9) return null;
        Vec2 w = p2.sub(p1);
        double t = (w.x() * d2.z() - w.z() * d2.x()) / denom;
        return p1.add(d1.scale(t));
    }

    /** Signed distance from q to the junction asphalt (negative inside). */
    public double asphaltDistance(Vec2 q) {
        Vec2 rel = q.sub(center);
        double best = Double.POSITIVE_INFINITY;
        for (Arm a : arms) {
            double s = rel.dot(a.u()), d = rel.dot(a.u().left());
            double dist = s >= 0 ? Math.abs(d) - a.halfCarriageway()
                    : Math.hypot(Math.max(0, Math.abs(d) - a.halfCarriageway()), -s);
            best = Math.min(best, dist);
        }
        for (Fillet f : fillets) {
            if (f.inTriangle(q)) best = Math.min(best, f.r() - q.distanceTo(f.f()));
        }
        return best;
    }

    /** The arm whose strip contains q with the largest along-distance, or null. */
    public Arm armContaining(Vec2 q, boolean fullWidth) {
        Vec2 rel = q.sub(center);
        Arm best = null;
        double bestS = -1;
        for (Arm a : arms) {
            double s = rel.dot(a.u()), d = Math.abs(rel.dot(a.u().left()));
            double half = fullWidth ? a.halfTotal() : a.halfCarriageway();
            if (s >= 0 && d <= half && s > bestS) { bestS = s; best = a; }
        }
        return best;
    }

    /** Nearest arm by angle (for choosing curb/sidewalk materials in corners). */
    public Arm nearestArm(Vec2 q) {
        Vec2 rel = q.sub(center);
        Arm best = arms.isEmpty() ? null : arms.get(0);
        double bestDot = -2;
        for (Arm a : arms) {
            double dot = rel.normalize().dot(a.u());
            if (dot > bestDot) { bestDot = dot; best = a; }
        }
        return best;
    }
}
