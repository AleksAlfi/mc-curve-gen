package com.aleksalfi.curvegen.plan;

import java.util.ArrayList;
import java.util.List;

/** Bounds applied to every plan on both sides, so a client cannot make the server rasterize the whole world. */
public final class PlanLimits {
    private PlanLimits() {}

    public static final int MAX_SEGMENTS = 256;
    public static final int MAX_POINTS_PER_SEGMENT = 512;
    public static final int MAX_LANES = 32;
    public static final double MAX_LANE_WIDTH = 64;
    public static final double MAX_TOTAL_WIDTH = 256;
    public static final int MAX_THICKNESS = 32;
    public static final double MAX_Y_OFFSET = 384;
    public static final double MAX_RADIUS = 1024;
    public static final double MAX_COORD = 30_000_000;
    public static final double MAX_Y = 4096;
    public static final int MAX_NAME_LENGTH = 64;
    /** Largest bounding box (in columns) the rasterizer will attempt. */
    public static final long MAX_COLUMNS = 4_000_000L;

    /** Returns true when the plan is within all limits. */
    public static boolean isValid(CurvePlan plan) {
        return sanitize(plan).equals(plan);
    }

    /** Clamps every numeric field and truncates over-long lists. */
    public static CurvePlan sanitize(CurvePlan plan) {
        List<SegmentSpec> segments = new ArrayList<>();
        for (SegmentSpec s : plan.segments()) {
            if (segments.size() >= MAX_SEGMENTS) break;
            if (s.points().isEmpty()) continue;
            segments.add(sanitize(s));
        }
        SegmentSpec draft = sanitize(plan.draft());
        ProfileSpec p = plan.profile();
        List<LaneSpec> lanes = new ArrayList<>();
        double total = 0;
        for (LaneSpec l : p.lanes()) {
            if (lanes.size() >= MAX_LANES) break;
            double w = finite(l.width(), 1, 0, MAX_LANE_WIDTH);
            if (total + w > MAX_TOTAL_WIDTH) w = Math.max(0, MAX_TOTAL_WIDTH - total);
            total += w;
            lanes.add(new LaneSpec(w, clip(l.block(), 256), clip(l.material(), 256)));
        }
        if (lanes.isEmpty()) lanes.add(new LaneSpec(1, "minecraft:stone", ""));
        ProfileSpec profile = new ProfileSpec(List.copyOf(lanes),
                (int) finite(p.thickness(), 1, 1, MAX_THICKNESS),
                clip(p.baseBlock(), 256),
                finite(p.yOffset(), 0, -MAX_Y_OFFSET, MAX_Y_OFFSET),
                p.edgeSmoothing(), p.slopeSmoothing(), p.elevationMode(),
                (int) finite(p.quality(), 1, 1, 3));
        return new CurvePlan(List.copyOf(segments), draft, profile, clip(plan.schematicName(), MAX_NAME_LENGTH));
    }

    static SegmentSpec sanitize(SegmentSpec s) {
        List<PlanPoint> pts = new ArrayList<>();
        for (PlanPoint p : s.points()) {
            if (pts.size() >= MAX_POINTS_PER_SEGMENT) break;
            pts.add(new PlanPoint(finite(p.x(), 0, -MAX_COORD, MAX_COORD), finite(p.y(), 0, -MAX_Y, MAX_Y), finite(p.z(), 0, -MAX_COORD, MAX_COORD)));
        }
        return s.withPoints(pts).withRadius(finite(s.radius(), 12, 0.5, MAX_RADIUS));
    }

    private static double finite(double v, double fallback, double min, double max) {
        if (!Double.isFinite(v)) v = fallback;
        return Math.max(min, Math.min(max, v));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
