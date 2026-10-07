package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.geom.Arc2;
import com.aleksalfi.curvegen.geom.PathSampler;
import com.aleksalfi.curvegen.geom.PathSegment;
import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.geom.Rasterizer;
import com.aleksalfi.curvegen.geom.Vec2;
import com.aleksalfi.curvegen.plan.ArcMode;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.Heading;
import com.aleksalfi.curvegen.plan.PathBuilder;
import com.aleksalfi.curvegen.plan.PlanPoint;
import com.aleksalfi.curvegen.plan.SBendStyle;
import com.aleksalfi.curvegen.plan.SegmentSpec;
import com.aleksalfi.curvegen.plan.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {

    @Test
    void arcFromTangentEndsAtTarget() {
        Vec2 p = new Vec2(0, 0), q = new Vec2(10, 10);
        Arc2 a = Arc2.fromTangent(p, new Vec2(1, 0), q);
        assertNotNull(a);
        assertEquals(0, a.start().distanceTo(p), 1e-9);
        assertEquals(0, a.end().distanceTo(q), 1e-9);
        assertEquals(1, a.startTangent().dot(new Vec2(1, 0)), 1e-9);
        assertEquals(10, a.radius(), 1e-9);
        assertEquals(Math.PI / 2, Math.abs(a.sweep()), 1e-9);
    }

    @Test
    void arcFromTangentLeftTurn() {
        // Heading east, target to the north (negative z) => left turn.
        Arc2 a = Arc2.fromTangent(new Vec2(0, 0), new Vec2(1, 0), new Vec2(10, -10));
        assertNotNull(a);
        assertEquals(0, a.end().distanceTo(new Vec2(10, -10)), 1e-9);
        assertTrue(a.sweep() < 0);
        assertEquals(0, a.endTangent().distanceTo(new Vec2(0, -1)), 1e-9);
    }

    @Test
    void arcThroughPointsPassesThroughMiddle() {
        Vec2 p = new Vec2(0, 0), m = new Vec2(5, 5), q = new Vec2(10, 0);
        Arc2 a = Arc2.throughPoints(p, m, q);
        assertNotNull(a);
        assertEquals(0, a.point(0.5).distanceTo(m), 1e-9);
        assertEquals(0, a.end().distanceTo(q), 1e-9);
    }

    @Test
    void arcFromRadiusMinor() {
        Arc2 a = Arc2.fromRadius(new Vec2(0, 0), new Vec2(10, 0), 10, true);
        assertNotNull(a);
        assertEquals(0, a.end().distanceTo(new Vec2(10, 0)), 1e-9);
        assertTrue(Math.abs(a.sweep()) <= Math.PI + 1e-9);
        assertEquals(10, a.radius(), 1e-9);
    }

    @Test
    void sBendArcsMeetTheEndWithSameHeading() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.S_BEND).withSBendStyle(SBendStyle.TWO_ARCS)
                .withHeading(Heading.EAST).withPoints(List.of(new PlanPoint(0, 64, 0), new PlanPoint(30, 64, 8)));
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        assertNotNull(seg);
        assertEquals(2, seg.curves().size());
        assertEquals(0, seg.end().distanceTo(new Vec2(30, 8)), 1e-6);
        assertEquals(0, seg.endTangent().distanceTo(new Vec2(1, 0)), 1e-6);
        // Both arcs same radius
        assertEquals(((Arc2) seg.curves().get(0)).radius(), ((Arc2) seg.curves().get(1)).radius(), 1e-6);
    }

    @Test
    void sBendSmoothTangents() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.S_BEND).withSBendStyle(SBendStyle.SMOOTH)
                .withHeading(Heading.AUTO).withPoints(List.of(new PlanPoint(0, 64, 0), new PlanPoint(0, 64, 30)));
        // Pure longitudinal: no lateral offset => straight
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        assertNotNull(seg);
        spec = spec.withPoints(List.of(new PlanPoint(0, 64, 0), new PlanPoint(6, 64, 30)));
        seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        assertEquals(0, seg.endTangent().distanceTo(new Vec2(0, 1)), 1e-6);
        assertEquals(0, seg.start().distanceTo(new Vec2(0, 0)), 1e-9);
    }

    @Test
    void chainedPlanIsContinuous() {
        CurvePlan plan = CurvePlan.DEFAULT;
        plan = plan.withDraftOptions(SegmentSpec.defaults().withType(SegmentType.STRAIGHT));
        plan = plan.addPoint(new PlanPoint(0.5, 64, 0.5));
        plan = plan.addPoint(new PlanPoint(20.5, 64, 0.5));
        assertEquals(1, plan.segments().size());
        plan = plan.withDraftOptions(SegmentSpec.defaults().withType(SegmentType.ARC).withArcMode(ArcMode.TANGENT));
        plan = plan.addPoint(new PlanPoint(40.5, 70, 20.5));
        assertEquals(2, plan.segments().size());
        List<PathSegment> segs = PathBuilder.build(plan);
        assertEquals(2, segs.size());
        assertEquals(0, segs.get(0).end().distanceTo(segs.get(1).start()), 1e-9);
        assertEquals(0, segs.get(0).endTangent().distanceTo(segs.get(1).curves().get(0).startTangent()), 1e-6);
        assertEquals(64, segs.get(1).y0(), 1e-9);
        assertEquals(70, segs.get(1).y1(), 1e-9);
        // undo reopens the arc as draft
        plan = plan.undoPoint();
        assertEquals(1, plan.segments().size());
        assertEquals(0, plan.draft().points().size());
    }

    @Test
    void rasterizeStraightRoadHasExpectedWidth() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.STRAIGHT)
                .withPoints(List.of(new PlanPoint(0.5, 64, 0.5), new PlanPoint(20.5, 64, 0.5)));
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        Polyline line = PathSampler.sample(List.of(seg), 0.5, PathSampler.Elevation.LINEAR);
        List<Rasterizer.Column> cols = Rasterizer.rasterize(line, new double[]{1, 3, 1}, 4);
        // Width 5 centered on z=0.5 => z in [-2,2] full coverage
        long full = cols.stream().filter(c -> c.coverage() > 0.99).count();
        assertEquals(21 * 5, full);
        assertTrue(cols.stream().allMatch(c -> c.z() >= -2 && c.z() <= 2));
        assertTrue(cols.stream().allMatch(c -> c.x() >= 0 && c.x() <= 20));
        // Lane assignment: z = -2 is lane 0 (left when travelling east is north = -z)
        Rasterizer.Column left = cols.stream().filter(c -> c.x() == 10 && c.z() == -2).findFirst().orElseThrow();
        assertEquals(0, left.lane());
        Rasterizer.Column mid = cols.stream().filter(c -> c.x() == 10 && c.z() == 0).findFirst().orElseThrow();
        assertEquals(1, mid.lane());
        assertEquals(64, mid.height(), 1e-9);
        // Outward normal for the left edge points north (-z)
        assertTrue(left.outwardZ() < -0.9);
    }

    @Test
    void rasterizeFractionalWidthGivesPartialCoverage() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.STRAIGHT)
                .withPoints(List.of(new PlanPoint(0.5, 64, 0.5), new PlanPoint(10.5, 64, 0.5)));
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        Polyline line = PathSampler.sample(List.of(seg), 0.5, PathSampler.Elevation.LINEAR);
        List<Rasterizer.Column> cols = Rasterizer.rasterize(line, new double[]{4}, 8);
        // width 4 centered on 0.5 => z in [-1.5, 2.5]: z=-2 half covered, z=2 half covered
        Rasterizer.Column edge = cols.stream().filter(c -> c.x() == 5 && c.z() == -2).findFirst().orElseThrow();
        assertEquals(0.5, edge.coverage(), 1e-9);
        Rasterizer.Column edge2 = cols.stream().filter(c -> c.x() == 5 && c.z() == 2).findFirst().orElseThrow();
        assertEquals(0.5, edge2.coverage(), 1e-9);
    }

    @Test
    void rampHeightInterpolates() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.STRAIGHT)
                .withPoints(List.of(new PlanPoint(0.5, 64, 0.5), new PlanPoint(10.5, 69, 0.5)));
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        Polyline line = PathSampler.sample(List.of(seg), 0.5, PathSampler.Elevation.LINEAR);
        List<Rasterizer.Column> cols = Rasterizer.rasterize(line, new double[]{1}, 4);
        Rasterizer.Column c = cols.stream().filter(k -> k.x() == 5 && k.z() == 0).findFirst().orElseThrow();
        assertEquals(66.5, c.height(), 1e-6);
    }

    @Test
    void splinePassesThroughAllPointsAndAlignsEnds() {
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.SPLINE).withAlignStart(true).withAlignEnd(true)
                .withPoints(List.of(new PlanPoint(0, 64, 0), new PlanPoint(20, 66, 5), new PlanPoint(40, 70, -3), new PlanPoint(70, 70, 1)));
        CurvePlan plan = CurvePlan.DEFAULT.withDraft(spec);
        assertTrue(plan.segments().isEmpty(), "splines never auto-finish");
        plan = plan.finishDraft();
        assertEquals(1, plan.segments().size());
        PathSegment seg = PathBuilder.build(plan).get(0);
        assertEquals(3, seg.curves().size());
        assertEquals(0, seg.curves().get(1).start().distanceTo(new Vec2(20, 5)), 1e-9);
        assertEquals(0, seg.curves().get(2).start().distanceTo(new Vec2(40, -3)), 1e-9);
        assertEquals(0, seg.startTangent().distanceTo(new Vec2(1, 0)), 1e-6);
        assertEquals(0, seg.endTangent().distanceTo(new Vec2(1, 0)), 1e-6);
        // Tangent continuity at the inner nodes
        assertEquals(0, seg.curves().get(0).endTangent().distanceTo(seg.curves().get(1).startTangent()), 1e-6);
        assertArrayEquals(new double[]{64, 66, 70, 70}, seg.ys(), 1e-9);
        assertFalse(seg.uniform());
    }

    @Test
    void alignedArcEndsOnAxisWithArcThenStraight() {
        // Heading east, end point to the south-east but further east: should be a 90° arc then a straight run south? No:
        // the free tangent arc to (40, 20) ends heading ~ south-east; snapped to east -> arc turning... use (20, 30) which
        // ends heading south after snapping.
        SegmentSpec spec = SegmentSpec.defaults().withType(SegmentType.ARC).withArcMode(ArcMode.TANGENT)
                .withHeading(Heading.EAST).withAlignEnd(true)
                .withPoints(List.of(new PlanPoint(0, 64, 0), new PlanPoint(20, 64, 30)));
        PathSegment seg = PathBuilder.buildSegment(spec, null, null, 0, true, false);
        assertNotNull(seg);
        assertEquals(0, seg.end().distanceTo(new Vec2(20, 30)), 1e-6);
        assertEquals(0, seg.endTangent().distanceTo(new Vec2(0, 1)), 1e-6);
        assertEquals(0, seg.startTangent().distanceTo(new Vec2(1, 0)), 1e-6);
        assertTrue(seg.curves().get(0) instanceof Arc2);
        assertEquals(2, seg.curves().size()); // quarter arc of radius 20, then 10 blocks straight south
        assertEquals(20, ((Arc2) seg.curves().get(0)).radius(), 1e-6);
    }

    @Test
    void arcThenLineReturnsNullWhenImpossible() {
        // End point behind the start while asked to end heading east: no single arc + straight does that.
        assertNull(PathBuilder.arcThenLine(new Vec2(0, 0), new Vec2(1, 0), new Vec2(-30, 0.5), new Vec2(1, 0)));
    }
}
