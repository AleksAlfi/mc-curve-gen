package com.aleksalfi.curvegen.plan;

import java.util.ArrayList;
import java.util.List;

/** Everything a Curve Planner item remembers: finished segments, the segment being drawn, and the road profile. */
public record CurvePlan(List<SegmentSpec> segments, SegmentSpec draft, ProfileSpec profile, String schematicName) {

    public static final CurvePlan DEFAULT = new CurvePlan(List.of(), SegmentSpec.defaults(), ProfileSpec.defaults(), "curve");

    public CurvePlan withSegments(List<SegmentSpec> s) { return new CurvePlan(List.copyOf(s), draft, profile, schematicName); }
    public CurvePlan withDraft(SegmentSpec d) { return new CurvePlan(segments, d, profile, schematicName); }
    public CurvePlan withProfile(ProfileSpec p) { return new CurvePlan(segments, draft, p, schematicName); }
    public CurvePlan withSchematicName(String n) { return new CurvePlan(segments, draft, profile, n); }

    public boolean isEmpty() { return segments.isEmpty() && draft.points().isEmpty(); }

    /** Everything that affects the generated blocks (the schematic name does not). */
    public CurvePlan geometryKey() { return withSchematicName(""); }

    public boolean draftIsFirst() { return segments.isEmpty(); }

    public boolean hasPreviousTangent() { return !segments.isEmpty(); }

    public int draftRequiredPoints() { return draft.requiredPoints(draftIsFirst(), hasPreviousTangent()); }

    /** True when the draft already has every point its type needs (it is finalized by the next click). */
    public boolean draftComplete() {
        int req = draftRequiredPoints();
        return req != SegmentSpec.OPEN_ENDED && !draft.points().isEmpty() && draft.points().size() >= req;
    }

    /** The last point of the path: end of the draft, else end of the last segment. */
    public PlanPoint lastPoint() {
        if (!draft.points().isEmpty()) return draft.points().get(draft.points().size() - 1);
        for (int i = segments.size() - 1; i >= 0; i--) {
            List<PlanPoint> pts = segments.get(i).points();
            if (!pts.isEmpty()) return pts.get(pts.size() - 1);
        }
        return null;
    }

    /** Where the path currently ends: the last draft point, else the end point of the last segment. */
    public PlanPoint pathEndPoint() {
        if (!draft.points().isEmpty()) return draft.points().get(draft.points().size() - 1);
        for (int i = segments.size() - 1; i >= 0; i--) {
            List<PlanPoint> pts = segments.get(i).points();
            if (pts.isEmpty()) continue;
            if (segments.get(i).type() == SegmentType.SPLINE) return pts.get(pts.size() - 1);
            int endIndex = i == 0 ? 1 : 0; // after the (optional) start click comes the end point
            return pts.get(Math.min(endIndex, pts.size() - 1));
        }
        return null;
    }

    public int totalPoints() {
        int n = draft.points().size();
        for (SegmentSpec s : segments) n += s.points().size();
        return n;
    }

    /**
     * Adds a clicked point; finalizes the draft into a segment when it has enough points. Returns the same
     * plan when the click is ignored (same block as the path end, or a point limit reached).
     */
    public CurvePlan addPoint(PlanPoint p) {
        PlanPoint last = lastPoint();
        PlanPoint end = pathEndPoint();
        if (same(last, p) || (draft.points().isEmpty() && same(end, p))) return this; // same block clicked twice
        if (totalPoints() >= PlanLimits.MAX_TOTAL_POINTS) return this;
        if (draft.points().size() >= PlanLimits.MAX_POINTS_PER_SEGMENT) return this;
        if (draftComplete()) {
            // The draft was completed by an option change; this click starts the next segment.
            CurvePlan finished = finalizeDraft(draftRequiredPoints());
            return finished == this ? this : finished.addPoint(p);
        }
        SegmentSpec d = draft.plusPoint(p);
        int req = d.requiredPoints(draftIsFirst(), hasPreviousTangent());
        if (req != SegmentSpec.OPEN_ENDED && d.points().size() >= req) return withDraft(d).finalizeDraft(req);
        return withDraft(d);
    }

    private static boolean same(PlanPoint a, PlanPoint b) {
        return a != null && b != null && Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.z() - b.z()) < 1e-6;
    }

    /** Moves the draft (trimmed to {@code keep} points) into the segment list. */
    private CurvePlan finalizeDraft(int keep) {
        if (segments.size() >= PlanLimits.MAX_SEGMENTS) return this;
        SegmentSpec d = keep < draft.points().size() ? draft.withPoints(draft.points().subList(0, keep)) : draft;
        List<SegmentSpec> s = new ArrayList<>(segments);
        s.add(d);
        return new CurvePlan(List.copyOf(s), d.cleared(), profile, schematicName);
    }

    /** Removes the last clicked point, reopening the last finished segment if the draft is empty. */
    public CurvePlan undoPoint() {
        if (!draft.points().isEmpty()) return withDraft(draft.minusLastPoint());
        if (segments.isEmpty()) return this;
        SegmentSpec last = segments.get(segments.size() - 1);
        List<SegmentSpec> s = new ArrayList<>(segments.subList(0, segments.size() - 1));
        return new CurvePlan(List.copyOf(s), last.minusLastPoint(), profile, schematicName);
    }

    /** Finishes an open-ended draft (spline) that has enough points; no-op for every other type. */
    public CurvePlan finishDraft() {
        if (draft.type() != SegmentType.SPLINE) return this;
        if (draft.points().size() < draft.minimumPoints(draftIsFirst())) return this;
        return finalizeDraft(draft.points().size());
    }

    public CurvePlan removeLastSegment() {
        if (!draft.points().isEmpty()) return withDraft(draft.cleared());
        if (segments.isEmpty()) return this;
        return withSegments(segments.subList(0, segments.size() - 1));
    }

    public CurvePlan clearPath() { return new CurvePlan(List.of(), draft.cleared(), profile, schematicName); }

    /**
     * Changes the options of the draft segment. Clicked points are kept; if the new type needs fewer points
     * the draft simply counts as complete and the next click starts the following segment.
     */
    public CurvePlan withDraftOptions(SegmentSpec options) {
        return withDraft(options.withPoints(draft.points()));
    }

    public PlanPoint firstPoint() {
        for (SegmentSpec s : segments) if (!s.points().isEmpty()) return s.points().get(0);
        return draft.points().isEmpty() ? null : draft.points().get(0);
    }
}
