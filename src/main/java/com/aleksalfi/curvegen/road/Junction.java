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
        boolean inTriangle(Vec2 q) { return Junction.inTriangle(q, p, t1, t2); }

    }

    public static boolean inTriangle(Vec2 q, Vec2 a, Vec2 b, Vec2 c) {
        return sameSide(q, a, b, c) && sameSide(q, b, c, a) && sameSide(q, c, a, b);
    }

    private static boolean sameSide(Vec2 q, Vec2 a, Vec2 b, Vec2 ref) {
        Vec2 ab = b.sub(a);
        double cq = ab.x() * (q.z() - a.z()) - ab.z() * (q.x() - a.x());
        double cr = ab.x() * (ref.z() - a.z()) - ab.z() * (ref.x() - a.x());
        return cq * cr >= -1e-9;
    }

    public final RoadNode node;
    public final Vec2 center;
    public final List<Arm> arms = new ArrayList<>();
    public final List<Fillet> fillets = new ArrayList<>();
    public final double cornerRadius;
    public final double box;
    public final double maxHalfTotal;
    public final double maxHalfCarriageway;
    public final double zebraWidth;
    /** Steepest tilt of a junction or roundabout core and of any road's cross slope: 10%. Grades along a road are not capped. */
    public static final double MAX_TILT = 0.10;
    /** Rise per block of the core plane (fit of the through road's grade, capped at {@link #MAX_TILT}). */
    public final Vec2 gradient;

    private Junction(RoadNetwork net, RoadNode node) {
        this.node = node;
        this.center = node.xz();
        double r = 0, maxHalf = 0, maxLane = 0, maxCarriage = 0;
        List<Vec2> dirs = new ArrayList<>();
        List<Double> grades = new ArrayList<>();
        for (RoadLink link : net.linksOf(node.id())) {
            RoadNode other = net.nodes().get(link.other(node.id()));
            if (other == null) continue;
            Vec2 u = other.xz().sub(center);
            if (u.lengthSq() < 1e-6) continue;
            RoadClass cls = net.classOf(link);
            arms.add(new Arm(link, cls, u.normalize(), node.arm(link.id())));
            dirs.add(u.normalize());
            grades.add((other.y() - node.y()) / u.length());
            r = Math.max(r, cls.cornerRadius());
            maxHalf = Math.max(maxHalf, cls.halfTotal());
            maxCarriage = Math.max(maxCarriage, cls.halfCarriageway());
            maxLane = Math.max(maxLane, cls.laneWidth());
        }
        arms.sort(Comparator.comparingDouble(a -> a.u().angle()));
        this.cornerRadius = r;
        this.maxHalfTotal = maxHalf;
        this.maxHalfCarriageway = maxCarriage;
        this.box = maxHalf + r + 1;
        this.zebraWidth = Math.max(3, Math.round(maxLane / 2.0));
        this.gradient = fitPlane(dirs, grades);
        for (int i = 0; i < arms.size(); i++) {
            Fillet f = fillet(arms.get(i), arms.get((i + 1) % arms.size()));
            if (f != null) fillets.add(f);
        }
    }

    public static Junction of(RoadNetwork net, RoadNode node) { return new Junction(net, node); }

    /** Height of the core plane at a point. */
    public double heightAt(Vec2 q) { return node.y() + gradient.dot(q.sub(center)); }

    /**
     * The grade of the core plane. Only <em>through</em> roads count: arms that continue on the far side of
     * the node (within 30° of opposite) are fitted by least squares, so a road crossing a hillside keeps its
     * grade; side roads and ramps are graded to meet that plane beyond the box. Without a through road the
     * core is level. The tilt is capped at {@link #MAX_TILT} so a side road never has to bank much to meet it.
     */
    static Vec2 fitPlane(List<Vec2> dirs, List<Double> grades) {
        boolean[] through = new boolean[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            for (int k = i + 1; k < dirs.size(); k++) {
                if (dirs.get(i).dot(dirs.get(k)) < -0.866) { through[i] = true; through[k] = true; }
            }
        }
        double axx = 1e-3, axz = 0, azz = 1e-3, bx = 0, bz = 0;
        boolean any = false;
        for (int i = 0; i < dirs.size(); i++) {
            if (!through[i]) continue;
            any = true;
            Vec2 u = dirs.get(i);
            double g = grades.get(i);
            axx += u.x() * u.x(); axz += u.x() * u.z(); azz += u.z() * u.z();
            bx += g * u.x(); bz += g * u.z();
        }
        if (!any) return Vec2.ZERO;
        double det = axx * azz - axz * axz;
        if (Math.abs(det) < 1e-12) return Vec2.ZERO;
        Vec2 g = new Vec2((bx * azz - bz * axz) / det, (axx * bz - axz * bx) / det);
        return g.length() > MAX_TILT ? g.normalize().scale(MAX_TILT) : g;
    }

    public double box() { return box; }

    /** Radius of the region this junction repaints: the box plus the stop line just outside it. */
    public double paintRadius() { return box + 2; }

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
        // A disc around the node closes the back of a junction whose arms all leave on one side.
        double best = rel.length() - maxHalfCarriageway;
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
