package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanPoint;
import com.aleksalfi.curvegen.plan.SegmentSpec;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/** Compiles a curve plan for the preview. */
public final class CurvePreview {
    private CurvePreview() {}

    public static Compiled compile(CurvePlan plan, BlockGetter level) {
        PlanCompiler.Result r = PlanCompiler.compile(plan, level);
        List<Compiled.Marker> markers = new ArrayList<>();
        for (int i = 0; i < plan.segments().size(); i++) markers(markers, plan.segments().get(i), false, i == 0);
        markers(markers, plan.draft(), true, plan.segments().isEmpty());
        return new Compiled(r.blocks(), List.of(r.centerline()), markers, List.of(), r.blocks().warnings());
    }

    private static void markers(List<Compiled.Marker> out, SegmentSpec seg, boolean draft, boolean first) {
        List<PlanPoint> pts = seg.points();
        int base = first ? 1 : 0;
        for (int i = 0; i < pts.size(); i++) {
            PlanPoint p = pts.get(i);
            boolean control = i > base; // after the (optional) start and the end point come through/control points
            float r = control ? 1f : 0.2f, g = control ? 0.3f : 1f, b = 1f;
            if (draft) r = Math.min(1, r + 0.3f);
            out.add(new Compiled.Marker(p.x(), p.y(), p.z(), r, g, b, 0.3f));
        }
    }
}
