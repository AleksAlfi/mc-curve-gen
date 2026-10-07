package com.aleksalfi.curvegen.plan;

import com.aleksalfi.curvegen.geom.Arc2;
import com.aleksalfi.curvegen.geom.CubicBezier2;
import com.aleksalfi.curvegen.geom.Curve2;
import com.aleksalfi.curvegen.geom.Line2;
import com.aleksalfi.curvegen.geom.PathSegment;
import com.aleksalfi.curvegen.geom.Vec2;

import java.util.ArrayList;
import java.util.List;

/** Turns the clicked segment specs of a plan into concrete curves. */
public final class PathBuilder {
    private PathBuilder() {}

    /**
     * @param prevPos  end of the previous segment (null for the first segment)
     * @param prevTan  end tangent of the previous segment (null for the first)
     * @param prevY    surface height at the end of the previous segment
     */
    public static PathSegment buildSegment(SegmentSpec spec, Vec2 prevPos, Vec2 prevTan, double prevY, boolean first, boolean draft) {
        List<PlanPoint> pts = spec.points();
        int base = 0;
        Vec2 start;
        double y0;
        if (first) {
            if (pts.isEmpty()) return null;
            start = pts.get(0).xz();
            y0 = pts.get(0).y();
            base = 1;
        } else {
            start = prevPos;
            y0 = prevY;
        }
        if (pts.size() <= base) return null;
        if (spec.type() == SegmentType.SPLINE) return spline(spec, pts.subList(base, pts.size()), start, y0, prevTan, draft);

        PlanPoint endPt = pts.get(base);
        Vec2 end = endPt.xz();
        double y1 = endPt.y();
        if (start.distanceTo(end) < 1e-6) return null;
        Vec2 extra1 = pts.size() > base + 1 ? pts.get(base + 1).xz() : null;
        Vec2 extra2 = pts.size() > base + 2 ? pts.get(base + 2).xz() : null;

        boolean hasPrev = prevTan != null;
        Vec2 heading = resolveHeading(spec, start, end, prevTan);

        List<Curve2> curves = new ArrayList<>();
        switch (spec.type()) {
            case STRAIGHT -> curves.add(new Line2(start, end));
            case ARC -> {
                double chord = start.distanceTo(end);
                Curve2 arc = null;
                switch (spec.arcMode()) {
                    case TANGENT -> {
                        if (spec.effectiveTangentAvailable(hasPrev)) {
                            arc = sane(Arc2.fromTangent(start, heading, end), chord);
                            // A target behind the start would need almost a full circle: use a smooth curve instead,
                            // ending opposite to the heading (a proper hairpin) when the target lies behind.
                            if (arc == null) {
                                Vec2 chordDir = end.sub(start).normalize();
                                Vec2 te = chordDir.dot(heading) < 0 ? heading.scale(-1) : chordDir;
                                arc = hermite(start, heading, end, te).get(0);
                            }
                        } else if (extra1 != null) {
                            arc = sane(Arc2.throughPoints(start, extra1, end), chord);
                        }
                    }
                    case THROUGH_POINT -> { if (extra1 != null) arc = sane(Arc2.throughPoints(start, extra1, end), chord); }
                    case RADIUS -> arc = Arc2.fromRadius(start, end, spec.radius(), spec.turnLeft());
                }
                if (arc == null) arc = new Line2(start, end);
                if (spec.alignEnd() && arc instanceof Arc2) {
                    // Keep a true circular arc but end it on an axis: arc that turns onto the snapped
                    // direction, then a straight run to the end point.
                    Vec2 te = Heading.snapCardinal(arc.endTangent());
                    List<Curve2> aligned = arcThenLine(start, arc.startTangent(), end, te);
                    curves.addAll(aligned != null ? aligned : hermite(start, arc.startTangent(), end, te));
                } else {
                    curves.add(arc);
                }
            }
            case BEZIER -> {
                boolean auto = spec.smoothJoin() && hasPrev;
                double dist = start.distanceTo(end);
                Vec2 c1, c2;
                if (spec.bezierKind() == BezierKind.QUADRATIC) {
                    Vec2 c;
                    if (auto) {
                        double along = Math.max(0.5, end.sub(start).dot(prevTan));
                        c = start.add(prevTan.scale(along));
                    } else {
                        c = extra1 != null ? extra1 : start.lerp(end, 0.5);
                    }
                    CubicBezier2 q = CubicBezier2.fromQuadratic(start, c, end);
                    c1 = q.p1();
                    c2 = q.p2();
                } else if (auto) {
                    c1 = start.add(prevTan.scale(dist / 3));
                    c2 = extra1 != null ? extra1 : end.sub(end.sub(start).normalize().scale(dist / 3));
                } else {
                    c1 = extra1 != null ? extra1 : start.lerp(end, 1.0 / 3);
                    c2 = extra2 != null ? extra2 : end.lerp(start, 1.0 / 3);
                }
                if (spec.alignStart() && !auto) c1 = start.add(Heading.snapCardinal(c1.sub(start)).scale(Math.max(1, c1.distanceTo(start))));
                if (spec.alignEnd()) c2 = end.sub(Heading.snapCardinal(end.sub(c2)).scale(Math.max(1, c2.distanceTo(end))));
                curves.add(new CubicBezier2(start, c1, c2, end));
            }
            case S_BEND -> curves.addAll(sBend(start, heading, end, spec.sBendStyle()));
            default -> curves.add(new Line2(start, end));
        }
        return PathSegment.uniform(curves, y0, y1, draft);
    }

    /** Smooth curve through every point (Catmull-Rom style tangents, exact cubic Béziers between points). */
    private static PathSegment spline(SegmentSpec spec, List<PlanPoint> pts, Vec2 start, double y0, Vec2 prevTan, boolean draft) {
        List<Vec2> nodes = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        nodes.add(start);
        ys.add(y0);
        for (PlanPoint p : pts) {
            Vec2 v = p.xz();
            if (v.distanceTo(nodes.get(nodes.size() - 1)) < 1e-6) continue;
            nodes.add(v);
            ys.add(p.y());
        }
        int n = nodes.size();
        if (n < 2) return null;
        Vec2[] tangents = new Vec2[n];
        for (int i = 1; i < n - 1; i++) {
            Vec2 a = nodes.get(i).sub(nodes.get(i - 1)).normalize();
            Vec2 b = nodes.get(i + 1).sub(nodes.get(i)).normalize();
            Vec2 t = a.add(b);
            tangents[i] = t.lengthSq() < 1e-9 ? b : t.normalize();
        }
        Vec2 firstChord = nodes.get(1).sub(nodes.get(0)).normalize();
        Vec2 lastChord = nodes.get(n - 1).sub(nodes.get(n - 2)).normalize();
        Vec2 fixed = spec.heading().vector();
        if (prevTan != null && (spec.smoothJoin() || spec.heading() == Heading.PREVIOUS)) tangents[0] = prevTan;
        else if (fixed != null) tangents[0] = fixed;
        else tangents[0] = spec.alignStart() ? Heading.snapCardinal(firstChord) : (n > 2 ? reflectTangent(firstChord, tangents[1]) : firstChord);
        tangents[n - 1] = spec.alignEnd() ? Heading.snapCardinal(lastChord) : (n > 2 ? reflectTangent(lastChord, tangents[n - 2]) : lastChord);

        List<Curve2> curves = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Vec2 a = nodes.get(i), b = nodes.get(i + 1);
            double k = a.distanceTo(b) / 3;
            curves.add(new CubicBezier2(a, a.add(tangents[i].scale(k)), b.sub(tangents[i + 1].scale(k)), b));
        }
        double[] heights = new double[n];
        for (int i = 0; i < n; i++) heights[i] = ys.get(i);
        return new PathSegment(curves, heights, false, draft);
    }

    /** End tangent for an open spline end: the chord mirrored around the neighbouring tangent so the curve doesn't kink. */
    private static Vec2 reflectTangent(Vec2 chord, Vec2 neighbour) {
        Vec2 t = chord.scale(2).sub(neighbour);
        return t.lengthSq() < 1e-9 ? chord : t.normalize();
    }

    /**
     * Circular arc from p (tangent t) turning onto direction te, followed by a straight along te that
     * ends exactly at q. Returns null when no such construction exists.
     */
    public static List<Curve2> arcThenLine(Vec2 p, Vec2 t, Vec2 q, Vec2 te) {
        t = t.normalize();
        te = te.normalize();
        Vec2 d = q.sub(p);
        double cross = t.x() * te.z() - t.z() * te.x();
        double turn;
        if (Math.abs(cross) < 1e-9) {
            if (t.dot(te) > 0) {
                // Same direction: a straight line if q is already on the ray, otherwise impossible with one arc.
                return Math.abs(d.dot(t.left())) < 1e-6 && d.dot(t) > 0 ? List.of(new Line2(p, q)) : null;
            }
            turn = d.dot(t.left()) >= 0 ? 1 : -1; // U-turn: centre on the side of q
        } else {
            turn = cross > 0 ? -1 : 1; // cross > 0 means te is to the right of t (clockwise from above)
        }
        // left turn: centre = point + r*left(tangent); right turn: centre = point - r*left(tangent)
        Vec2 dir = t.left().sub(te.left()).scale(turn); // E = P + r * dir
        double denom = dir.dot(te.left());
        if (Math.abs(denom) < 1e-9) return null;
        double r = d.dot(te.left()) / denom;
        if (r < 0.5) return null;
        Vec2 e = p.add(dir.scale(r));
        double run = q.sub(e).dot(te);
        if (run < -1e-6) return null;
        Arc2 arc = Arc2.fromTangent(p, t, e);
        if (arc == null) return null;
        List<Curve2> out = new ArrayList<>();
        out.add(arc);
        if (run > 1e-6) out.add(new Line2(e, q));
        return out;
    }

    /** Cubic with prescribed unit tangents at both ends. */
    static List<Curve2> hermite(Vec2 p, Vec2 t, Vec2 q, Vec2 te) {
        double k = Math.max(1, p.distanceTo(q) / 3);
        return List.of(new CubicBezier2(p, p.add(t.normalize().scale(k)), q.sub(te.normalize().scale(k)), q));
    }

    /** Start direction: the previous segment when smooth-joining, else a fixed heading, else the snapped chord. */
    static Vec2 resolveHeading(SegmentSpec spec, Vec2 start, Vec2 end, Vec2 prevTan) {
        if (prevTan != null && (spec.smoothJoin() || spec.heading() == Heading.PREVIOUS)) return prevTan;
        Vec2 fixed = spec.heading().vector();
        if (fixed != null) return fixed;
        return Heading.snapCardinal(end.sub(start));
    }

    /** Largest arc sweep accepted before an arc is treated as unreasonable (nearly a full circle). */
    static final double MAX_SWEEP = 1.5 * Math.PI;

    /** Rejects arcs that nearly close a circle or whose radius dwarfs the chord; they would cover a huge area. */
    static Arc2 sane(Arc2 arc, double chord) {
        if (arc == null) return null;
        if (Math.abs(arc.sweep()) > MAX_SWEEP) return null;
        if (!Double.isFinite(arc.radius()) || arc.radius() > 4 * chord + 64) return null;
        return arc;
    }

    /** Builds all finished segments plus the draft when it has at least an end point. */
    public static List<PathSegment> build(CurvePlan plan) {
        List<PathSegment> out = new ArrayList<>();
        Vec2 pos = null, tan = null;
        double y = 0;
        List<SegmentSpec> specs = plan.segments();
        for (int i = 0; i < specs.size(); i++) {
            SegmentSpec spec = specs.get(i);
            boolean first = pos == null;
            PathSegment seg = buildSegment(spec, pos, tan, y, first, false);
            if (seg == null) {
                // Degenerate (e.g. zero length) segment: still establish the path start so later segments survive.
                if (first && !spec.points().isEmpty()) {
                    // The end point is the second click of a first segment (start, end, controls...).
                    PlanPoint p = spec.points().get(Math.min(1, spec.points().size() - 1));
                    pos = p.xz();
                    y = p.y();
                }
                continue;
            }
            out.add(seg);
            pos = seg.end();
            tan = seg.endTangent();
            y = seg.y1();
        }
        SegmentSpec draft = plan.draft();
        boolean first = pos == null;
        if (draft.points().size() >= (first ? 2 : 1)) {
            PathSegment seg = buildSegment(draft, pos, tan, y, first, true);
            if (seg != null) out.add(seg);
        }
        return out;
    }

    static List<Curve2> sBend(Vec2 start, Vec2 heading, Vec2 end, SBendStyle style) {
        Vec2 t = heading.normalize();
        Vec2 d = end.sub(start);
        double along = d.dot(t);
        double lateral = d.dot(t.left());
        if (along < 0.5) {
            // Heading points away from the end: just draw a straight line.
            return List.of(new Line2(start, end));
        }
        if (Math.abs(lateral) < 1e-6) return List.of(new Line2(start, end));
        if (style == SBendStyle.TWO_ARCS) {
            // Reverse curve: two equal arcs meeting at the midpoint; the second arc continues the first one's tangent.
            Vec2 mid = start.add(d.scale(0.5));
            Arc2 a1 = Arc2.fromTangent(start, t, mid);
            Arc2 a2 = a1 == null ? null : Arc2.fromTangent(a1.end(), a1.endTangent(), end);
            if (a1 != null && a2 != null) return List.of(a1, a2);
            return List.of(new Line2(start, end));
        }
        double k = Math.max(1.0, along * 0.5);
        return List.of(new CubicBezier2(start, start.add(t.scale(k)), end.sub(t.scale(k)), end));
    }

}
