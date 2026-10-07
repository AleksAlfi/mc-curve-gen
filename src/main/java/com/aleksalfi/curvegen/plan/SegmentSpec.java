package com.aleksalfi.curvegen.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * A segment as entered by the player. {@code points} holds the clicked points in click order. For the
 * first segment of a path the first point is the start; later segments start where the previous one ended.
 * After the (optional) start, the click order is: end point, then any through/control points.
 */
public record SegmentSpec(SegmentType type, List<PlanPoint> points, ArcMode arcMode, double radius, boolean turnLeft,
                          BezierKind bezierKind, SBendStyle sBendStyle, Heading heading, boolean smoothJoin,
                          boolean alignStart, boolean alignEnd) {

    /** Splines never finish on their own; the player finishes them from the options screen or command. */
    public static final int OPEN_ENDED = Integer.MAX_VALUE;

    public static SegmentSpec defaults() {
        return new SegmentSpec(SegmentType.SPLINE, List.of(), ArcMode.TANGENT, 12, true, BezierKind.CUBIC,
                SBendStyle.SMOOTH, Heading.AUTO, true, false, false);
    }

    public SegmentSpec withType(SegmentType t) { return new SegmentSpec(t, points, arcMode, radius, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withPoints(List<PlanPoint> p) { return new SegmentSpec(type, List.copyOf(p), arcMode, radius, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withArcMode(ArcMode m) { return new SegmentSpec(type, points, m, radius, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withRadius(double r) { return new SegmentSpec(type, points, arcMode, r, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withTurnLeft(boolean l) { return new SegmentSpec(type, points, arcMode, radius, l, bezierKind, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withBezierKind(BezierKind k) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, k, sBendStyle, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withSBendStyle(SBendStyle s) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, bezierKind, s, heading, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withHeading(Heading h) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, bezierKind, sBendStyle, h, smoothJoin, alignStart, alignEnd); }
    public SegmentSpec withSmoothJoin(boolean s) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, bezierKind, sBendStyle, heading, s, alignStart, alignEnd); }
    public SegmentSpec withAlignStart(boolean a) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, a, alignEnd); }
    public SegmentSpec withAlignEnd(boolean a) { return new SegmentSpec(type, points, arcMode, radius, turnLeft, bezierKind, sBendStyle, heading, smoothJoin, alignStart, a); }

    public SegmentSpec plusPoint(PlanPoint p) {
        List<PlanPoint> l = new ArrayList<>(points);
        l.add(p);
        return withPoints(l);
    }

    public SegmentSpec minusLastPoint() {
        if (points.isEmpty()) return this;
        return withPoints(points.subList(0, points.size() - 1));
    }

    /** Same options, no points. */
    public SegmentSpec cleared() { return withPoints(List.of()); }

    /** Number of clicks this segment needs, given whether a previous segment supplies the start and tangent. */
    public int requiredPoints(boolean first, boolean hasPreviousTangent) {
        if (type == SegmentType.SPLINE) return OPEN_ENDED;
        int n = first ? 1 : 0; // explicit start
        n += 1; // end point
        switch (type) {
            case STRAIGHT -> {}
            case ARC -> {
                switch (arcMode) {
                    case TANGENT -> { if (!effectiveTangentAvailable(hasPreviousTangent)) n += 1; }
                    case THROUGH_POINT -> n += 1;
                    case RADIUS -> {}
                }
            }
            case BEZIER -> {
                boolean auto = smoothJoin && hasPreviousTangent;
                n += switch (bezierKind) {
                    case QUADRATIC -> auto ? 0 : 1;
                    case CUBIC -> auto ? 1 : 2;
                };
            }
            case S_BEND -> {}
        }
        return n;
    }

    /**
     * Whether a start tangent is known for TANGENT arcs: previous segment, an explicit cardinal heading, or
     * "align start to axis" (which snaps the direction towards the end point).
     */
    public boolean effectiveTangentAvailable(boolean hasPreviousTangent) {
        if (heading.vector() != null || alignStart) return true;
        return hasPreviousTangent && (smoothJoin || heading == Heading.PREVIOUS);
    }

    /** Minimum clicks before an open-ended segment (spline) can be finished. */
    public int minimumPoints(boolean first) { return first ? 2 : 1; }

    /** Human readable description of what the next click will set (null when the segment is complete). */
    public String nextClickLabel(boolean first, boolean hasPreviousTangent) {
        int have = points.size();
        int req = requiredPoints(first, hasPreviousTangent);
        if (req != OPEN_ENDED && have > 0 && have >= req) return "end point of the next segment";
        int idx = first ? have - 1 : have; // index relative to "end point = 0"
        if (first && have == 0) return "start point";
        if (type == SegmentType.SPLINE) return "spline point " + (idx + 1) + " (finish it in the options or with /curvegen finish)";
        if (idx == 0) return "end point";
        return switch (type) {
            case ARC -> arcMode == ArcMode.THROUGH_POINT || !effectiveTangentAvailable(hasPreviousTangent) ? "point on the arc" : null;
            case BEZIER -> {
                boolean auto = smoothJoin && hasPreviousTangent;
                if (bezierKind == BezierKind.QUADRATIC) yield idx == 1 && !auto ? "control point" : null;
                if (auto) yield idx == 1 ? "control point (near end)" : null;
                yield idx == 1 ? "control point 1 (near start)" : idx == 2 ? "control point 2 (near end)" : null;
            }
            default -> null;
        };
    }
}
